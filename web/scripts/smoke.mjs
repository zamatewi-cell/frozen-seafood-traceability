import { randomInt } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync, spawn, spawnSync } from 'node:child_process'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webDir = resolve(scriptDir, '..')
const repositoryDir = resolve(webDir, '..')
const serverDir = resolve(repositoryDir, 'server')
const localEnvPath = resolve(repositoryDir, '.env')
const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
const productName = '受控冒烟测试海产品'
const backendOrigin = 'http://127.0.0.1:18081'
const containerName = process.env.SMOKE_MYSQL_CONTAINER || 'seafood-mysql'

function readLocalEnvironment() {
  if (!existsSync(localEnvPath)) return {}

  return Object.fromEntries(
    readFileSync(localEnvPath, 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const separator = line.indexOf('=')
        const key = line.slice(0, separator).trim()
        const value = line.slice(separator + 1).trim().replace(/^(['"])(.*)\1$/, '$2')
        return [key, value]
      })
  )
}

const localEnv = readLocalEnvironment()
const dbName = process.env.DB_NAME || localEnv.DB_NAME || 'seafood_trace'
const dbUser = process.env.DB_USERNAME || localEnv.DB_USERNAME || 'trace_user'
const dbPassword = process.env.DB_PASSWORD || localEnv.DB_PASSWORD

if (!dbPassword) {
  throw new Error('真实冒烟测试需要通过环境变量或仓库根目录 .env 提供 DB_PASSWORD')
}

function runSql(sql) {
  return execFileSync(
    'docker',
    [
      'exec', '-i', '-e', `MYSQL_PWD=${dbPassword}`,
      containerName,
      'mysql', '--batch', '--raw', '--skip-column-names',
      '--default-character-set=utf8mb4',
      '-u', dbUser, dbName
    ],
    { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] }
  ).trim()
}

function randomPublicTraceId() {
  let result = 'SMOKE'
  while (result.length < 26) result += alphabet[randomInt(alphabet.length)]
  return result
}

function allocateFixture() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const id = randomInt(4_000_000_000, 4_200_000_000)
    const suffix = `${id}`
    const publicTraceId = randomPublicTraceId()
    const occupied = Number(runSql(`
      SELECT
        (SELECT COUNT(*) FROM organization WHERE id = ${id} OR org_no = 'SMOKE_ORG_${suffix}') +
        (SELECT COUNT(*) FROM product WHERE id = ${id} OR product_code = 'SMOKE_PROD_${suffix}') +
        (SELECT COUNT(*) FROM batch WHERE id = ${id}) +
        (SELECT COUNT(*) FROM trace_event WHERE id = ${id}) +
        (SELECT COUNT(*) FROM public_trace_code WHERE id = ${id} OR public_id = '${publicTraceId}');
    `))
    if (occupied === 0) return { id, suffix, publicTraceId }
  }
  throw new Error('无法分配无碰撞的冒烟测试夹具标识')
}

function seedFixture(fixture) {
  const { id, suffix, publicTraceId } = fixture
  runSql(`
    START TRANSACTION;
    INSERT INTO organization
      (id, org_no, name, org_type, status, credit_code, is_deleted, created_at, updated_at)
    VALUES
      (${id}, 'SMOKE_ORG_${suffix}', '受控冒烟测试组织', 'SOURCE', 'ACTIVE',
       'SMOKE_CREDIT_${suffix}', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

    INSERT INTO product
      (id, product_code, public_name, category, specification, source_type, base_unit_code,
       status, is_deleted, version, created_at, updated_at)
    VALUES
      (${id}, 'SMOKE_PROD_${suffix}', '${productName}', 'FISH', '500g/盒',
       'DOMESTIC_CAPTURE', 'kg', 'ACTIVE', 0, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

    INSERT INTO batch
      (id, batch_no, batch_type, org_id, product_id, quantity, unit_code, status,
       origin_type, origin_text, production_date, is_deleted, version, created_at, updated_at)
    VALUES
      (${id}, 'SMOKE_BATCH_${suffix}', 'SOURCE', ${id}, ${id}, 10.000, 'kg', 'ACTIVE',
       'DOMESTIC_CAPTURE', '受控冒烟测试海域', UTC_DATE(), 0, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

    INSERT INTO trace_event
      (id, batch_id, org_id, event_type, occurred_at, recorded_at, status, data_source,
       idempotency_key, summary, is_deleted, created_at, updated_at)
    VALUES
      (${id}, ${id}, ${id}, 'SOURCE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 'SUBMITTED',
       'SIMULATED', 'SMOKE_EVENT_${suffix}', '受控冒烟测试事件', 0,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

    INSERT INTO public_trace_code
      (id, batch_id, org_id, public_id, token_hash, status, activated_at, version,
       is_deleted, created_at, updated_at)
    VALUES
      (${id}, ${id}, ${id}, '${publicTraceId}', SHA2('${publicTraceId}', 256), 'ACTIVE',
       UTC_TIMESTAMP(6), 0, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
    COMMIT;
  `)
}

