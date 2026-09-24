/**
 * Phase 0 + Phase A / Slice 1 ~ Slice 5 真实浏览器冒烟：真实 MySQL 8.4 → Spring Boot → Vite proxy → Vue。
 *
 * 1. 在 MySQL 8.4 容器中新建隔离 schema（seafood_trace_phase0_demo_<随机后缀>），绝不触碰 seafood_trace；
 * 2. 以进程级随机 pepper 启动 Spring Boot（Flyway 在空 schema 上执行 V1~V11）；
 * 3. 通过 SQL 准备基础资料（两个来源组织、角色、账号、产品），通过真实 HTTP API（CSRF + Session）创建与提交来源批次、激活公开码；
 *    Phase 0 没有冻结 / 召回 / 关闭接口，FROZEN、RECALLED、CLOSED 状态在隔离 schema 中直接写入以验证展示与筛选；
 * 4. Playwright 在不使用任何拦截或替身的情况下走完整企业端与消费者路径，并在浏览器中真实新建、激活来源批次 SRC-2026-001；
 * 5. 浏览器结束后直接查询 MySQL，确认 SRC-2026-001 为 ACTIVE/NORMAL 且恰好一条 SOURCE 事件；
 * 6. Slice 2：另建独立的来源 / 承运 / 加工三个组织、账号与场所，并以来源账号通过真实 API 创建并激活 1000kg 来源批次 B0；
 *    Playwright 以三个隔离浏览器上下文走完 Transfer + Shipment 全链，结束后查询 MySQL 验证全部验收事实；
 * 7. Slice 3：加工企业在浏览器中完成 B0 → PROCESS → B1 → SPLIT → B2 600kg / B3 360kg；
 * 8. Slice 4：加工企业在本组织自有冷库对 B2、B3 各记录一次冷库入库与出库（先经真实 API 探测他组织冷库被 403 拒绝），
 *    结束后查询 MySQL 验证批次数量 / 责任组织 / 双状态 / 版本不变、每批恰好一条 IN + OUT、场所为加工企业启用冷库，
 *    且无批次 / 谱系 / 交接 / 运输副作用；
 * 9. Slice 5：零售企业（门店 STORE）加入；加工企业在浏览器中为 B2 / B3 创建 T2 / T3 并装入同一运输任务 S1，承运商发运 / 到达，
 *    零售企业接受后在批次详情把 B2 售 200 + 400、B3 售 360 至售罄；B2 首次销售后在同一会话内经真实 API 探测
 *    交接 / 批次操作 / 非门店 / 停用门店 / 他组织门店 / 超卖 / 人工 SALE 均被拒绝；结束后查询 MySQL 验证
 *    责任组织、售罄关闭、Sale 行与 SALE 事件一一对应、无超卖、无重复事件、探测零写入；
 * 10. 无论成功失败都停止后端、删除 schema 并撤销授权，验证残留为 0。
 *
 * 密码、pepper、Cookie 与 CSRF 凭据只在进程环境与内存中传递，从不打印。
 * 例外：SMOKE_KEEP=true 手工验收模式只准备 Slice 2 / Slice 5 基础资料，打印一次四个临时账号供人工在真实浏览器中操作，
 * 并保持 Spring Boot 与 Vite 运行，直到按 Ctrl+C 后清理；这些账号只存在于随后被删除的隔离 schema 中。
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

function seedMasterData(suffix, passwordA, passwordB, passwordQm) {
  mysql(`
    START TRANSACTION;
    INSERT INTO organization (org_no, name, org_type, credit_code, status)
      VALUES ('P0_SRC_A_${suffix}', '冒烟来源捕捞企业_${suffix}', 'SOURCE', 'SMOKE-CREDIT-${suffix}', 'ACTIVE');
    SET @orgA = LAST_INSERT_ID();
    INSERT INTO organization (org_no, name, org_type, credit_code, status)
      VALUES ('P0_SRC_B_${suffix}', '冒烟来源养殖企业_${suffix}', 'SOURCE', 'SMOKE-CREDIT-B-${suffix}', 'ACTIVE');
    SET @orgB = LAST_INSERT_ID();
    INSERT INTO role (role_code, name, scope_type, status) VALUES ('OPERATOR', '企业操作员', 'ORG_ONLY', 'ACTIVE');
    SET @role = LAST_INSERT_ID();
    -- PB1：质量管理员角色只存在于冒烟夹具（不进入 Flyway），负责风险冻结 / 解除冻结
    INSERT INTO role (role_code, name, scope_type, status) VALUES ('QUALITY_MANAGER', '质量管理员', 'ORG_ONLY', 'ACTIVE');
    SET @qm = LAST_INSERT_ID();
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgA, 'p0_src_a_${suffix}', '冒烟来源操作员', ${sqlText(springPbkdf2(passwordA))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgB, 'p0_src_b_${suffix}', '冒烟第二来源操作员', ${sqlText(springPbkdf2(passwordB))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgA, 'p0_src_a_qm_${suffix}', '冒烟来源质量管理员', ${sqlText(springPbkdf2(passwordQm))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @qm);
    INSERT INTO product (product_code, public_name, category, specification, source_type, base_unit_code, status)
      VALUES ('P0-YELLOW-${suffix}', 'Phase0冒烟冷冻大黄鱼', 'FISH', '500g/条', 'DOMESTIC_CAPTURE', 'kg', 'ACTIVE');
    INSERT INTO product (product_code, public_name, category, specification, source_type, base_unit_code, status)
      VALUES ('P0-SHRIMP-${suffix}', 'Phase0冒烟冷冻南美白虾', 'CRUSTACEAN', '1kg/袋', 'IMPORT', 'kg', 'ACTIVE');
    COMMIT;
  `, { database: schema })
  const ids = mysql(`SELECT
      (SELECT id FROM organization WHERE org_no = 'P0_SRC_A_${suffix}'),
      (SELECT id FROM organization WHERE org_no = 'P0_SRC_B_${suffix}'),
      (SELECT id FROM product WHERE product_code = 'P0-YELLOW-${suffix}'),
      (SELECT id FROM product WHERE product_code = 'P0-SHRIMP-${suffix}');`, { database: schema }).split('\t').map(Number)
  return { orgA: ids[0], orgB: ids[1], productFish: ids[2], productShrimp: ids[3] }
}

async function createBatch(session, request, submit) {
  const created = await session.write('POST', '/api/v1/batches', request, { 'Idempotency-Key': randomUUID() })
  if (!submit) return created
  return session.write('POST', `/api/v1/batches/${created.id}/submit`, { version: created.version })
}

async function seedBusinessData(suffix, master, passwordA, passwordB, passwordQm) {
  const sessionA = new ApiSession()
  await sessionA.login(`p0_src_a_${suffix}`, passwordA)
  // 来源批次创建请求不携带 batchType 等服务端字段（服务端固定 SOURCE / DRAFT / NORMAL）
  const base = { unitCode: 'kg', originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场（冒烟夹具）' }

  const active = await createBatch(sessionA, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 1000, productionDate: '2026-09-01', captureDate: '2026-08-30', shelfLifeDays: 365 }, true)
  const draft = await createBatch(sessionA, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 250.5 }, false)
  const frozen = await createBatch(sessionA, { ...base, productId: master.productShrimp, quantity: 480, originType: 'IMPORT', originText: '进口原料（冒烟夹具）' }, true)
  const recalled = await createBatch(sessionA, { ...base, productId: master.productShrimp, externalBatchNo: 'P0-EXT-004', quantity: 60 }, true)
  const closed = await createBatch(sessionA, { ...base, productId: master.productFish, quantity: 12.345 }, true)
  const publicCode = await sessionA.write('POST', `/api/v1/batches/${active.id}/public-trace-code/activate`, undefined, { 'Idempotency-Key': randomUUID() })
  // Slice 6 只读回归：在批次仍为 ACTIVE + NORMAL 时正常激活，再由下方种子 SQL 置为 CLOSED + RECALLED（Phase A 无风险写入接口）
  const recalledCode = await sessionA.write('POST', `/api/v1/batches/${recalled.id}/public-trace-code/activate`, undefined, { 'Idempotency-Key': randomUUID() })

  const sessionB = new ApiSession()
  await sessionB.login(`p0_src_b_${suffix}`, passwordB)
  const foreign = await createBatch(sessionB, { ...base, productId: master.productFish, externalBatchNo: 'P0-EXT-001', quantity: 77 }, true)

  // PB1：风险冻结经真实接口由来源企业质量管理员执行（写入风险转换台账与审计）
  const sessionQm = new ApiSession()
  await sessionQm.login(`p0_src_a_qm_${suffix}`, passwordQm)
  await sessionQm.write('POST', `/api/v1/batches/${frozen.id}/risk/freeze`, { reason: 'Phase 0 冒烟夹具：模拟风险冻结' }, { 'Idempotency-Key': randomUUID() })
  const frozenLedger = mysql(`SELECT COUNT(*) FROM batch_risk_transition WHERE batch_id = ${frozen.id} AND from_status = 'NORMAL'
      AND to_status = 'FROZEN' AND source_type = 'MANUAL' AND org_id = ${master.orgA};`, { database: schema })
  if (frozenLedger !== '1') throw new Error(`Phase 0 冻结夹具未经 PB1 接口写入风险转换台账: ${frozenLedger}`)

  // 召回（PB5）与“关闭”夹具仍无对应业务接口：仅在隔离 schema 中直接写入以验证展示与筛选
  mysql(`
    UPDATE batch SET flow_status = 'CLOSED', risk_status = 'RECALLED' WHERE id = ${recalled.id};
    UPDATE batch SET flow_status = 'CLOSED' WHERE id = ${closed.id};
  `, { database: schema })

  return { active, draft, frozen, recalled, closed, foreign, publicTraceId: publicCode.publicId, recalledPublicTraceId: recalledCode.publicId }
}

function databaseEvidence(orgA) {
  return mysql(`SELECT id, trace_batch_no, IFNULL(external_batch_no, '-'), flow_status, risk_status, org_id = ${orgA}
    FROM batch ORDER BY id;`, { database: schema })
}

/** 浏览器新建的 SRC-2026-001：恰好一条批次，ACTIVE/NORMAL，属于登录组织，且恰好一条有效 SOURCE 事件。 */
function verifyBrowserCreatedSourceBatch(orgA) {
  const rows = mysql(`SELECT b.trace_batch_no, b.batch_type, b.flow_status, b.risk_status, b.org_id = ${orgA}, b.creation_org_id = ${orgA},
      b.unit_code, b.quantity,
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'SOURCE' AND e.status = 'SUBMITTED'),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.idempotency_key = CONCAT('SYS:SOURCE:BATCH:', b.id))
    FROM batch b WHERE b.external_batch_no = 'SRC-2026-001';`, { database: schema })
  const lines = rows ? rows.split('\n') : []
  if (lines.length !== 1) throw new Error(`期望恰好 1 条 SRC-2026-001 批次，实际 ${lines.length}`)
  const [traceBatchNo, batchType, flow, risk, ownedByA, createdByA, unit, quantity, sourceCount, serverKeyCount] = lines[0].split('\t')
  const ok = /^TB-[0-9A-Z]{26}$/.test(traceBatchNo) && batchType === 'SOURCE' && flow === 'ACTIVE' && risk === 'NORMAL'
    && ownedByA === '1' && createdByA === '1' && unit === 'kg' && Number(quantity) === 1000 && sourceCount === '1' && serverKeyCount === '1'
  if (!ok) throw new Error(`SRC-2026-001 数据库事实不符合预期: ${lines[0]}`)
  console.log(`[smoke] MySQL 确认浏览器新建来源批次: ${traceBatchNo} SOURCE ACTIVE/NORMAL 1000 kg，SOURCE 事件 ${sourceCount} 条（服务端幂等身份 ${serverKeyCount} 条）`)
}

