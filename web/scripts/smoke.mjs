/**
 * Phase 0 真实浏览器冒烟：真实 MySQL 8.4 → Spring Boot → Vite proxy → Vue。
 *
 * 1. 在 MySQL 8.4 容器中新建隔离 schema（seafood_trace_phase0_demo_<随机后缀>），绝不触碰 seafood_trace；
 * 2. 以进程级随机 pepper 启动 Spring Boot（Flyway 在空 schema 上执行 V1~V8）；
 * 3. 通过 SQL 准备基础资料（组织、角色、账号、产品），通过真实 HTTP API（CSRF + Session）创建与提交批次、激活公开码；
 *    Phase 0 没有冻结 / 召回 / 关闭接口，FROZEN、RECALLED、CLOSED 状态在隔离 schema 中直接写入以验证展示与筛选；
 * 4. Playwright 在不使用任何拦截或替身的情况下走完整企业端与消费者路径；
 * 5. 无论成功失败都停止后端、删除 schema 并撤销授权，验证残留为 0。
 *
 * 密码、pepper、Cookie 与 CSRF 凭据只在进程环境与内存中传递，从不打印。
 */
import { randomBytes, randomUUID, pbkdf2Sync } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync, spawn, spawnSync } from 'node:child_process'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(scriptDir, '..')
const repositoryDir = resolve(webDir, '..')
const serverDir = resolve(repositoryDir, 'server')
const localEnvPath = resolve(repositoryDir, '.env')
const backendPort = 18081
const backendOrigin = `http://127.0.0.1:${backendPort}`
const containerName = process.env.SMOKE_MYSQL_CONTAINER || 'seafood-mysql'
const SAFE_SCHEMA = /^seafood_trace_phase0_demo_[a-f0-9]{10}$/