function cleanupFixture(fixture) {
  if (!fixture) return
  const { id } = fixture
  runSql(`
    START TRANSACTION;
    DELETE FROM public_trace_code_idempotency WHERE id = ${id};
    DELETE FROM public_trace_code WHERE id = ${id};
    DELETE FROM trace_event WHERE id = ${id};
    DELETE FROM batch WHERE id = ${id};
    DELETE FROM product WHERE id = ${id};
    DELETE FROM organization WHERE id = ${id};
    COMMIT;
  `)

  const remaining = Number(runSql(`
    SELECT
      (SELECT COUNT(*) FROM organization WHERE id = ${id}) +
      (SELECT COUNT(*) FROM product WHERE id = ${id}) +
      (SELECT COUNT(*) FROM batch WHERE id = ${id}) +
      (SELECT COUNT(*) FROM trace_event WHERE id = ${id}) +
      (SELECT COUNT(*) FROM public_trace_code WHERE id = ${id}) +
      (SELECT COUNT(*) FROM public_trace_code_idempotency WHERE id = ${id});
  `))
  if (remaining !== 0) throw new Error(`冒烟测试夹具清理不完整，仍有 ${remaining} 行残留`)
}

async function waitForBackend(processHandle, logBuffer) {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    if (processHandle.exitCode !== null) {
      throw new Error(`Spring Boot 提前退出。最近日志：\n${logBuffer.value.slice(-4000)}`)
    }
    try {
      const response = await fetch(`${backendOrigin}/actuator/health`)
      if (response.ok) return
    } catch {
      // 服务仍在启动。
    }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000))
  }
  throw new Error(`等待 Spring Boot 启动超时。最近日志：\n${logBuffer.value.slice(-4000)}`)
}

function startBackend(logBuffer) {
  const executable = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : './mvnw'
  const args = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'mvnw.cmd -q -DskipTests spring-boot:run']
    : ['-q', '-DskipTests', 'spring-boot:run']
  const child = spawn(executable, args, {
    cwd: serverDir,
    env: {
      ...process.env,
      ...localEnv,
      DB_NAME: dbName,
      DB_USERNAME: dbUser,
      DB_PASSWORD: dbPassword,
      SERVER_PORT: '18081'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  child.stdout.on('data', (chunk) => { logBuffer.value += chunk.toString() })
  child.stderr.on('data', (chunk) => { logBuffer.value += chunk.toString() })
  return child
}

function stopProcessTree(child) {
  if (!child || child.exitCode !== null || !child.pid) return
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

function runFrontendSmoke(fixture) {
  const npmCommand = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'npm'
  const npmArgs = process.platform === 'win32'
    ? ['/d', '/s', '/c', 'npm.cmd run test:e2e -- tests/e2e/real-smoke.spec.ts --project=desktop']
    : ['run', 'test:e2e', '--', 'tests/e2e/real-smoke.spec.ts', '--project=desktop']
  const result = spawnSync(
    npmCommand,
    npmArgs,
    {
      cwd: webDir,
      env: {
        ...process.env,
        REAL_SMOKE: 'true',
        SMOKE_PUBLIC_TRACE_ID: fixture.publicTraceId,
        SMOKE_PRODUCT_NAME: productName,
        VITE_BACKEND_PROXY_TARGET: backendOrigin
      },
      stdio: 'inherit'
    }
  )
  if (result.status !== 0) throw new Error(`真实前后端浏览器冒烟失败，退出码 ${result.status}`)
}

let fixture
let backend
let primaryError
const backendLog = { value: '' }

try {
  const mysqlVersion = runSql('SELECT VERSION();')
  if (!mysqlVersion.startsWith('8.4.')) {
    throw new Error(`需要 MySQL 8.4，当前容器返回 ${mysqlVersion}`)
  }

  fixture = allocateFixture()
  seedFixture(fixture)
  backend = startBackend(backendLog)
  await waitForBackend(backend, backendLog)
  runFrontendSmoke(fixture)
  console.log('真实 Vue → Vite proxy → Spring Boot → MySQL 8.4 冒烟通过')
} catch (error) {
  primaryError = error
} finally {
  stopProcessTree(backend)
  try {
    cleanupFixture(fixture)
    if (fixture) console.log('冒烟测试夹具已完成物理清理，残留 0 行')
  } catch (cleanupError) {
    if (!primaryError) primaryError = cleanupError
    else console.error(cleanupError)
  }
}

if (primaryError) throw primaryError