/**
 * Slice 2 基础资料：独立的来源 / 承运 / 加工组织与 OPERATOR 账号，来源码头与加工厂两个场所。
 * 只准备基础资料，不预造任何交接或运输单据。
 */
function seedSlice2MasterData(suffix, passwords) {
  mysql(`
    START TRANSACTION;
    SET @role = (SELECT id FROM role WHERE role_code = 'OPERATOR');
    SET @qm = (SELECT id FROM role WHERE role_code = 'QUALITY_MANAGER');
    INSERT INTO organization (org_no, name, org_type, status) VALUES ('S2_SRC_${suffix}', 'Slice2来源捕捞企业_${suffix}', 'SOURCE', 'ACTIVE');
    SET @src = LAST_INSERT_ID();
    INSERT INTO organization (org_no, name, org_type, status) VALUES ('S2_CAR_${suffix}', 'Slice2冷链承运企业_${suffix}', 'CARRIER', 'ACTIVE');
    SET @car = LAST_INSERT_ID();
    INSERT INTO organization (org_no, name, org_type, status) VALUES ('S2_PRC_${suffix}', 'Slice2水产加工企业_${suffix}', 'PROCESSOR', 'ACTIVE');
    SET @prc = LAST_INSERT_ID();
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@src, 's2_src_${suffix}', 'Slice2来源操作员', ${sqlText(springPbkdf2(passwords.source))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@car, 's2_car_${suffix}', 'Slice2承运操作员', ${sqlText(springPbkdf2(passwords.carrier))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@prc, 's2_prc_${suffix}', 'Slice2加工操作员', ${sqlText(springPbkdf2(passwords.processor))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@prc, 's2_prc_qm_${suffix}', 'Slice2加工质量管理员', ${sqlText(springPbkdf2(passwords.processorQm))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @qm);
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@src, 'S2-PORT', 'Slice2沈家门码头', 'PORT', 'ACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@prc, 'S2-FACTORY', 'Slice2舟山加工厂', 'FACTORY', 'ACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@prc, 'S2-COLD', 'Slice2舟山自有冷库', 'COLD_STORE', 'ACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@src, 'S2-SRC-COLD', 'Slice2来源企业冷库', 'COLD_STORE', 'ACTIVE');
    COMMIT;
  `, { database: schema })
  const ids = mysql(`SELECT
      (SELECT id FROM organization WHERE org_no = 'S2_SRC_${suffix}'),
      (SELECT id FROM organization WHERE org_no = 'S2_CAR_${suffix}'),
      (SELECT id FROM organization WHERE org_no = 'S2_PRC_${suffix}'),
      (SELECT s.id FROM site s JOIN organization o ON o.id = s.org_id WHERE o.org_no = 'S2_PRC_${suffix}' AND s.site_no = 'S2-COLD'),
      (SELECT s.id FROM site s JOIN organization o ON o.id = s.org_id WHERE o.org_no = 'S2_SRC_${suffix}' AND s.site_no = 'S2-SRC-COLD');`, { database: schema }).split('\t').map(Number)
  return {
    sourceOrgId: ids[0],
    carrierOrgId: ids[1],
    processorOrgId: ids[2],
    sourceOrgName: `Slice2来源捕捞企业_${suffix}`,
    carrierOrgName: `Slice2冷链承运企业_${suffix}`,
    processorOrgName: `Slice2水产加工企业_${suffix}`,
    sourceSiteName: 'Slice2沈家门码头',
    processorSiteName: 'Slice2舟山加工厂',
    coldStoreSiteId: ids[3],
    coldStoreName: 'Slice2舟山自有冷库',
    sourceColdStoreSiteId: ids[4],
    usernames: { source: `s2_src_${suffix}`, carrier: `s2_car_${suffix}`, processor: `s2_prc_${suffix}`, processorQm: `s2_prc_qm_${suffix}` }
  }
}

/** B0：来源企业通过 Slice 1 真实 API 创建并激活的 1000kg ACTIVE/NORMAL 来源批次。 */
async function seedSlice2SourceBatch(master, productId, password) {
  const session = new ApiSession()
  await session.login(master.usernames.source, password)
  return createBatch(session, {
    productId, externalBatchNo: 'S2-B0', quantity: 1000, unitCode: 'kg',
    originType: 'DOMESTIC_CAPTURE', originText: '东海舟山渔场（Slice 2 验收）', captureDate: '2026-09-20', shelfLifeDays: 365
  }, true)
}

/** Slice 2 最终数据库事实：T0 ACCEPTED、S0 DELIVERED、B0 责任组织为加工企业且 ACTIVE/NORMAL 1000kg、TRANSPORT / ARRIVAL 各一条。 */
function verifySlice2Database(master, b0Id) {
  const row = mysql(`SELECT
      (SELECT COUNT(*) FROM transfer WHERE batch_id = ${b0Id} AND is_deleted = 0),
      (SELECT status FROM transfer WHERE batch_id = ${b0Id} AND is_deleted = 0 LIMIT 1),
      (SELECT s.status FROM shipment s JOIN transfer t ON t.shipment_id = s.id WHERE t.batch_id = ${b0Id} LIMIT 1),
      (SELECT s.loaded_at <= s.unloaded_at FROM shipment s JOIN transfer t ON t.shipment_id = s.id WHERE t.batch_id = ${b0Id} LIMIT 1),
      (SELECT s.carrier_org_id = ${master.carrierOrgId} AND s.sender_org_id = ${master.sourceOrgId} AND s.receiver_org_id = ${master.processorOrgId}
         FROM shipment s JOIN transfer t ON t.shipment_id = s.id WHERE t.batch_id = ${b0Id} LIMIT 1),
      b.org_id = ${master.processorOrgId}, b.flow_status, b.risk_status, b.quantity,
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'TRANSPORT'),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'ARRIVAL'),
      (SELECT COUNT(*) FROM trace_event e JOIN transfer t ON t.batch_id = e.batch_id
         WHERE e.batch_id = b.id AND e.event_type IN ('TRANSPORT', 'ARRIVAL')
           AND JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.sourceObjectType')) = 'SHIPMENT'
           AND CAST(JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.shipmentId')) AS UNSIGNED) = t.shipment_id),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.idempotency_key LIKE 'TRANSFER_ARRIVAL_%'),
      (SELECT COUNT(*) FROM trace_event e JOIN transfer t ON t.batch_id = e.batch_id
         WHERE e.batch_id = b.id AND e.event_type = 'ARRIVAL' AND e.recorded_at <= t.decision_recorded_at),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.org_id = ${master.carrierOrgId})
    FROM batch b WHERE b.id = ${b0Id};`, { database: schema })
  const [transferCount, transferStatus, shipmentStatus, timeOrder, parties, ownedByProcessor, flow, risk, quantity,
    transportCount, arrivalCount, fromShipmentCount, legacyArrivalCount, arrivalBeforeAccept, carrierOrgEvents] = row.split('\t')
  const facts = {
    'T0 = ACCEPTED': transferCount === '1' && transferStatus === 'ACCEPTED',
    'S0 = DELIVERED': shipmentStatus === 'DELIVERED',
    'S0 loaded_at <= unloaded_at': timeOrder === '1',
    'S0 sender/carrier/receiver = SOURCE/CARRIER/PROCESSOR': parties === '1',
    'B0 responsibleOrgId = PROCESSOR': ownedByProcessor === '1',
    'B0 = ACTIVE + NORMAL': flow === 'ACTIVE' && risk === 'NORMAL',
    'B0 quantity = 1000kg': Number(quantity) === 1000,
    'TRANSPORT = 1': transportCount === '1',
    'ARRIVAL = 1': arrivalCount === '1',
    'TRANSPORT / ARRIVAL 来源为该运输任务': fromShipmentCount === '2',
    'ACCEPT 未生成 ARRIVAL（无 TRANSFER_ARRIVAL_* 事件）': legacyArrivalCount === '0',
    'ARRIVAL 登记早于 ACCEPT 决定': arrivalBeforeAccept === '1',
    '承运商从未成为事件记录 / 责任组织': carrierOrgEvents === '0'
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) throw new Error(`Slice 2 数据库事实不符合预期: ${row}`)
  console.log('[smoke] MySQL trace_event（B0）:')
  console.log(mysql(`SELECT id, event_type, org_id, site_id, occurred_at, idempotency_key FROM trace_event WHERE batch_id = ${b0Id} ORDER BY occurred_at, id;`, { database: schema })
    .split('\n').map((line) => `  ${line}`).join('\n'))
}

/**
 * Slice 3 最终数据库事实：B0 → PROCESS → B1（960 + LOSS 30 + SAMPLE 10）→ SPLIT → B2 600 / B3 360。
 * 只由真实浏览器操作产生：INPUT 全量消耗并 CLOSED、OUTPUT ACTIVE、谱系边、PROCESS 事件恰好一条、SPLIT 无事件。
 */
