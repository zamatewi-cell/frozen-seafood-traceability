/**
 * Phase 0 + Phase A / Slice 1 ~ Slice 4 真实浏览器冒烟：真实 MySQL 8.4 → Spring Boot → Vite proxy → Vue。
 *
 * 1. 在 MySQL 8.4 容器中新建隔离 schema（seafood_trace_phase0_demo_<随机后缀>），绝不触碰 seafood_trace；
 * 2. 以进程级随机 pepper 启动 Spring Boot（Flyway 在空 schema 上执行 V1~V9）；
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
 * 9. 无论成功失败都停止后端、删除 schema 并撤销授权，验证残留为 0。
 *
 * 密码、pepper、Cookie 与 CSRF 凭据只在进程环境与内存中传递，从不打印。
 * 例外：SMOKE_KEEP=true 手工验收模式只准备 Slice 2 基础资料，打印一次三个临时账号供人工在真实浏览器中操作，
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

function seedMasterData(suffix, passwordA, passwordB) {
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
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgA, 'p0_src_a_${suffix}', '冒烟来源操作员', ${sqlText(springPbkdf2(passwordA))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
    INSERT INTO app_user (org_id, username, display_name, password_hash, status)
      VALUES (@orgB, 'p0_src_b_${suffix}', '冒烟第二来源操作员', ${sqlText(springPbkdf2(passwordB))}, 'ACTIVE');
    INSERT INTO user_role (user_id, role_id) VALUES (LAST_INSERT_ID(), @role);
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

async function seedBusinessData(suffix, master, passwordA, passwordB) {
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

  const sessionB = new ApiSession()
  await sessionB.login(`p0_src_b_${suffix}`, passwordB)
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
    usernames: { source: `s2_src_${suffix}`, carrier: `s2_car_${suffix}`, processor: `s2_prc_${suffix}` }
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
  const master = seedMasterData(suffix, passwordA, passwordB)
  const slice2Passwords = {
    source: `S2!${randomBytes(18).toString('base64url')}`,
    carrier: `S2!${randomBytes(18).toString('base64url')}`,
    processor: `S2!${randomBytes(18).toString('base64url')}`
  }
  const slice2 = seedSlice2MasterData(suffix, slice2Passwords)
  const b0 = await seedSlice2SourceBatch(slice2, master.productFish, slice2Passwords.source)
  console.log(`[smoke] Slice 2 B0 已由来源账号经真实 API 创建并激活: ${b0.traceBatchNo} ${b0.flowStatus}/${b0.riskStatus} ${b0.quantity} ${b0.unitCode}`)

  if (keepForManualAcceptance) {
    viteServer = startViteDevServer()
    console.log('[smoke] ===== SMOKE_KEEP 手工验收模式（账号只存在于本次隔离 schema，退出时随 schema 删除）=====')
    console.log('[smoke] 浏览器地址: http://localhost:5173/login  （建议三个独立浏览器 Profile）')
    console.log(`[smoke] 来源企业 ${slice2.sourceOrgName}: ${slice2.usernames.source} / ${slice2Passwords.source}`)
    console.log(`[smoke] 承运企业 ${slice2.carrierOrgName}: ${slice2.usernames.carrier} / ${slice2Passwords.carrier}`)
    console.log(`[smoke] 加工企业 ${slice2.processorOrgName}: ${slice2.usernames.processor} / ${slice2Passwords.processor}`)
    console.log(`[smoke] B0: /app/batches/${b0.id}  (${b0.traceBatchNo}, 1000 kg ACTIVE/NORMAL)`)
    console.log('[smoke] Slice 3：加工企业接受 B0 后，在 B0 详情执行“加工”（产出 960，损耗 30，留样 10），再对 B1 执行“拆分”（600 + 360）。')
    console.log(`[smoke] Slice 4：加工企业在 B2、B3 详情“自有冷库仓储”中选择 ${slice2.coldStoreName}，各执行一次冷库入库与冷库出库。`)
    console.log('[smoke] 完成页面操作后按 Ctrl+C：脚本将先输出 Slice 2 / Slice 3 / Slice 4 数据库事实，再停止服务并删除 schema。')
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
    }
  } else {
  const fixture = await seedBusinessData(suffix, master, passwordA, passwordB)
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
    publicTraceId: fixture.publicTraceId
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