function readLocalEnvironment() {
  if (!existsSync(localEnvPath)) return {}
  return Object.fromEntries(
    readFileSync(localEnvPath, 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const separator = line.indexOf('=')
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim().replace(/^(['"])(.*)\1$/, '$2')]
      })
  )
}

const localEnv = readLocalEnvironment()
const setting = (key, fallback) => process.env[key] || localEnv[key] || fallback
const dbUser = setting('DB_USERNAME', 'trace_user')
const dbPassword = setting('DB_PASSWORD')
const dbRootPassword = setting('DB_ROOT_PASSWORD')
if (!dbPassword || !dbRootPassword) {
  throw new Error('真实冒烟需要通过环境变量或仓库根目录 .env 提供 DB_PASSWORD 与 DB_ROOT_PASSWORD')
}

const schema = `seafood_trace_phase0_demo_${randomBytes(5).toString('hex')}`
if (!SAFE_SCHEMA.test(schema)) throw new Error('生成的 schema 名称不符合安全前缀')

function mysql(sql, { root = false, database } = {}) {
  const args = ['exec', '-i', '-e', `MYSQL_PWD=${root ? dbRootPassword : dbPassword}`, containerName,
    'mysql', '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4',
    '-u', root ? 'root' : dbUser]
  if (database) args.push(database)
  return execFileSync('docker', args, { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }).trim()
}

function schemaExists() {
  return mysql(`SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '${schema}';`, { root: true }) !== '0'
}

function createIsolatedSchema() {
  if (schemaExists()) throw new Error(`隔离 schema ${schema} 已存在，拒绝复用`)
  mysql(`CREATE DATABASE \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${dbUser}'@'%';`, { root: true })
}

function dropIsolatedSchema() {
  if (!SAFE_SCHEMA.test(schema) || schema === 'seafood_trace') throw new Error('拒绝删除非隔离 schema')
  mysql(`REVOKE ALL PRIVILEGES ON \`${schema}\`.* FROM '${dbUser}'@'%';`, { root: true })
  mysql(`DROP DATABASE IF EXISTS \`${schema}\`;`, { root: true })
  if (schemaExists()) throw new Error(`隔离 schema ${schema} 删除后仍然存在`)
}

function assertPortFree(port) {
  return new Promise((resolvePromise, reject) => {
    const probe = createServer()
    probe.once('error', () => reject(new Error(`端口 ${port} 已被占用；请先停止占用进程，避免复用错误的服务`)))
    probe.once('listening', () => probe.close(() => resolvePromise()))
    probe.listen(port, '127.0.0.1')
  })
}

function startBackend(logBuffer) {
  const executable = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : './mvnw'
  const args = process.platform === 'win32'
    ? ['/d', '/s', '/c', `""${resolve(serverDir, 'mvnw.cmd')}" -q -DskipTests spring-boot:run"`]
    : ['-q', '-DskipTests', 'spring-boot:run']
  const child = spawn(executable, args, {
    cwd: serverDir,
    // 以绝对路径调用 mvnw.cmd，并让 cmd 原样解析引号（路径可包含空格）
    windowsVerbatimArguments: process.platform === 'win32',
    env: {
      ...process.env,
      DB_HOST: setting('DB_HOST', '127.0.0.1'),
      DB_PORT: setting('DB_PORT', '3307'),
      DB_NAME: schema,
      DB_USERNAME: dbUser,
      DB_PASSWORD: dbPassword,
      SERVER_PORT: String(backendPort),
      // 进程级临时 pepper：只存在于该子进程环境中，随 schema 一起销毁
      TRACE_BATCH_NO_HISTORY_PEPPER: randomBytes(48).toString('base64')
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout.on('data', (chunk) => { logBuffer.value += chunk.toString() })
  child.stderr.on('data', (chunk) => { logBuffer.value += chunk.toString() })
  return child
}

async function waitForBackend(processHandle, logBuffer) {
  const deadline = Date.now() + 180_000
  while (Date.now() < deadline) {
    if (processHandle.exitCode !== null) {
      throw new Error(`Spring Boot 提前退出。最近日志：\n${logBuffer.value.slice(-4000)}`)
    }
    try {
      const response = await fetch(`${backendOrigin}/actuator/health`)
      if (response.ok) return
    } catch {
      // 服务仍在启动
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000))
  }
  throw new Error(`等待 Spring Boot 启动超时。最近日志：\n${logBuffer.value.slice(-4000)}`)
}

function stopProcessTree(child) {
  if (!child || child.exitCode !== null || !child.pid) return
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

/** Spring Security 7 DelegatingPasswordEncoder 的 {pbkdf2@SpringSecurity_v5_8} 格式。 */
function springPbkdf2(password) {
  const salt = randomBytes(16)
  const hash = pbkdf2Sync(password, salt, 310000, 32, 'sha256')
  return `{pbkdf2@SpringSecurity_v5_8}${Buffer.concat([salt, hash]).toString('hex')}`
}

const sqlText = (value) => `'${String(value).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`

/** 只用于准备夹具的最小 HTTP 会话客户端（Cookie + CSRF），不打印任何凭据。 */
class ApiSession {
  cookie = ''

  async call(method, path, { body, headers = {} } = {}) {
    const response = await fetch(`${backendOrigin}${path}`, {
      method,
      headers: {
        Accept: 'application/json',
        ...(this.cookie ? { Cookie: this.cookie } : {}),
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers
      },
      body: body !== undefined ? JSON.stringify(body) : undefined
    })
    const setCookie = response.headers.getSetCookie?.() ?? []
    for (const entry of setCookie) {
      const pair = entry.split(';')[0]
      if (pair.startsWith('TRACESESSION=')) this.cookie = pair
    }
    const text = await response.text()
    const json = text ? JSON.parse(text) : null
    if (!response.ok) throw new Error(`${method} ${path} -> ${response.status} ${json?.code ?? ''}`)
    return json?.data
  }

  async write(method, path, body, headers = {}) {
    const csrf = await this.call('GET', '/api/v1/auth/csrf')
    return this.call(method, path, { body, headers: { [csrf.headerName]: csrf.token, ...headers } })
  }

  login(username, password) {
    return this.write('POST', '/api/v1/auth/login', { username, password })
  }
}

function seedMasterData(suffix, passwordA, passwordB) {
  mysql(`
    START TRANSACTION;
    INSERT INTO organization (org_no, name, org_type, credit_code, status)
      VALUES ('P0_PROC_${suffix}', 'Phase0冒烟加工企业_${suffix}', 'PROCESSOR', 'SMOKE-CREDIT-${suffix}', 'ACTIVE');
    SET @orgA = LAST_INSERT_ID();
    INSERT INTO organization (org_no, name, org_type, credit_code, status)
      VALUES ('P0_RETAIL_${suffix}', 'Phase0冒烟零售企业_${suffix}', 'RETAILER', 'SMOKE-CREDIT-B-${suffix}', 'ACTIVE');
    SET @orgB = LAST_INSERT_ID();
    INSERT INTO role (role_code, name, scope_type, status) VALUES ('OPERATOR', '企业操作员', 'ORG_ONLY', 'ACTIVE');
    SET @role = LAST_INSERT_ID();
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgA, 'p0_proc_${suffix}', 'Phase0冒烟操作员', ${sqlText(springPbkdf2(passwordA))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgB, 'p0_retail_${suffix}', 'Phase0冒烟零售操作员', ${sqlText(springPbkdf2(passwordB))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO product (product_code, public_name, category, specification, source_type, base_unit_code, status)
      VALUES ('P0-YELLOW-${suffix}', 'Phase0冒烟冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE');
    INSERT INTO product (product_code, public_name, category, specification, source_type, base_unit_code, status)
      VALUES ('P0-SHRIMP-${suffix}', 'Phase0冒烟冷冻南美白虾', 'CRUSTACEAN', '1kg/袋', 'IMPORT', 'kg', 'ACTIVE');
    COMMIT;
  `, { database: schema })
  const ids = mysql(`SELECT
      (SELECT id FROM organization WHERE org_no = 'P0_PROC_${suffix}'),
      (SELECT id FROM organization WHERE org_no = 'P0_RETAIL_${suffix}'),
      (SELECT id FROM product WHERE product_code = 'P0-YELLOW-${suffix}'),
      (SELECT id FROM product WHERE product_code = 'P0-SHRIMP-${suffix}');`, { database: schema }).split('\t').map(Number)
  return { orgA: ids[0], orgB: ids[1], productFish: ids[2], productShrimp: ids[3] }
}

async function createBatch(session, request, submit) {
  const created = await session.write('POST', '/api/v1/batches', request, { 'Idempotency-Key': randomUUID() })
  if (!submit) return created
  return session.write('POST', `/api/v1/batches/${created.id}/submit`, { version: created.version })
}

async function seedBusinessData(suffix, master, passwordA, passwordB) {
  const sessionA = new ApiSession()
  await sessionA.login(`p0_proc_${suffix}`, passwordA)
  const base = { batchType: 'SOURCE', unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场（冒烟夹具）' }

  const active = await createBatch(sessionA, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 1000, productionDate: '2026-09-01', captureDate: '2026-08-30', shelfLifeDays: 365 }, true)
  const draft = await createBatch(sessionA, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 250.5 }, false)
  const frozen = await createBatch(sessionA, { ...base, productId: master.productShrimp, quantity: 480, originType: 'IMPORT', originText: '进口原料（冒烟夹具）' }, true)
  const recalled = await createBatch(sessionA, { ...base, productId: master.productShrimp, externalBatchNo: 'P0-EXT-004', quantity: 60 }, true)
  const closed = await createBatch(sessionA, { ...base, productId: master.productFish, quantity: 12.345 }, true)
  const publicCode = await sessionA.write('POST', `/api/v1/batches/${active.id}/public-trace-code/activate`, undefined, { 'Idempotency-Key': randomUUID() })

  const sessionB = new ApiSession()
  await sessionB.login(`p0_retail_${suffix}`, passwordB)
  const foreign = await createBatch(sessionB, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 77 }, true)

  // Phase 0 没有冻结 / 召回 / 关闭业务接口：仅在隔离 schema 中直接写入以验证展示与筛选
  mysql(`
    UPDATE batch SET risk_status = 'FROZEN' WHERE id = ${frozen.id};
    UPDATE batch SET flow_status = 'CLOSED', risk_status = 'RECALLED' WHERE id = ${recalled.id};
    UPDATE batch SET flow_status = 'CLOSED' WHERE id = ${closed.id};
  `, { database: schema })

  return { active, draft, frozen, recalled, closed, foreign, publicTraceId: publicCode.publicId }
}

function databaseEvidence(orgA) {
  return mysql(`SELECT id, trace_batch_no, IFNULL(external_batch_no, '-'), flow_status, risk_status, org_id = ${orgA}
    FROM batch ORDER BY id;`, { database: schema })
}

function runBrowserSmoke(env) {
  const npmCommand = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'npm'
  const npmArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'npm.cmd run test:e2e -- tests/e2e/real-smoke.spec.ts --project=desktop --workers=1']
    : ['run', 'test:e2e', '--', 'tests/e2e/real-smoke.spec.ts', '--project=desktop', '--workers=1']
  const result = spawnSync(npmCommand, npmArgs, {
    cwd: webDir,
    env: { ...process.env, ...env, REAL_SMOKE: 'true', VITE_BACKEND_PROXY_TARGET: backendOrigin },
    stdio: 'inherit'
  })
  if (result.status !== 0) throw new Error(`真实前后端浏览器冒烟失败，退出码 ${result.status}`)
}

let backend
let schemaCreated = false
let primaryError
const backendLog = { value: '' }

try {
  const mysqlVersion = mysql('SELECT VERSION();', { root: true })
  if (!mysqlVersion.startsWith('8.4.')) throw new Error(`需要 MySQL 8.4，当前容器返回 ${mysqlVersion}`)
  console.log(`[smoke] MySQL ${mysqlVersion}; 隔离 schema: ${schema}`)
  await assertPortFree(backendPort)
  await assertPortFree(5173)

  createIsolatedSchema()
  schemaCreated = true
  backend = startBackend(backendLog)
  await waitForBackend(backend, backendLog)
  console.log(`[smoke] Spring Boot 已在 ${backendOrigin} 启动，Flyway 已迁移: ${mysql("SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM flyway_schema_history WHERE success = 1;", { database: schema })}`)

  const suffix = randomBytes(3).toString('hex')
  const passwordA = `P0!${randomBytes(18).toString('base64url')}`
  const passwordB = `P0!${randomBytes(18).toString('base64url')}`
  const master = seedMasterData(suffix, passwordA, passwordB)
  const fixture = await seedBusinessData(suffix, master, passwordA, passwordB)
  console.log('[smoke] MySQL batch 表（id, trace_batch_no, external_batch_no, flow, risk, 属于登录组织）:')
  console.log(databaseEvidence(master.orgA).split('\n').map((line) => `  ${line}`).join('\n'))

  const expected = {
    orgName: `Phase0冒烟加工企业_${suffix}`,
    fishName: 'Phase0冒烟冷冻大黄鱼',
    active: fixture.active.traceBatchNo,
    activeId: fixture.active.id,
    draft: fixture.draft.traceBatchNo,
    frozen: fixture.frozen.traceBatchNo,
    recalled: fixture.recalled.traceBatchNo,
    closed: fixture.closed.traceBatchNo,
    foreign: fixture.foreign.traceBatchNo,
    publicTraceId: fixture.publicTraceId
  }
  runBrowserSmoke({
    SMOKE_USERNAME: `p0_proc_${suffix}`,
    SMOKE_PASSWORD: passwordA,
    SMOKE_EXPECTED: JSON.stringify(expected)
  })
  console.log('[smoke] 真实 Vue → Vite proxy → Spring Boot → MySQL 8.4 企业端与消费者冒烟通过')
} catch (error) {
  primaryError = error
} finally {
  stopProcessTree(backend)
  if (schemaCreated) {
    try {
      dropIsolatedSchema()
      console.log(`[smoke] 已删除隔离 schema ${schema} 并撤销授权，残留 0`)
    } catch (cleanupError) {
      if (!primaryError) primaryError = cleanupError
      else console.error(cleanupError)
    }
  }
}

if (primaryError) throw primaryError