function verifySlice3Database(master, b0Id) {
  const q = (sql) => mysql(sql, { database: schema })
  const [op1, b0Flow, b0Risk, b0Qty] = q(`SELECT IFNULL(consumed_by_operation_id, 0), flow_status, risk_status, quantity FROM batch WHERE id = ${b0Id};`).split('\t')
  const [op1Type, op1Status, op1Org] = q(`SELECT operation_type, status, org_id FROM batch_operation WHERE id = ${op1};`).split('\t')
  const op1Sums = q(`SELECT role, SUM(quantity) FROM batch_operation_item WHERE operation_id = ${op1} AND is_deleted = 0 GROUP BY role ORDER BY role;`)
  const b1 = q(`SELECT id FROM batch WHERE produced_by_operation_id = ${op1} AND is_deleted = 0;`).split('\n').filter(Boolean)
  const b1Id = Number(b1[0] || 0)
  const [op2, b1Flow, b1Risk, b1Qty, b1Type, b1Org] = b1Id
    ? q(`SELECT IFNULL(consumed_by_operation_id, 0), flow_status, risk_status, quantity, batch_type, org_id = ${master.processorOrgId} FROM batch WHERE id = ${b1Id};`).split('\t')
    : ['0', '', '', '0', '', '0']
  const [op2Type, op2Status] = Number(op2) ? q(`SELECT operation_type, status FROM batch_operation WHERE id = ${op2};`).split('\t') : ['', '']
  const children = Number(op2)
    ? q(`SELECT id, quantity, flow_status, risk_status, batch_type FROM batch WHERE produced_by_operation_id = ${op2} AND is_deleted = 0 ORDER BY quantity DESC;`).split('\n').filter(Boolean).map((l) => l.split('\t'))
    : []
  const childIds = children.map((c) => c[0])
  const allIds = [b0Id, b1Id, ...childIds].filter(Boolean).join(',')
  const relations = q(`SELECT parent_batch_id, child_batch_id, relation_type FROM batch_relation WHERE parent_batch_id IN (${allIds}) ORDER BY id;`)
  const events = q(`SELECT
      (SELECT COUNT(*) FROM trace_event WHERE batch_id = ${b1Id || 0} AND event_type = 'PROCESS'),
      (SELECT COUNT(*) FROM trace_event WHERE batch_id = ${b1Id || 0} AND event_type = 'PROCESS' AND org_id = ${master.processorOrgId}
         AND idempotency_key = CONCAT('SYS:PROCESS:OPERATION:', ${op1}, ':BATCH:', ${b1Id || 0})),
      (SELECT COUNT(*) FROM trace_event WHERE batch_id IN (${childIds.join(',') || 0})),
      (SELECT COUNT(*) FROM trace_event WHERE batch_id IN (${allIds}) AND event_type IN ('PACK', 'FREEZE')),
      (SELECT COUNT(*) FROM trace_event WHERE batch_id = ${b0Id} AND event_type = 'PROCESS');`).split('\t')
  const facts = {
    'B0 = CLOSED + NORMAL，1000kg，consumed_by = OP1': Number(op1) > 0 && b0Flow === 'CLOSED' && b0Risk === 'NORMAL' && Number(b0Qty) === 1000,
    'OP1 = PROCESS SUBMITTED（加工企业）': op1Type === 'PROCESS' && op1Status === 'SUBMITTED' && Number(op1Org) === master.processorOrgId,
    'OP1 物料平衡 1000 = 960 + 30 + 10': op1Sums === 'INPUT\t1000.000\nLOSS\t30.000\nOUTPUT\t960.000\nSAMPLE\t10.000',
    'B1 = 960kg PROCESSING，加工企业负责': b1.length === 1 && Number(b1Qty) === 960 && b1Type === 'PROCESSING' && b1Org === '1',
    'B1 = CLOSED + NORMAL，consumed_by = OP2': Number(op2) > 0 && b1Flow === 'CLOSED' && b1Risk === 'NORMAL',
    'OP2 = SPLIT SUBMITTED': op2Type === 'SPLIT' && op2Status === 'SUBMITTED',
    'B2 = 600kg ACTIVE + NORMAL，B3 = 360kg ACTIVE + NORMAL（类型继承 PROCESSING）': children.length === 2
      && Number(children[0][1]) === 600 && Number(children[1][1]) === 360
      && children.every((c) => c[2] === 'ACTIVE' && c[3] === 'NORMAL' && c[4] === 'PROCESSING'),
    '谱系：B0→B1 TRANSFORM，B1→B2 / B1→B3 SPLIT': relations === [
      `${b0Id}\t${b1Id}\tTRANSFORM`, `${b1Id}\t${childIds[0]}\tSPLIT`, `${b1Id}\t${childIds[1]}\tSPLIT`
    ].join('\n'),
    'PROCESS = 1（仅 B1，记录组织为加工企业，系统幂等键）': events[0] === '1' && events[1] === '1',
    'B0 无 PROCESS 事件': events[4] === '0',
    'SPLIT 不产生任何事件（B2 / B3 事件 = 0）': events[2] === '0',
    'PACK = 0，FREEZE = 0（加工不推导速冻，拆分不声称包装）': events[3] === '0'
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) {
    throw new Error(`Slice 3 数据库事实不符合预期: op1=${op1} b1=${b1Id} op2=${op2} relations=${JSON.stringify(relations)} sums=${JSON.stringify(op1Sums)}`)
  }
  console.log('[smoke] MySQL batch（B0 → B1 → B2 / B3）:')
  console.log(q(`SELECT id, trace_batch_no, batch_type, quantity, flow_status, risk_status, produced_by_operation_id, consumed_by_operation_id FROM batch WHERE id IN (${allIds}) ORDER BY id;`)
    .split('\n').map((line) => `  ${line}`).join('\n'))
  return { b0Id, b1Id, b2Id: Number(childIds[0]), b3Id: Number(childIds[1]) }
}

/** Slice 2 三个组织范围内的批次 / 谱系 / 交接 / 运输数量，以及 B2 / B3 批次行与非仓储事件数量快照。 */
function slice4Snapshot(master, ids) {
  const q = (sql) => mysql(sql, { database: schema })
  const orgs = `${master.sourceOrgId}, ${master.carrierOrgId}, ${master.processorOrgId}`
  const [batches, relations, transfers, shipments, otherEvents] = q(`SELECT
      (SELECT COUNT(*) FROM batch WHERE creation_org_id IN (${orgs}) OR org_id IN (${orgs})),
      (SELECT COUNT(*) FROM batch_relation r JOIN batch b ON b.id = r.child_batch_id WHERE b.creation_org_id IN (${orgs}) OR b.org_id IN (${orgs})),
      (SELECT COUNT(*) FROM transfer WHERE sender_org_id IN (${orgs}) OR receiver_org_id IN (${orgs})),
      (SELECT COUNT(*) FROM shipment WHERE sender_org_id IN (${orgs}) OR receiver_org_id IN (${orgs}) OR carrier_org_id IN (${orgs})),
      (SELECT COUNT(*) FROM trace_event WHERE event_type NOT IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT'));`).split('\t')
  const rows = q(`SELECT id, quantity, org_id, flow_status, risk_status, version FROM batch WHERE id IN (${ids.b2Id}, ${ids.b3Id}) ORDER BY id;`)
  return { batches, relations, transfers, shipments, otherEvents, rows }
}

/** 真实 API 越权探测：加工企业引用来源企业（他组织）的冷库记录入库必须 403，且不得落库。 */
async function probeForeignColdStore(master, ids, password) {
  const session = new ApiSession()
  await session.login(master.usernames.processor, password)
  try {
    await session.write('POST', `/api/v1/batches/${ids.b2Id}/events`, {
      eventType: 'WAREHOUSE_IN', siteId: master.sourceColdStoreSiteId, occurredAt: new Date().toISOString(),
      dataSource: 'MANUAL', summary: '越权引用他组织冷库（冒烟探测）'
    }, { 'Idempotency-Key': randomUUID() })
  } catch (error) {
    if (/-> 403 ORG_SCOPE_DENIED$/.test(error.message)) {
      console.log('[smoke] API ✔ 加工企业引用来源企业冷库记录入库被拒绝（403 ORG_SCOPE_DENIED）')
      return
    }
    throw error
  }
  throw new Error('他组织冷库入库探测未被拒绝')
}

/**
 * Slice 4 最终数据库事实：B2 / B3 各恰好一条 WAREHOUSE_IN + WAREHOUSE_OUT，场所为加工企业启用的 COLD_STORE，MANUAL；
 * 批次数量 / 责任组织 / 双状态 / 版本不变，无批次 / 谱系 / 交接 / 运输及其他事件副作用。
 * snapshot 为空（SMOKE_KEEP 手工模式）时跳过与浏览器操作前快照的比较，只校验绝对事实。
 */
function verifySlice4Database(master, ids, snapshot) {
  const q = (sql) => mysql(sql, { database: schema })
  const perBatch = (id) => q(`SELECT
      b.quantity, b.org_id = ${master.processorOrgId}, b.flow_status, b.risk_status,
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'WAREHOUSE_IN'),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'WAREHOUSE_OUT'),
      (SELECT COUNT(*) FROM trace_event e JOIN site s ON s.id = e.site_id
         WHERE e.batch_id = b.id AND e.event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT')
           AND e.site_id = ${master.coldStoreSiteId} AND s.org_id = ${master.processorOrgId} AND s.site_type = 'COLD_STORE' AND s.status = 'ACTIVE'
           AND e.org_id = ${master.processorOrgId} AND e.data_source = 'MANUAL' AND e.status = 'SUBMITTED' AND e.details_json IS NULL)
    FROM batch b WHERE b.id = ${id};`).split('\t')
  const [b2Qty, b2Owned, b2Flow, b2Risk, b2In, b2Out, b2Valid] = perBatch(ids.b2Id)
  const [b3Qty, b3Owned, b3Flow, b3Risk, b3In, b3Out, b3Valid] = perBatch(ids.b3Id)
  const [allWarehouse, foreignSiteEvents] = q(`SELECT
      (SELECT COUNT(*) FROM trace_event WHERE event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT')),
      (SELECT COUNT(*) FROM trace_event WHERE site_id = ${master.sourceColdStoreSiteId});`).split('\t')
  const after = slice4Snapshot(master, ids)
  const facts = {
    'B2 = 600kg，加工企业负责，ACTIVE + NORMAL': Number(b2Qty) === 600 && b2Owned === '1' && b2Flow === 'ACTIVE' && b2Risk === 'NORMAL',
    'B3 = 360kg，加工企业负责，ACTIVE + NORMAL': Number(b3Qty) === 360 && b3Owned === '1' && b3Flow === 'ACTIVE' && b3Risk === 'NORMAL',
    'B2 WAREHOUSE_IN = 1，WAREHOUSE_OUT = 1': b2In === '1' && b2Out === '1',
    'B3 WAREHOUSE_IN = 1，WAREHOUSE_OUT = 1': b3In === '1' && b3Out === '1',
    '全部仓储事件场所 = 加工企业启用 COLD_STORE，记录组织 = 加工企业，MANUAL，无 details': b2Valid === '2' && b3Valid === '2',
    '全库仓储事件恰好 4 条（B0 / B1 / 越权探测均未写入）': allWarehouse === '4' && foreignSiteEvents === '0',
    'Batch 数量 = 4（B0 / B1 / B2 / B3，无新批次）': after.batches === '4',
    'BatchRelation 数量 = 3（无新谱系）': after.relations === '3',
    'Transfer 数量 = 1（仅 T0）': after.transfers === '1',
    'Shipment 数量 = 1（仅 S0）': after.shipments === '1'
  }
  if (snapshot) {
    Object.assign(facts, {
      'B2 / B3 批次行（数量 / 责任组织 / 状态 / 版本）与入库前完全一致': after.rows === snapshot.rows,
      '批次 / 谱系 / 交接 / 运输数量与入库前一致': after.batches === snapshot.batches && after.relations === snapshot.relations
        && after.transfers === snapshot.transfers && after.shipments === snapshot.shipments,
      '非仓储追溯事件数量不变': after.otherEvents === snapshot.otherEvents
    })
  } else {
    console.log('[smoke] SMOKE_KEEP 模式无入库前快照：跳过版本与快照比较')
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) {
    throw new Error(`Slice 4 数据库事实不符合预期: b2=${ids.b2Id} b3=${ids.b3Id} snapshot=${JSON.stringify(snapshot)} after=${JSON.stringify(after)}`)
  }
  console.log('[smoke] MySQL trace_event（B2 / B3 仓储事件）:')
  console.log(q(`SELECT id, batch_id, event_type, org_id, site_id, data_source, status, occurred_at FROM trace_event
      WHERE batch_id IN (${ids.b2Id}, ${ids.b3Id}) ORDER BY batch_id, occurred_at, id;`)
    .split('\n').map((line) => `  ${line}`).join('\n'))
}

/**
 * Slice 5 基础资料：零售企业（RETAILER）与 OPERATOR 账号、启用门店 STORE（运输目的地与销售场所）、
 * 用于拒绝探测的停用门店与配送中心，以及加工企业自有门店（他组织门店探测）。只准备基础资料，不预造任何单据。
 */
function seedSlice5MasterData(suffix, password, slice2, qmPassword) {
  mysql(`
    START TRANSACTION;
    SET @role = (SELECT id FROM role WHERE role_code = 'OPERATOR');
    SET @qm = (SELECT id FROM role WHERE role_code = 'QUALITY_MANAGER');
    INSERT INTO organization (org_no, name, org_type, status) VALUES ('S5_RET_${suffix}', 'Slice5海港生鲜零售_${suffix}', 'RETAILER', 'ACTIVE');
    SET @ret = LAST_INSERT_ID();
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@ret, 's5_ret_${suffix}', 'Slice5零售操作员', ${sqlText(springPbkdf2(password))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@ret, 's5_ret_qm_${suffix}', 'Slice5零售质量管理员', ${sqlText(springPbkdf2(qmPassword))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @qm);
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@ret, 'S5-STORE', 'Slice5海港一号门店', 'STORE', 'ACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@ret, 'S5-STORE-OFF', 'Slice5已停用旧店', 'STORE', 'INACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (@ret, 'S5-DC', 'Slice5配送中心', 'LOGISTICS_HUB', 'ACTIVE');
    INSERT INTO site (org_id, site_no, name, site_type, status) VALUES (${slice2.processorOrgId}, 'S2-PRC-STORE', 'Slice2加工企业直营店', 'STORE', 'ACTIVE');
    COMMIT;
  `, { database: schema })
  const ids = mysql(`SELECT
      (SELECT id FROM organization WHERE org_no = 'S5_RET_${suffix}'),
      (SELECT s.id FROM site s JOIN organization o ON o.id = s.org_id WHERE o.org_no = 'S5_RET_${suffix}' AND s.site_no = 'S5-STORE'),
      (SELECT s.id FROM site s JOIN organization o ON o.id = s.org_id WHERE o.org_no = 'S5_RET_${suffix}' AND s.site_no = 'S5-STORE-OFF'),
      (SELECT s.id FROM site s JOIN organization o ON o.id = s.org_id WHERE o.org_no = 'S5_RET_${suffix}' AND s.site_no = 'S5-DC'),
      (SELECT id FROM site WHERE org_id = ${slice2.processorOrgId} AND site_no = 'S2-PRC-STORE');`, { database: schema }).split('\t').map(Number)
  return {
    retailerOrgId: ids[0],
    retailerOrgName: `Slice5海港生鲜零售_${suffix}`,
    storeSiteId: ids[1],
    storeName: 'Slice5海港一号门店',
    inactiveStoreId: ids[2],
    hubSiteId: ids[3],
    processorStoreSiteId: ids[4],
    username: `s5_ret_${suffix}`,
    qmUsername: `s5_ret_qm_${suffix}`
  }
}

/**
 * Slice 5 最终数据库事实：T2 / T3 同一运输任务 S1 且 ACCEPTED；B2 / B3 责任组织为零售企业、售罄 CLOSED、声明数量不变；
 * Sale 行（B2 = 200 + 400，B3 = 360）全部在零售门店、由零售企业提交；SALE 事件与 Sale 一一对应（系统键 + 来源对象）；
 * 派生剩余量为 0、无超卖、无重复事件；首次销售后的探测（交接 / 批次操作 / 非法门店 / 超卖 / 人工 SALE）零写入。
 */
function verifySlice5Database(slice2, retail, ids) {
  const q = (sql) => mysql(sql, { database: schema })
  const perBatch = (id) => q(`SELECT
      b.org_id = ${retail.retailerOrgId}, b.flow_status, b.risk_status, b.quantity,
      b.first_sale_id = (SELECT MIN(s.id) FROM sale s WHERE s.batch_id = b.id),
      b.quantity
        - (SELECT COALESCE(SUM(i.quantity), 0) FROM batch_operation_item i JOIN batch_operation o ON o.id = i.operation_id
            WHERE i.batch_id = b.id AND i.role = 'INPUT' AND i.is_deleted = 0 AND o.status = 'SUBMITTED' AND o.is_deleted = 0)
        - (SELECT COALESCE(SUM(s.quantity), 0) FROM sale s WHERE s.batch_id = b.id AND s.status = 'SUBMITTED'),
      (SELECT COALESCE(GROUP_CONCAT(s.quantity ORDER BY s.id SEPARATOR ','), '') FROM sale s WHERE s.batch_id = b.id),
      (SELECT COUNT(*) FROM sale s WHERE s.batch_id = b.id AND s.status = 'SUBMITTED' AND s.org_id = ${retail.retailerOrgId} AND s.site_id = ${retail.storeSiteId}),
      (SELECT COUNT(*) FROM trace_event e WHERE e.batch_id = b.id AND e.event_type = 'SALE'),
      (SELECT COUNT(*) FROM trace_event e JOIN sale s ON e.idempotency_key = CONCAT('SYS:SALE:SALE:', s.id)
         WHERE e.batch_id = b.id AND s.batch_id = b.id AND e.event_type = 'SALE' AND e.status = 'SUBMITTED'
           AND e.org_id = s.org_id AND e.site_id = s.site_id AND e.occurred_at = s.occurred_at
           AND JSON_UNQUOTE(JSON_EXTRACT(e.details_json, '$.sourceObjectType')) = 'SALE'
           AND CAST(JSON_EXTRACT(e.details_json, '$.sourceObjectId') AS UNSIGNED) = s.id),
      (SELECT COUNT(*) FROM transfer t WHERE t.batch_id = b.id AND t.is_deleted = 0 AND t.receiver_org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM transfer t WHERE t.batch_id = b.id AND t.status = 'ACCEPTED' AND t.receiver_org_id = ${retail.retailerOrgId}
         AND t.sender_org_id = ${slice2.processorOrgId})
    FROM batch b WHERE b.id = ${id};`).split('\t')
  const [b2Owned, b2Flow, b2Risk, b2Qty, b2First, b2Remaining, b2Sales, b2SalesAtStore, b2Events, b2BackedEvents, b2Transfers, b2Accepted] = perBatch(ids.b2Id)
  const [b3Owned, b3Flow, b3Risk, b3Qty, b3First, b3Remaining, b3Sales, b3SalesAtStore, b3Events, b3BackedEvents, b3Transfers, b3Accepted] = perBatch(ids.b3Id)
  const [shipments, sameShipment, shipmentOk, totalSales, totalSaleEvents, retailerOtherEvents, retailerOps, batchCount, relationCount, oversold, retailerTransfers] = q(`SELECT
      (SELECT COUNT(DISTINCT t.shipment_id) FROM transfer t WHERE t.batch_id IN (${ids.b2Id}, ${ids.b3Id}) AND t.receiver_org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM transfer t2 JOIN transfer t3 ON t2.shipment_id = t3.shipment_id
         WHERE t2.batch_id = ${ids.b2Id} AND t3.batch_id = ${ids.b3Id} AND t2.receiver_org_id = ${retail.retailerOrgId} AND t3.receiver_org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM shipment s JOIN transfer t ON t.shipment_id = s.id
         WHERE t.batch_id = ${ids.b2Id} AND t.receiver_org_id = ${retail.retailerOrgId} AND s.status = 'DELIVERED'
           AND s.sender_org_id = ${slice2.processorOrgId} AND s.receiver_org_id = ${retail.retailerOrgId} AND s.carrier_org_id = ${slice2.carrierOrgId}
           AND s.destination_site_id = ${retail.storeSiteId}),
      (SELECT COUNT(*) FROM sale),
      (SELECT COUNT(*) FROM trace_event WHERE event_type = 'SALE'),
      (SELECT COUNT(*) FROM trace_event WHERE org_id = ${retail.retailerOrgId} AND event_type <> 'SALE'),
      (SELECT COUNT(*) FROM batch_operation WHERE org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM batch WHERE creation_org_id IN (${slice2.sourceOrgId}, ${slice2.processorOrgId}, ${retail.retailerOrgId})),
      (SELECT COUNT(*) FROM batch_relation r JOIN batch b ON b.id = r.child_batch_id WHERE b.creation_org_id IN (${slice2.sourceOrgId}, ${slice2.processorOrgId})),
      (SELECT COUNT(*) FROM batch b WHERE (SELECT COALESCE(SUM(s.quantity), 0) FROM sale s WHERE s.batch_id = b.id) > b.quantity),
      (SELECT COUNT(*) FROM transfer WHERE sender_org_id = ${retail.retailerOrgId});`).split('\t')
  const facts = {
    'T2 / T3 装入同一运输任务 S1（DELIVERED，加工 → 零售，独立承运商，目的地为零售门店）': shipments === '1' && sameShipment === '1' && shipmentOk === '1',
    'T2 / T3 = ACCEPTED，每批恰好一张到零售企业的交接': b2Transfers === '1' && b3Transfers === '1' && b2Accepted === '1' && b3Accepted === '1',
    '首次销售后的交接探测零写入（零售企业未发出任何交接）': retailerTransfers === '0',
    'B2 / B3 责任组织 = 零售企业（Sale 不改变责任组织）': b2Owned === '1' && b3Owned === '1',
    'B2 / B3 = CLOSED + NORMAL，声明数量 600 / 360 不变': b2Flow === 'CLOSED' && b2Risk === 'NORMAL' && Number(b2Qty) === 600
      && b3Flow === 'CLOSED' && b3Risk === 'NORMAL' && Number(b3Qty) === 360,
    'B2 / B3 派生剩余量 = 0': Number(b2Remaining) === 0 && Number(b3Remaining) === 0,
    'B2 / B3 first_sale_id = 各自第一笔 Sale': b2First === '1' && b3First === '1',
    'B2 Sale = 200 + 400，B3 Sale = 360（SUBMITTED，零售组织，零售门店）': b2Sales === '200.000,400.000' && b3Sales === '360.000'
      && b2SalesAtStore === '2' && b3SalesAtStore === '1',
    'SALE 事件 B2 = 2、B3 = 1，且每条都由对应 Sale 支撑（系统键 / 组织 / 门店 / 时间 / 来源对象）': b2Events === '2' && b3Events === '1'
      && b2BackedEvents === '2' && b3BackedEvents === '1',
    '全库 Sale = 3、SALE 事件 = 3（无重复事件，超卖 / 非法门店 / 重放探测未写入）': totalSales === '3' && totalSaleEvents === '3',
    '无超卖（任何批次已售 ≤ 声明数量）': oversold === '0',
    '零售企业无批次操作、除 SALE 外无其他事件（人工 SALE 探测未写入）': retailerOps === '0' && retailerOtherEvents === '0',
    'Batch = 4（B0 / B1 / B2 / B3，无新批次），BatchRelation = 3': batchCount === '4' && relationCount === '3'
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) {
    throw new Error(`Slice 5 数据库事实不符合预期: b2=${perBatch(ids.b2Id)} b3=${perBatch(ids.b3Id)} shipments=${shipments}/${sameShipment}/${shipmentOk} sales=${totalSales} events=${totalSaleEvents} retailerTransfers=${retailerTransfers}`)
  }
  console.log('[smoke] MySQL sale（B2 / B3）:')
  console.log(q(`SELECT id, batch_id, org_id, site_id, quantity, status, occurred_at FROM sale ORDER BY id;`)
    .split('\n').map((line) => `  ${line}`).join('\n'))
  console.log('[smoke] MySQL batch（B2 / B3）:')
  console.log(q(`SELECT id, trace_batch_no, org_id, quantity, flow_status, risk_status, first_sale_id, version FROM batch WHERE id IN (${ids.b2Id}, ${ids.b3Id}) ORDER BY id;`)
    .split('\n').map((line) => `  ${line}`).join('\n'))
}

function startViteDevServer() {
  const npmCommand = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'npm'
  const npmArgs = process.platform === 'win32' ? ['/d', '/s', '/c', 'npm.cmd run dev'] : ['run', 'dev']
  return spawn(npmCommand, npmArgs, {
    cwd: webDir,
    env: { ...process.env, VITE_BACKEND_PROXY_TARGET: backendOrigin },
    stdio: 'inherit'
  })
}

function waitForInterrupt() {
  return new Promise((resolvePromise) => {
    process.once('SIGINT', resolvePromise)
    process.once('SIGTERM', resolvePromise)
  })
}

/**
 * 异步运行 Playwright：必须保持 Node 事件循环持续读取 Spring Boot 的 stdout/stderr 管道。
 * 若使用 spawnSync 阻塞事件循环，后端日志会填满管道缓冲区，后端在写日志时被阻塞，请求随之挂起。
 */
function runBrowserSmoke(env, spec = 'tests/e2e/real-smoke.spec.ts') {
  const npmCommand = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'npm'
  const npmArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', `npm.cmd run test:e2e -- ${spec} --project=desktop --workers=1`]
    : ['run', 'test:e2e', '--', spec, '--project=desktop', '--workers=1']
  return new Promise((resolvePromise, reject) => {
    const child = spawn(npmCommand, npmArgs, {
      cwd: webDir,
      windowsVerbatimArguments: process.platform === 'win32',
      env: { ...process.env, ...env, REAL_SMOKE: 'true', VITE_BACKEND_PROXY_TARGET: backendOrigin },
      stdio: 'inherit'
    })
    child.once('error', reject)
    child.once('exit', (code) => {
      if (code === 0) resolvePromise()
      else reject(new Error(`真实前后端浏览器冒烟失败（${spec}），退出码 ${code}`))
    })
  })
}

/**
 * Slice 5 / 6 公开追溯码事实：B2 / B3 各恰好一个 ACTIVE 码且持有组织为零售企业（交接不换码、不复制）；
 * 激活发生在该批次第一笔终端销售之前（契约 v1.1 §12 第 18 → 19 步）；售罄 CLOSED 不会自动停用已激活码；
 * 激活不产生任何追溯事件。
 */
function verifySlice5PublicCodes(retail, ids) {
  const q = (sql) => mysql(sql, { database: schema })
  const perBatch = (id) => q(`SELECT
      (SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ${id}),
      (SELECT COUNT(*) FROM public_trace_code WHERE batch_id = ${id} AND status = 'ACTIVE' AND is_deleted = 0 AND org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM public_trace_code p WHERE p.batch_id = ${id}
         AND p.activated_at <= (SELECT MIN(s.created_at) FROM sale s WHERE s.batch_id = ${id})),
      (SELECT flow_status FROM batch WHERE id = ${id}),
      (SELECT COUNT(*) FROM public_trace_code_idempotency WHERE batch_id = ${id} AND action = 'ACTIVATE' AND org_id = ${retail.retailerOrgId});`).split('\t')
  const [b2Codes, b2Active, b2BeforeSale, b2Flow, b2Idem] = perBatch(ids.b2Id)
  const [b3Codes, b3Active, b3BeforeSale, b3Flow, b3Idem] = perBatch(ids.b3Id)
  const [otherCodes, activationEvents] = q(`SELECT
      (SELECT COUNT(*) FROM public_trace_code WHERE batch_id IN (${ids.b0Id}, ${ids.b1Id})),
      (SELECT COUNT(*) FROM trace_event WHERE org_id = ${retail.retailerOrgId} AND event_type <> 'SALE');`).split('\t')
  const facts = {
    'B2 / B3 各恰好一个公开追溯码（一批一码）': b2Codes === '1' && b3Codes === '1',
    'B2 / B3 公开追溯码 ACTIVE 且持有组织为零售企业（售罄 CLOSED 不自动停用）': b2Active === '1' && b3Active === '1' && b2Flow === 'CLOSED' && b3Flow === 'CLOSED',
    '激活时间早于该批次第一笔终端销售（接受 → 激活 → 销售）': b2BeforeSale === '1' && b3BeforeSale === '1',
    '激活由零售企业以幂等键提交': Number(b2Idem) >= 1 && Number(b3Idem) >= 1,
    'B0 / B1 未激活公开追溯码（祖先不另外发码）': otherCodes === '0',
    '激活不产生追溯事件（零售企业只有 SALE 事件）': activationEvents === '0'
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) {
    throw new Error(`Slice 5 公开追溯码事实不符合预期: b2=${perBatch(ids.b2Id)} b3=${perBatch(ids.b3Id)} others=${otherCodes} events=${activationEvents}`)
  }
}

/**
 * Slice 5 业务时间先后（数据库中存储的业务事实，而非页面位置）：S1 发运 ≤ S1 到达 ≤ 各批次第一笔销售，同一批次销售按提交顺序不倒退；
 * 冷库与销售表单按秒登记业务时间（不向下取整到分钟、不编造毫秒），存储值没有亚秒部分。
 */
function verifySlice5Chronology(retail, ids) {
  const q = (sql) => mysql(sql, { database: schema })
  const perBatch = (id) => q(`SELECT
      (SELECT s.loaded_at <= s.unloaded_at FROM shipment s JOIN transfer t ON t.shipment_id = s.id
         WHERE t.batch_id = ${id} AND t.receiver_org_id = ${retail.retailerOrgId}),
      (SELECT s.unloaded_at <= (SELECT MIN(x.occurred_at) FROM sale x WHERE x.batch_id = ${id})
         FROM shipment s JOIN transfer t ON t.shipment_id = s.id WHERE t.batch_id = ${id} AND t.receiver_org_id = ${retail.retailerOrgId}),
      (SELECT COUNT(*) FROM sale a JOIN sale b ON a.batch_id = b.batch_id AND a.id < b.id AND a.occurred_at > b.occurred_at WHERE a.batch_id = ${id}),
      (SELECT COUNT(*) FROM sale WHERE batch_id = ${id} AND MICROSECOND(occurred_at) <> 0),
      (SELECT COUNT(*) FROM trace_event WHERE batch_id = ${id} AND event_type IN ('WAREHOUSE_IN', 'WAREHOUSE_OUT') AND MICROSECOND(occurred_at) <> 0),
      (SELECT CONCAT_WS(' <= ', DATE_FORMAT(s.loaded_at, '%H:%i:%s.%f'), DATE_FORMAT(s.unloaded_at, '%H:%i:%s.%f'),
              (SELECT GROUP_CONCAT(DATE_FORMAT(x.occurred_at, '%H:%i:%s.%f') ORDER BY x.id SEPARATOR ' <= ') FROM sale x WHERE x.batch_id = ${id}))
         FROM shipment s JOIN transfer t ON t.shipment_id = s.id WHERE t.batch_id = ${id} AND t.receiver_org_id = ${retail.retailerOrgId});`).split('\t')
  const [b2LoadedBeforeUnloaded, b2ArrivalBeforeSale, b2SaleInversions, b2SaleSubSecond, b2WarehouseSubSecond, b2Line] = perBatch(ids.b2Id)
  const [b3LoadedBeforeUnloaded, b3ArrivalBeforeSale, b3SaleInversions, b3SaleSubSecond, b3WarehouseSubSecond, b3Line] = perBatch(ids.b3Id)
  const facts = {
    'S1 发运（loaded_at）≤ S1 到达（unloaded_at）': b2LoadedBeforeUnloaded === '1' && b3LoadedBeforeUnloaded === '1',
    'S1 到达 ≤ B2 第一笔销售，S1 到达 ≤ B3 销售': b2ArrivalBeforeSale === '1' && b3ArrivalBeforeSale === '1',
    'B2 两笔销售业务时间按提交顺序不倒退': b2SaleInversions === '0' && b3SaleInversions === '0',
    '销售与冷库业务时间按秒登记（无亚秒部分，界面不编造毫秒）': b2SaleSubSecond === '0' && b3SaleSubSecond === '0'
      && b2WarehouseSubSecond === '0' && b3WarehouseSubSecond === '0'
  }
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  console.log(`[smoke] MySQL B2 业务时间（UTC）：S1 发运 <= S1 到达 <= 销售: ${b2Line}`)
  console.log(`[smoke] MySQL B3 业务时间（UTC）：S1 发运 <= S1 到达 <= 销售: ${b3Line}`)
  if (Object.values(facts).some((ok) => !ok)) {
    throw new Error(`Slice 5 业务时间先后不符合预期: b2=${perBatch(ids.b2Id)} b3=${perBatch(ids.b3Id)}`)
  }
}

/** 打印一组数据库事实并在任一失败时中止。 */
function assertFacts(title, facts, detail) {
  for (const [fact, ok] of Object.entries(facts)) console.log(`[smoke] MySQL ${ok ? '✔' : '✘'} ${fact}`)
  if (Object.values(facts).some((ok) => !ok)) throw new Error(`${title} 数据库事实不符合预期: ${detail}`)
}

function userIdOf(username) {
  return mysql(`SELECT id FROM app_user WHERE username = ${sqlText(username)};`, { database: schema })
}

/** PB1-A 前快照：全部业务表行数、B2 / B3 批次行与 Phase A 批次上的风险台账行数。 */
function pb1ProcessorSnapshot(ids) {
  const q = (sql) => mysql(sql, { database: schema })
  return {
    counts: q(`SELECT (SELECT COUNT(*) FROM batch), (SELECT COUNT(*) FROM batch_relation), (SELECT COUNT(*) FROM transfer),
        (SELECT COUNT(*) FROM transfer_idempotency), (SELECT COUNT(*) FROM shipment), (SELECT COUNT(*) FROM batch_operation),
        (SELECT COUNT(*) FROM trace_event), (SELECT COUNT(*) FROM public_trace_code), (SELECT COUNT(*) FROM public_trace_code_idempotency),
        (SELECT COUNT(*) FROM sale);`),
    b2: q(`SELECT quantity, org_id, flow_status, risk_status, first_sale_id IS NULL, consumed_by_operation_id IS NULL, version FROM batch WHERE id = ${ids.b2Id};`),
    b3: q(`SELECT quantity, org_id, flow_status, risk_status, version FROM batch WHERE id = ${ids.b3Id};`),
    ledger: q(`SELECT COUNT(*) FROM batch_risk_transition WHERE batch_id IN (${ids.b0Id}, ${ids.b1Id}, ${ids.b2Id}, ${ids.b3Id});`)
  }
}

/**
 * PB1-A 数据库事实：B2 恰好两行 MANUAL 风险台账（NORMAL → FROZEN → NORMAL，加工企业、加工质量管理员、流转快照 ACTIVE）与两条审计；
 * B2 数量 / 责任组织 / 双状态不变、版本只因冻结与解除 +2；B3 不受影响；冻结期间全部被阻断的写入零落库。
 */
function verifyPb1ProcessorDatabase(master, ids, before) {
  const q = (sql) => mysql(sql, { database: schema })
  const qmId = userIdOf(master.usernames.processorQm)
  const after = pb1ProcessorSnapshot(ids)
  const rows = q(`SELECT from_status, to_status, flow_status, source_type, org_id = ${master.processorOrgId}, actor_user_id = ${qmId},
      CHAR_LENGTH(request_hash) = 64, CHAR_LENGTH(TRIM(reason)) > 0
    FROM batch_risk_transition WHERE batch_id = ${ids.b2Id} ORDER BY id;`).split('\n')
  const [freezeAudits, releaseAudits] = q(`SELECT
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b2Id} AND action = 'RISK_FREEZE'
         AND actor_org_id = ${master.processorOrgId} AND actor_user_id = ${qmId}),
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b2Id} AND action = 'RISK_RELEASE'
         AND actor_org_id = ${master.processorOrgId} AND actor_user_id = ${qmId});`).split('\t')
  const [quantity, orgId, flow, risk, unsold, unconsumed, version] = after.b2.split('\t')
  const beforeVersion = Number(before.b2.split('\t')[6])
  assertFacts('PB1-A', {
    'B2 风险台账恰好 2 行：NORMAL → FROZEN → NORMAL，MANUAL，加工企业，加工质量管理员，流转快照 ACTIVE，原因与请求哈希完整': rows.length === 2
      && rows[0] === 'NORMAL\tFROZEN\tACTIVE\tMANUAL\t1\t1\t1\t1' && rows[1] === 'FROZEN\tNORMAL\tACTIVE\tMANUAL\t1\t1\t1\t1',
    'RISK_FREEZE / RISK_RELEASE 审计各 1 条（加工质量管理员、加工企业）': freezeAudits === '1' && releaseAudits === '1',
    'B2 = 600kg，加工企业负责，ACTIVE + NORMAL，未销售、未消耗': Number(quantity) === 600 && orgId === String(master.processorOrgId)
      && flow === 'ACTIVE' && risk === 'NORMAL' && unsold === '1' && unconsumed === '1',
    'B2 版本只因冻结与解除 +2': Number(version) === beforeVersion + 2,
    'B3 批次行完全不变（不向同源批次传播）': after.b3 === before.b3,
    '冻结期间被阻断的交接 / 批次操作 / 仓储 / 首次激活公开码零落库（全部业务表行数不变）': after.counts === before.counts,
    'Phase A 批次上只有 B2 的 2 行风险台账（B0 / B1 / B3 无）': before.ledger === '0' && after.ledger === '2'
  }, `rows=${JSON.stringify(rows)} audits=${freezeAudits}/${releaseAudits} before=${JSON.stringify(before)} after=${JSON.stringify(after)}`)
  console.log('[smoke] MySQL batch_risk_transition（B2，PB1-A）:')
  console.log(q(`SELECT id, batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id, occurred_at FROM batch_risk_transition
      WHERE batch_id = ${ids.b2Id} ORDER BY id;`).split('\n').map((line) => `  ${line}`).join('\n'))
}

/**
 * PB1-S 数据库事实：B3 在第一笔终端销售之前被零售质量管理员冻结并解除（两行 MANUAL 台账、两条审计，流转快照 ACTIVE）；
 * 冻结期间没有任何销售落库，B3 最终只有 Slice 5 的那一笔 360。
 */
function verifyPb1SaleCheckpoint(retail, ids) {
  const q = (sql) => mysql(sql, { database: schema })
  const qmId = userIdOf(retail.qmUsername)
  const rows = q(`SELECT from_status, to_status, flow_status, source_type, org_id = ${retail.retailerOrgId}, actor_user_id = ${qmId}
    FROM batch_risk_transition WHERE batch_id = ${ids.b3Id} ORDER BY id;`).split('\n')
  const [freezeAudits, releaseAudits, releasedBeforeSale, salesDuringFreeze, b3Sales] = q(`SELECT
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b3Id} AND action = 'RISK_FREEZE' AND actor_user_id = ${qmId}),
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b3Id} AND action = 'RISK_RELEASE' AND actor_user_id = ${qmId}),
      (SELECT MAX(created_at) FROM batch_risk_transition WHERE batch_id = ${ids.b3Id}) <= (SELECT MIN(created_at) FROM sale WHERE batch_id = ${ids.b3Id}),
      (SELECT COUNT(*) FROM sale s WHERE s.batch_id = ${ids.b3Id}
         AND s.created_at BETWEEN (SELECT MIN(created_at) FROM batch_risk_transition WHERE batch_id = ${ids.b3Id})
                              AND (SELECT MAX(created_at) FROM batch_risk_transition WHERE batch_id = ${ids.b3Id})),
      (SELECT COUNT(*) FROM sale WHERE batch_id = ${ids.b3Id});`).split('\t')
  assertFacts('PB1-S', {
    'B3 风险台账恰好 2 行：NORMAL → FROZEN → NORMAL，MANUAL，零售企业，零售质量管理员，流转快照 ACTIVE': rows.length === 2
      && rows[0] === 'NORMAL\tFROZEN\tACTIVE\tMANUAL\t1\t1' && rows[1] === 'FROZEN\tNORMAL\tACTIVE\tMANUAL\t1\t1',
    'RISK_FREEZE / RISK_RELEASE 审计各 1 条（零售质量管理员）': freezeAudits === '1' && releaseAudits === '1',
    '冻结与解除都发生在 B3 第一笔终端销售之前': releasedBeforeSale === '1',
    '冻结期间没有任何销售落库（真实 API 销售探测 422 零写入）': salesDuringFreeze === '0',
    'B3 最终只有 Slice 5 的一笔销售（360，售罄）': b3Sales === '1'
  }, `rows=${JSON.stringify(rows)} audits=${freezeAudits}/${releaseAudits} order=${releasedBeforeSale} during=${salesDuringFreeze} sales=${b3Sales}`)
}

/** PB1-B 前快照：B2 业务事实与版本、其他 Phase A 批次、B2 公开码、全局行数与 B2 风险台账行数。 */
function pb1ClosedSnapshot(ids) {
  return mysql(`SELECT
      (SELECT CONCAT_WS(':', quantity, org_id, flow_status, risk_status, first_sale_id, IFNULL(consumed_by_operation_id, '-')) FROM batch WHERE id = ${ids.b2Id}),
      (SELECT version FROM batch WHERE id = ${ids.b2Id}),
      (SELECT GROUP_CONCAT(CONCAT_WS(':', id, version, flow_status, risk_status, org_id) ORDER BY id) FROM batch WHERE id IN (${ids.b0Id}, ${ids.b1Id}, ${ids.b3Id})),
      (SELECT CONCAT_WS(':', id, status, version, org_id, public_id) FROM public_trace_code WHERE batch_id = ${ids.b2Id}),
      (SELECT COUNT(*) FROM sale), (SELECT COUNT(*) FROM trace_event), (SELECT COUNT(*) FROM transfer), (SELECT COUNT(*) FROM batch),
      (SELECT COUNT(*) FROM batch_risk_transition WHERE batch_id = ${ids.b2Id});`, { database: schema }).split('\t')
}

/**
 * PB1-B 数据库事实：已售罄 B2 由零售质量管理员冻结并解除（两行 MANUAL 台账，流转快照 CLOSED）；B2 始终 CLOSED、数量 / 责任组织 /
 * 首次销售不变、版本只 +2；公开追溯码、其他批次、销售与追溯事件完全不变（匿名消费者查询零写入）。
 */
function verifyPb1ClosedDatabase(retail, ids, before) {
  const q = (sql) => mysql(sql, { database: schema })
  const qmId = userIdOf(retail.qmUsername)
  const after = pb1ClosedSnapshot(ids)
  const [b2Before, versionBefore, othersBefore, codeBefore, salesBefore, eventsBefore, transfersBefore, batchesBefore, ledgerBefore] = before
  const [b2After, versionAfter, othersAfter, codeAfter, salesAfter, eventsAfter, transfersAfter, batchesAfter, ledgerAfter] = after
  const lastTwo = q(`SELECT from_status, to_status, flow_status, source_type, org_id = ${retail.retailerOrgId}, actor_user_id = ${qmId}
    FROM batch_risk_transition WHERE batch_id = ${ids.b2Id} ORDER BY id DESC LIMIT 2;`).split('\n').reverse()
  const [freezeAudits, releaseAudits] = q(`SELECT
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b2Id} AND action = 'RISK_FREEZE' AND actor_user_id = ${qmId}),
      (SELECT COUNT(*) FROM audit_log WHERE object_type = 'BATCH' AND object_id = ${ids.b2Id} AND action = 'RISK_RELEASE' AND actor_user_id = ${qmId});`).split('\t')
  assertFacts('PB1-B', {
    'B2 新增 2 行风险台账：NORMAL → FROZEN → NORMAL，流转快照 CLOSED，MANUAL，零售企业，零售质量管理员': Number(ledgerAfter) === Number(ledgerBefore) + 2
      && lastTwo[0] === 'NORMAL\tFROZEN\tCLOSED\tMANUAL\t1\t1' && lastTwo[1] === 'FROZEN\tNORMAL\tCLOSED\tMANUAL\t1\t1',
    'RISK_FREEZE / RISK_RELEASE 审计各 1 条（零售质量管理员）': freezeAudits === '1' && releaseAudits === '1',
    'B2 始终 CLOSED（从不重新打开），最终 CLOSED + NORMAL，数量 / 责任组织 / 首次销售不变': b2After === b2Before && b2After.includes(':CLOSED:NORMAL:'),
    'B2 版本只因冻结与解除 +2': Number(versionAfter) === Number(versionBefore) + 2,
    'B0 / B1 / B3 批次行完全不变': othersAfter === othersBefore,
    'B2 公开追溯码不变（ACTIVE，版本与持有组织不变）': codeAfter === codeBefore && codeAfter.includes(':ACTIVE:'),
    '销售 / 追溯事件 / 交接 / 批次行数不变（冻结不产生业务事实，消费者查询零写入）': salesAfter === salesBefore && eventsAfter === eventsBefore
      && transfersAfter === transfersBefore && batchesAfter === batchesBefore
  }, `before=${JSON.stringify(before)} after=${JSON.stringify(after)} lastTwo=${JSON.stringify(lastTwo)} audits=${freezeAudits}/${releaseAudits}`)
  console.log('[smoke] MySQL batch_risk_transition（Phase A 批次，PB1-A / PB1-S / PB1-B）:')
  console.log(q(`SELECT id, batch_id, org_id, flow_status, from_status, to_status, source_type, actor_user_id, occurred_at FROM batch_risk_transition
      WHERE batch_id IN (${ids.b0Id}, ${ids.b1Id}, ${ids.b2Id}, ${ids.b3Id}) ORDER BY id;`).split('\n').map((line) => `  ${line}`).join('\n'))
  console.log('[smoke] MySQL batch（Phase A 最终状态）:')
  console.log(q(`SELECT id, trace_batch_no, org_id, quantity, flow_status, risk_status, version FROM batch
      WHERE id IN (${ids.b0Id}, ${ids.b1Id}, ${ids.b2Id}, ${ids.b3Id}) ORDER BY id;`).split('\n').map((line) => `  ${line}`).join('\n'))
}

/** Slice 6 前后快照：消费者匿名查询与零售企业只读查看不得产生任何业务写入。 */
function slice6Snapshot(ids) {
  const all = [ids.b0Id, ids.b1Id, ids.b2Id, ids.b3Id].join(',')
  return mysql(`SELECT
      (SELECT GROUP_CONCAT(CONCAT(id, ':', version, ':', flow_status, ':', risk_status, ':', org_id) ORDER BY id) FROM batch WHERE id IN (${all})),
      (SELECT GROUP_CONCAT(CONCAT(id, ':', status, ':', version, ':', org_id) ORDER BY id) FROM public_trace_code WHERE batch_id IN (${all})),
      (SELECT COUNT(*) FROM trace_event), (SELECT COUNT(*) FROM audit_log), (SELECT COUNT(*) FROM batch),
      (SELECT COUNT(*) FROM batch_relation), (SELECT COUNT(*) FROM sale), (SELECT COUNT(*) FROM transfer),
      (SELECT COUNT(*) FROM shipment), (SELECT COUNT(*) FROM public_trace_code), (SELECT COUNT(*) FROM public_trace_code_idempotency),
      (SELECT COUNT(*) FROM batch_risk_transition);`, { database: schema })
}

let backend
let viteServer
let schemaCreated = false
const keepForManualAcceptance = process.env.SMOKE_KEEP === 'true'
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
  const passwordQm = `P0!${randomBytes(18).toString('base64url')}`
  const master = seedMasterData(suffix, passwordA, passwordB, passwordQm)
  const slice2Passwords = {
    source: `S2!${randomBytes(18).toString('base64url')}`,
    carrier: `S2!${randomBytes(18).toString('base64url')}`,
    processor: `S2!${randomBytes(18).toString('base64url')}`,
    processorQm: `PB1!${randomBytes(18).toString('base64url')}`
  }
  const slice2 = seedSlice2MasterData(suffix, slice2Passwords)
  const slice5Password = `S5!${randomBytes(18).toString('base64url')}`
  const slice5QmPassword = `PB1!${randomBytes(18).toString('base64url')}`
  const retail = seedSlice5MasterData(suffix, slice5Password, slice2, slice5QmPassword)
  const b0 = await seedSlice2SourceBatch(slice2, master.productFish, slice2Passwords.source)
  console.log(`[smoke] Slice 2 B0 已由来源账号经真实 API 创建并激活: ${b0.traceBatchNo} ${b0.flowStatus}/${b0.riskStatus} ${b0.quantity} ${b0.unitCode}`)

  if (keepForManualAcceptance) {
    viteServer = startViteDevServer()
    console.log('[smoke] ===== SMOKE_KEEP 手工验收模式（账号只存在于本次隔离 schema，退出时随 schema 删除）=====')
    console.log('[smoke] 浏览器地址: http://localhost:5173/login  （建议三个独立浏览器 Profile）')
    console.log(`[smoke] 来源企业 ${slice2.sourceOrgName}: ${slice2.usernames.source} / ${slice2Passwords.source}`)
    console.log(`[smoke] 承运企业 ${slice2.carrierOrgName}: ${slice2.usernames.carrier} / ${slice2Passwords.carrier}`)
    console.log(`[smoke] 加工企业 ${slice2.processorOrgName}: ${slice2.usernames.processor} / ${slice2Passwords.processor}`)
    console.log(`[smoke] 零售企业 ${retail.retailerOrgName}: ${retail.username} / ${slice5Password}`)
    console.log(`[smoke] 加工企业质量管理员（PB1）: ${slice2.usernames.processorQm} / ${slice2Passwords.processorQm}`)
    console.log(`[smoke] 零售企业质量管理员（PB1）: ${retail.qmUsername} / ${slice5QmPassword}`)
    console.log(`[smoke] B0: /app/batches/${b0.id}  (${b0.traceBatchNo}, 1000 kg ACTIVE/NORMAL)`)
    console.log('[smoke] Slice 3：加工企业接受 B0 后，在 B0 详情执行“加工”（产出 960，损耗 30，留样 10），再对 B1 执行“拆分”（600 + 360）。')
    console.log(`[smoke] Slice 4：加工企业在 B2、B3 详情“自有冷库仓储”中选择 ${slice2.coldStoreName}，各执行一次冷库入库与冷库出库。`)
    console.log(`[smoke] Slice 5：加工企业为 B2、B3 分别发起交接给 ${retail.retailerOrgName}，在同一运输任务装载两张交接（目的地 ${retail.storeName}）并提交；承运商发运、到达；`)
    console.log(`[smoke]          零售企业接受后先在 B2、B3 详情“公开追溯码”中激活公开追溯码，再在 B2 详情"终端销售"中于 ${retail.storeName} 先售 200、再售 400，在 B3 售出全部 360。`)
    console.log('[smoke] Slice 6：在零售企业 B2 / B3 详情复制消费者查询链接，用未登录的浏览器窗口打开，查看 B0 → B1 → B2（或 B3）谱系与公开事实。')
    console.log('[smoke] PB1（可选）：质量管理员在批次详情“风险状态”中填写原因并二次确认风险冻结 / 解除冻结；冻结期间操作员的交接、加工、仓储、销售入口消失。')
    console.log('[smoke] 完成页面操作后按 Ctrl+C：脚本将先输出 Slice 2 ~ Slice 6 数据库事实，再停止服务并删除 schema。')
    await waitForInterrupt()
    try {
      verifySlice2Database(slice2, b0.id)
    } catch (verifyError) {
      console.error(verifyError.message)
    }
    let slice3Ids = null
    try {
      slice3Ids = verifySlice3Database(slice2, b0.id)
    } catch (verifyError) {
      console.error(verifyError.message)
    }
    if (slice3Ids) {
      try {
        verifySlice4Database(slice2, slice3Ids, null)
      } catch (verifyError) {
        console.error(verifyError.message)
      }
      try {
        verifySlice5Database(slice2, retail, slice3Ids)
      } catch (verifyError) {
        console.error(verifyError.message)
      }
      try {
        verifySlice5PublicCodes(retail, slice3Ids)
      } catch (verifyError) {
        console.error(verifyError.message)
      }
      try {
        verifySlice5Chronology(retail, slice3Ids)
      } catch (verifyError) {
        console.error(verifyError.message)
      }
    }
  } else {
  const fixture = await seedBusinessData(suffix, master, passwordA, passwordB, passwordQm)
  console.log('[smoke] MySQL batch 表（id, trace_batch_no, external_batch_no, flow, risk, 属于登录组织）:')
  console.log(databaseEvidence(master.orgA).split('\n').map((line) => `  ${line}`).join('\n'))

  const expected = {
    orgName: `冒烟来源捕捞企业_${suffix}`,
    fishName: 'Phase0冒烟冷冻大黄鱼',
    active: fixture.active.traceBatchNo,
    activeId: fixture.active.id,
    draft: fixture.draft.traceBatchNo,
    frozen: fixture.frozen.traceBatchNo,
    recalled: fixture.recalled.traceBatchNo,
    closed: fixture.closed.traceBatchNo,
    foreign: fixture.foreign.traceBatchNo,
    publicTraceId: fixture.publicTraceId,
    recalledPublicTraceId: fixture.recalledPublicTraceId
  }
  await runBrowserSmoke({
    SMOKE_USERNAME: `p0_src_a_${suffix}`,
    SMOKE_PASSWORD: passwordA,
    SMOKE_EXPECTED: JSON.stringify(expected)
  })
  verifyBrowserCreatedSourceBatch(master.orgA)
  console.log('[smoke] 真实 Vue → Vite proxy → Spring Boot → MySQL 8.4 企业端、来源建批与消费者冒烟通过')

  await runBrowserSmoke({
    SLICE2_SOURCE_USERNAME: slice2.usernames.source,
    SLICE2_SOURCE_PASSWORD: slice2Passwords.source,
    SLICE2_CARRIER_USERNAME: slice2.usernames.carrier,
    SLICE2_CARRIER_PASSWORD: slice2Passwords.carrier,
    SLICE2_PROCESSOR_USERNAME: slice2.usernames.processor,
    SLICE2_PROCESSOR_PASSWORD: slice2Passwords.processor,
    SLICE2_EXPECTED: JSON.stringify({
      b0Id: b0.id,
      b0TraceBatchNo: b0.traceBatchNo,
      sourceOrgName: slice2.sourceOrgName,
      carrierOrgName: slice2.carrierOrgName,
      processorOrgName: slice2.processorOrgName,
      sourceSiteName: slice2.sourceSiteName,
      processorSiteName: slice2.processorSiteName
    })
  }, 'tests/e2e/real-slice2.spec.ts')
  verifySlice2Database(slice2, b0.id)
  console.log('[smoke] Slice 2 真实三账号 Transfer + Shipment 浏览器验收与 MySQL 事实校验通过')

  await runBrowserSmoke({
    SLICE3_PROCESSOR_USERNAME: slice2.usernames.processor,
    SLICE3_PROCESSOR_PASSWORD: slice2Passwords.processor,
    SLICE3_EXPECTED: JSON.stringify({ b0Id: b0.id, b0TraceBatchNo: b0.traceBatchNo })
  }, 'tests/e2e/real-slice3.spec.ts')
  const slice3Ids = verifySlice3Database(slice2, b0.id)
  console.log('[smoke] Slice 3 真实加工企业 PROCESS / SPLIT 浏览器验收与 MySQL 事实校验通过')

  const slice4Before = slice4Snapshot(slice2, slice3Ids)
  await probeForeignColdStore(slice2, slice3Ids, slice2Passwords.processor)
  await runBrowserSmoke({
    SLICE4_PROCESSOR_USERNAME: slice2.usernames.processor,
    SLICE4_PROCESSOR_PASSWORD: slice2Passwords.processor,
    SLICE4_EXPECTED: JSON.stringify({
      b2Id: slice3Ids.b2Id,
      b3Id: slice3Ids.b3Id,
      coldStoreName: slice2.coldStoreName,
      processorOrgName: slice2.processorOrgName
    })
  }, 'tests/e2e/real-slice4.spec.ts')
  verifySlice4Database(slice2, slice3Ids, slice4Before)
  console.log('[smoke] Slice 4 真实加工企业自有冷库入库 / 出库浏览器验收与 MySQL 事实校验通过')

  const traceNo = (id) => mysql(`SELECT trace_batch_no FROM batch WHERE id = ${id};`, { database: schema })

  const pb1Before = pb1ProcessorSnapshot(slice3Ids)
  await runBrowserSmoke({
    PB1_PROCESSOR_USERNAME: slice2.usernames.processor,
    PB1_PROCESSOR_PASSWORD: slice2Passwords.processor,
    PB1_PROCESSOR_QM_USERNAME: slice2.usernames.processorQm,
    PB1_PROCESSOR_QM_PASSWORD: slice2Passwords.processorQm,
    PB1_EXPECTED: JSON.stringify({
      b2Id: slice3Ids.b2Id,
      b3Id: slice3Ids.b3Id,
      b2TraceBatchNo: traceNo(slice3Ids.b2Id),
      processorOrgName: slice2.processorOrgName,
      sourceOrgId: slice2.sourceOrgId,
      coldStoreSiteId: slice2.coldStoreSiteId
    })
  }, 'tests/e2e/real-pb1-freeze.spec.ts')
  verifyPb1ProcessorDatabase(slice2, slice3Ids, pb1Before)
  console.log('[smoke] PB1-A 真实加工企业质量管理员风险冻结 / 解除 B2、冻结期间 Phase A 守卫阻断与查询可用浏览器验收与 MySQL 事实校验通过')

  await runBrowserSmoke({
    SLICE5_PROCESSOR_USERNAME: slice2.usernames.processor,
    SLICE5_PROCESSOR_PASSWORD: slice2Passwords.processor,
    SLICE5_CARRIER_USERNAME: slice2.usernames.carrier,
    SLICE5_CARRIER_PASSWORD: slice2Passwords.carrier,
    SLICE5_RETAILER_USERNAME: retail.username,
    SLICE5_RETAILER_PASSWORD: slice5Password,
    SLICE5_RETAILER_QM_USERNAME: retail.qmUsername,
    SLICE5_RETAILER_QM_PASSWORD: slice5QmPassword,
    SLICE5_EXPECTED: JSON.stringify({
      b2Id: slice3Ids.b2Id,
      b3Id: slice3Ids.b3Id,
      b2TraceBatchNo: traceNo(slice3Ids.b2Id),
      b3TraceBatchNo: traceNo(slice3Ids.b3Id),
      processorOrgId: slice2.processorOrgId,
      processorOrgName: slice2.processorOrgName,
      carrierOrgName: slice2.carrierOrgName,
      retailerOrgName: retail.retailerOrgName,
      processorSiteName: slice2.processorSiteName,
      retailerStoreName: retail.storeName,
      retailerStoreSiteId: retail.storeSiteId,
      retailerHubSiteId: retail.hubSiteId,
      retailerInactiveStoreId: retail.inactiveStoreId,
      processorStoreSiteId: retail.processorStoreSiteId
    })
  }, 'tests/e2e/real-slice5.spec.ts')
  verifySlice5Database(slice2, retail, slice3Ids)
  verifySlice5PublicCodes(retail, slice3Ids)
  verifySlice5Chronology(retail, slice3Ids)
  verifyPb1SaleCheckpoint(retail, slice3Ids)
  console.log('[smoke] Slice 5 真实加工 → 零售交接、销售前激活公开追溯码、PB1-S 冻结阻断销售后解除与终端 Sale 浏览器验收与 MySQL 事实校验通过')

  const slice6Before = slice6Snapshot(slice3Ids)
  await runBrowserSmoke({
    SLICE6_RETAILER_USERNAME: retail.username,
    SLICE6_RETAILER_PASSWORD: slice5Password,
    SLICE6_EXPECTED: JSON.stringify({
      b2Id: slice3Ids.b2Id,
      b3Id: slice3Ids.b3Id,
      b2TraceBatchNo: traceNo(slice3Ids.b2Id),
      b3TraceBatchNo: traceNo(slice3Ids.b3Id),
      // 这些内部 / 企业信息绝不能出现在匿名消费者页面或响应中
      forbidden: [
        traceNo(slice3Ids.b0Id), traceNo(slice3Ids.b1Id),
        slice2.sourceOrgName, slice2.carrierOrgName, slice2.processorOrgName, retail.retailerOrgName,
        slice2.sourceSiteName, slice2.processorSiteName, slice2.coldStoreName, retail.storeName,
        slice2.usernames.source, slice2.usernames.carrier, slice2.usernames.processor, retail.username,
        '浙L·冷S2001', '浙L·冷S5001', '东海舟山渔场（Slice 2 验收）', 'S2-B0', 'SYS:', 'detailsJson', 'summary'
      ]
    })
  }, 'tests/e2e/real-slice6.spec.ts')
  const slice6After = slice6Snapshot(slice3Ids)
  const slice6ReadOnly = slice6After === slice6Before
  console.log(`[smoke] MySQL ${slice6ReadOnly ? '✔' : '✘'} Slice 6 匿名消费者查询与零售企业只读查看零写入（批次 / 公开码版本与全部业务表行数不变）`)
  if (!slice6ReadOnly) throw new Error(`Slice 6 查询产生了写入: before=${slice6Before} after=${slice6After}`)
  console.log('[smoke] Slice 6 真实匿名消费者扫码 B2 / B3 公开全链浏览器验收与 MySQL 零写入校验通过')

  const pb1bBefore = pb1ClosedSnapshot(slice3Ids)
  await runBrowserSmoke({
    PB1B_RETAILER_USERNAME: retail.username,
    PB1B_RETAILER_PASSWORD: slice5Password,
    PB1B_RETAILER_QM_USERNAME: retail.qmUsername,
    PB1B_RETAILER_QM_PASSWORD: slice5QmPassword,
    PB1B_EXPECTED: JSON.stringify({
      b2Id: slice3Ids.b2Id,
      b2TraceBatchNo: traceNo(slice3Ids.b2Id),
      retailerOrgName: retail.retailerOrgName,
      storeSiteId: retail.storeSiteId
    })
  }, 'tests/e2e/real-pb1-closed.spec.ts')
  verifyPb1ClosedDatabase(retail, slice3Ids, pb1bBefore)
  console.log('[smoke] PB1-B 真实零售质量管理员冻结 / 解除已售罄 CLOSED 的 B2 与匿名消费者模拟冻结投影浏览器验收与 MySQL 事实校验通过')
  }
} catch (error) {
  primaryError = error
} finally {
  stopProcessTree(viteServer)
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
