# Phase 3: 核心服务与最小闭环交付文档

本文档记录冷冻海产品溯源系统 Phase 3 的接口调用契约、安全设计机制、领域模型算法与测试分层规范：
1. **第一部分**：会话认证与组织上下文交付规范（GitHub Issue #7 “session authentication and organization context”）
2. **第二部分**：产品主数据与分阶段温控规则交付规范（GitHub Issue #9 “product master data and versioned temperature rules”）
3. **第三部分**：组织范围批次草稿生命周期交付规范（GitHub Issue #11 “organization-scoped batch draft lifecycle”）
4. **第四部分**：批次操作、物料平衡与谱系边交付规范（GitHub Issue #13 “batch operations, mass balance, and genealogy edges”）
5. **第五部分**：追溯事件与更正工作流交付规范（GitHub Issue #15 “append-only trace events and correction workflow”）
6. **第六部分**：公开追溯码与消费者端投影实现与验证规范（GitHub Issue #17，当前分支 `feat/17-public-trace-consumer` 正在实现 / 待合并验收）

---

# 第一部分：会话认证与组织上下文交付文档 (Session Auth & Organization Context)

本文档记录冷冻海产品溯源系统 Phase 3（GitHub Issue #7 “session authentication and organization context”）的接口调用契约、安全设计机制、测试分层策略以及真实数据库验证规范。

---

## 一、核心目标与安全边界

1. **服务端会话认证体系**：基于 Spring Security 7 与 Servlet 容器安全会话构建，采用命名为 `TRACESESSION` 的 HttpOnly、SameSite=Lax Cookie 维持登录态。
2. **组织上下文唯一服务端推导**：客户端严禁向服务端传递 `orgId`。组织上下文由服务端根据当前登录主体绑定的组织数据唯一确立。
3. **严格白名单数据投影**：所有面向客户端的当前用户信息（`/api/v1/me`）仅包含白名单安全属性（`userId`, `username`, `displayName`, `orgId`, `orgNo`, `orgName`, `orgType`, `roles`, `scopes`），严禁暴露 `passwordHash`、`creditCode` 等内部敏感字段。
4. **无固定演示账号与无预植入密码原则**：
   - Flyway 迁移脚本严格限定为 DDL，禁止新增或修改 V1 迁移脚本，绝对不在迁移中写入演示账号或密码 Hash 种子；
   - 代码中无任何硬编码默认账号密码；
   - 真实集成测试运行时动态生成随机密码与临时数据，并在测试结束后执行彻底的物理清理。

---

## 二、接口调用契约与交互时序

```mermaid
sequenceDiagram
    autonumber
    actor Client as 客户端 (Browser/API)
    participant Sec as Spring Security 过滤器链
    participant Auth as AuthController
    participant User as CurrentUserController
    participant Filter as ActivePrincipalVerificationFilter
    participant DB as 数据库 (MySQL 8.4)

    Note over Client,Sec: 1. 匿名获取 CSRF 凭据
    Client->>Sec: GET /api/v1/auth/csrf
    Sec-->>Client: 200 OK (Set-Cookie: TRACESESSION, CSRF Token 凭据)

    Note over Client,Auth: 2. 账号密码登录
    Client->>Sec: POST /api/v1/auth/login (Header: X-CSRF-TOKEN, Cookie: TRACESESSION)
    Sec->>Auth: 转发请求
    Auth->>DB: 验证用户、密码 Hash、组织状态及有效角色
    Auth->>Auth: Session ID 轮换 (防会话固定)
    Auth->>Auth: 显式 SecurityContextRepository.saveContext
    Auth-->>Client: 200 OK (Set-Cookie: 新 TRACESESSION, CurrentUser 白名单)

    Note over Client,User: 3. 受保护资源访问与活性复核
    Client->>Sec: GET /api/v1/me (Cookie: 新 TRACESESSION)
    Sec->>Filter: 执行活性复核
    Filter->>DB: 复核用户 ACTIVE、组织 ACTIVE、角色有效
    Filter->>User: 复核通过
    User-->>Client: 200 OK (CurrentUser 白名单数据)

    Note over Client,Auth: 4. 安全退出注销
    Client->>Sec: POST /api/v1/auth/logout (Header: X-CSRF-TOKEN, Cookie)
    Sec->>Auth: 清空安全上下文
    Auth->>Auth: session.invalidate()
    Auth-->>Client: 204 No Content
```

### 1. 获取 CSRF Token (`GET /api/v1/auth/csrf`)
- **权限**：匿名公开访问；
- **说明**：基于 `HttpSessionCsrfTokenRepository` 获取 CSRF Token，同时触发服务端安全会话的初始化；
- **响应**：
  - `Set-Cookie: TRACESESSION=<sessionId>; Path=/; HttpOnly; SameSite=Lax`
  - 响应体：
    ```json
    {
      "data": {
        "headerName": "X-CSRF-TOKEN",
        "parameterName": "_csrf",
        "token": "<uuid-token>"
      },
      "meta": {
        "requestId": "<req-id>",
        "timestamp": "2026-09-04T22:50:00Z"
      }
    }
    ```

### 2. 用户登录 (`POST /api/v1/auth/login`)
- **权限**：公开访问，但**强制校验 CSRF Token**；
- **入参**：
  ```json
  {
    "username": "user123",
    "password": "RawPassword123!"
  }
  ```
- **核心逻辑**：
  1. 数据库用户认证：校验加盐密码哈希（BCrypt）、用户状态（`ACTIVE` 且 `is_deleted=0`）、所属组织状态（`ACTIVE` 且 `is_deleted=0`）及已分配启用角色；
  2. 防会话固定攻击：调用 `SessionAuthenticationStrategy` 进行 Session ID 轮换；
  3. 上下文持久化：显式调用 `SecurityContextRepository.saveContext`；
  4. 登录审计更新：更新用户 `last_login_at` 字段为当前 UTC 时间。
- **错误收敛**：
  - 未知用户名、错误密码、锁定账户、停用账户、停用组织、未分配有效角色，对外统一收敛为 **`401 AUTH_CREDENTIALS_INVALID`**，不向调用方泄露账号存在性或状态信息。

### 3. 获取当前用户信息 (`GET /api/v1/me`)
- **权限**：已登录受保护资源；
- **说明**：从服务端安全上下文 `TraceSecurityPrincipal` 中获取当前登录用户的组织上下文、角色编码和权限作用域，白名单安全输出。
- **响应示例**：
  ```json
  {
    "data": {
      "userId": 1,
      "username": "alice",
      "displayName": "爱丽丝",
      "orgId": 100,
      "orgNo": "ORG_001",
      "orgName": "深蓝捕捞集团",
      "orgType": "SOURCE",
      "roles": ["OPERATOR"],
      "scopes": ["ORG_ONLY"]
    },
    "meta": {
      "requestId": "<req-id>",
      "timestamp": "2026-09-04T22:50:00Z"
    }
  }
  ```

### 4. 退出登录 (`POST /api/v1/auth/logout`)
- **权限**：已登录受保护资源，需要 CSRF；
- **说明**：清除 `SecurityContext` 并调用 `session.invalidate()` 使服务端会话失效；
- **响应**：`204 No Content`。

---

## 三、全局动态活性复核机制

通过 `ActivePrincipalVerificationFilter`（置于 `AuthorizationFilter` 之前）对每个受保护请求进行实时数据库状态与安全上下文复核：
1. **用户活性复核**：查询数据库确认用户未被逻辑删除，且状态为 `ACTIVE`；
2. **组织归属一致性复核**：比较数据库最新 `user.orgId` 与认证主体 `principal.orgId`，防止管理员调整组织归属后旧会话跨组织越权；
3. **组织活性复核**：查询数据库确认用户所属组织未被逻辑删除，且状态为 `ACTIVE`；
4. **角色与权限集合一致性复核**：查询数据库当前分配给用户的有效角色与作用域集合，与 `principal` 中的角色及作用域进行无序集合（Set）比对。任何角色撤销、替换、增加或作用域范围调整，均能被即时侦测。

**失效处理**：
任一复核失败时，立即：
- 清理 `SecurityContextHolder.clearContext()`；
- 使会话强制失效 `session.invalidate()`；
- 拦截请求，直接输出统一 RFC 9457 Problem Details（状态码 `401`，错误码 `AUTH_REQUIRED`）。

---

## 四、测试分层策略与执行方法

### 1. 默认测试套件（无外置依赖，用于 CI 快速门禁与离线验证）
完全使用 MockMvc 与 MockitoBean，不依赖任何真实外部服务与网络：
- `SampleControllerTest`：基础示例端点、响应封套与全局异常验证；
- `TraceUserDetailsServiceTest`：用户状态多维度校验与异常类型纯单元测试；
- `AuthControllerTest`：覆盖 CSRF 校验、登录参数边界约束（3..64, 8..128）、登录成功会话、会话固定防护轮换、白名单字段投影、统一错误收敛（密码错误、用户锁定、停用、组织停用、无角色）、注销失效、组织归属变更即刻下线、角色/作用域集合变更即刻下线、无序角色集合稳定放行。

**执行命令（需在 `server` 目录下执行）**：
```powershell
# 从仓库根目录进入 server
cd server
# Windows PowerShell
.\mvnw.cmd clean test
```

### 2. 真实 MySQL 8.4 集成测试（受条件环境变量控制）
测试类：`AuthMysqlIntegrationTest`
- **控制条件**：仅在环境变量 `MYSQL_IT_ENABLED=true` 时激活；若未启用或数据库未就绪则在默认构建中安全跳过（Skipped）；
- **物理清理**：注入 `JdbcTemplate`，在 `@AfterEach` 中按 `user_role` -> `app_user` -> `role` -> `organization` 依赖顺序执行真实物理 `DELETE FROM`，不受 `@TableLogic` 影响，确保即使被测数据被标记逻辑删除也能彻底物理清空；
- **执行逻辑**：
  1. 测试动态调用密码加盐算法生成随机密码；
  2. 动态生成带随机唯一编码的测试组织、角色、用户和用户角色关联，写入 MySQL 8.4 真实库；
  3. 执行端到端完整测试：获取真实 CSRF Token -> 错误密码 401 -> 正确密码登录 -> 验证防会话固定与返回主体 -> `/me` 白名单查询 -> 修改数据库将用户/组织置为 `INACTIVE` 或调整组织归属 -> 再次访问 `/me` 验证 401 且会话失效 -> 注销；
  4. 最终在 `tearDown` 中彻底物理删除插入的临时测试数据。

**在本地真实 MySQL 环境中执行该测试的方式**：
```powershell
# 1. 在仓库根目录启动本地 Docker 数据库（映射 3307 端口）
docker compose --env-file .env -f deploy/docker-compose.yml up -d --wait

# 2. 从根目录安全加载 .env 到当前 PowerShell 环境变量（静默设置，不回显敏感密钥/密码）并开启集成测试门禁
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line.Split('=', 2)
        [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}
$env:MYSQL_IT_ENABLED = "true"

# 3. 推荐命令：进入 server 目录执行全量 clean test（全量测试通过）
cd server
.\mvnw.cmd clean test
```

> **测试范围与命令区别说明**：
> - **单独运行 `AuthMysqlIntegrationTest`**（命令：`.\mvnw.cmd test -Dtest=AuthMysqlIntegrationTest`）：仅单独执行 Phase 3 身份与会话层在真实数据库上的集成验证（覆盖 CSRF 校验、BCrypt 密码校验、会话固定防护、`/me` 白名单数据投影、数据库组织及角色活性动态复核、注销会话失效及测试数据物理销毁），耗时较短，适合在聚焦 identity 模块时快速迭代验证。
> - **全量测试（包含 `MysqlMigrationIntegrationTest`，推荐命令：`.\mvnw.cmd clean test`）**：不仅包含全部离线单元/Mock 测试和上述 Phase 3 认证集成测试，还会执行 Phase 2 的 `MysqlMigrationIntegrationTest`，在真实 MySQL 8.4 容器中完整验证 Flyway V1 迁移脚本的幂等执行、全量表结构与索引完整性。在交付验收阶段强烈推荐使用全量命令，确保数据模型与会话鉴权体系端到端完全闭环。
>
> **环境验证记录**：本项目已在本机基于 Docker 编排的 MySQL 8.4.0 容器（宿主机 3307 端口隔离）环境下完成全量测试套件验证，`MysqlMigrationIntegrationTest` 与 `AuthMysqlIntegrationTest` 全部绿标通过（0 failures, 0 errors, 0 skipped），测试数据在 `@AfterEach` 中彻底物理清理，无敏感密码哈希泄露，无复合主键告警。

---

# 第二部分：产品主数据与分阶段温控规则 (Issue #9)

## 一、核心目标与职责边界

严格遵循 GitHub Issue #9 与系统架构规范，实现产品主数据（Product）的生命周期管理以及分阶段、可版本化的温控基准规则（TemperatureRule / TemperatureRuleStage）：

1. **业务边界隔离**：
   - 本模块仅限于海产品主数据的新建、查询、更新以及温控基准方案与阶段参数的定义与发布；
   - **不实现**批次管理（Batch）、不接入实时温度记录时序流、不执行告警判定与模拟召回、不提供组织管理或前端页面。
2. **四层分层架构**：
   - 严格遵循 `web -> application -> domain -> mapper/infrastructure` 分层设计；
   - 表现层 Controller 严禁直接调用持久层 Mapper，所有事务控制、业务校验、行级锁并发控制均收敛在应用服务层（`ProductApplicationService` 与 `TemperatureRuleApplicationService`）。
3. **安全与权限边界**：
   - **读权限**：所有已认证企业用户与平台用户均具备只读查询权限，可查询产品列表/详情，并允许查看关联的温控规则列表及明细（包含 `DRAFT` 草稿规则，供生产排程计划查阅）；
   - **写权限**：创建产品、更新产品、创建规则草稿、发布规则必须具备平台管理权限（`TraceSecurityPrincipal.scopes` 包含 `PLATFORM`），企业端非平台用户写操作一律返回 **`403 ACCESS_DENIED`**；
   - **审计字段注入**：`createdBy` 与 `updatedBy` 严格由服务端从当前安全上下文提取，绝不信任客户端入参；
   - **敏感字段屏蔽**：响应 DTO 仅暴露白名单业务字段，严禁泄露 `isDeleted` 内部逻辑删除标识。

---

## 二、领域模型与生命周期约束

### 1. 实体核心属性与规范

- **产品 (Product)**：
  - `id`：主键 ID；
  - `productCode`（库字段 `product_code`）：全局唯一业务编码，格式如 `PRD-FSH-001`；
  - `publicName`：面向消费者的公开规范名称；
  - `scientificName`：学名（拉丁名/学名），可选；
  - `category`：规范化品类大写代码（`FISH`, `CRUSTACEAN`, `SHELLFISH`, `CEPHALOPOD`, `OTHER`）；
  - `specification`：规格描述（如 `500g-600g/条`、`整条装`）；
  - `sourceType`：规范化来源大写代码（`DOMESTIC_CAPTURE`, `DOMESTIC_FARMED`, `IMPORT`）；
  - `baseUnitCode`：基础计量单位，Phase 1 严格限定为 `kg`，非 `kg` 返回 400 `INVALID_REQUEST`；
  - `status`：状态（`DRAFT`, `ACTIVE`, `INACTIVE`）；
  - `version`：乐观锁版本号（更新时必须递增比对）。

- **温控规则方案 (TemperatureRule)**：
  - `id`：方案 ID；
  - `productId`：关联产品 ID；
  - `versionNo`：方案版本序号（同一产品下从 1 严格自增，数据库复合唯一键约束 `uk_rule_product_version`）；
  - `name`：规则方案名称（如 `冷冻带鱼温控基准v1`）；
  - `effectiveFrom`：生效起始时间（包含，ISO 8601 UTC 存储）；
  - `effectiveTo`：生效结束时间（不包含，可为 null 表示长期有效；不为空时必须严格大于 `effectiveFrom`）；
  - `status`：方案状态（`DRAFT` 草稿、`ACTIVE` 生效中、`RETIRED` 已废弃）；
  - `basisNote`：标准制定依据说明（如国标编号、企业标准）。

- **温控环节明细 (TemperatureRuleStage)**：
  - `id`：明细 ID；
  - `ruleId`：关联方案 ID；
  - `stageCode`：供应链环节代码（`PROCESSING`, `STORAGE`, `TRANSPORT`, `RETAIL`，同一方案内环节代码排他唯一）；
  - `lowerLimit` / `upperLimit`：温度阈值下限与上限（`DECIMAL(6,2)`，整数最多 4 位，小数最多 2 位，必须满足 `lowerLimit <= upperLimit`）；
  - `unitCode`：温标单位，严格限定为 `CELSIUS`（摄氏度）；
  - `allowedDurationSeconds`：允许越界缓冲秒数（必须 `>= 0`，默认 0）；
  - `sequenceNo`：展示次序号（必须 `>= 1`，默认 1）。

### 2. 数据库物理约束保障 (Flyway V2)

在 `V2__product_rule_constraints.sql` 中补充 MySQL 8.4 物理级 CHECK 约束与复合唯一索引，杜绝非法脏数据穿透，实际物理约束名称如下：
- `chk_product_category`：限定品类枚举值；
- `chk_product_source_type`：限定来源枚举值；
- `chk_product_status`：限定产品状态值；
- `chk_product_base_unit_code`：限定基础单位必须为 `kg`；
- `uk_rule_product_version`：同一产品下规则版本序号唯一 `(product_id, version_no)`；
- `chk_rule_status`：限定规则状态值；
- `chk_rule_effective_period`：限定 `effective_to IS NULL OR effective_to > effective_from`；
- `uk_stage_rule_stage_code`：同一规则方案下环节代码唯一 `(rule_id, stage_code)`；
- `chk_stage_code`：限定环节代码枚举值；
- `chk_stage_unit_code`：限定温标单位必须为 `CELSIUS`；
- `chk_stage_limits`：限定 `lower_limit <= upper_limit`；
- `chk_stage_duration`：限定 `allowed_duration_seconds >= 0`；
- `chk_stage_sequence`：限定 `sequence_no >= 1`。

---

## 三、并发控制与发布状态机

```mermaid
stateDiagram-v2
    [*] --> DRAFT : 平台管理员创建规则草稿 (排他行锁分配 versionNo)
    DRAFT --> ACTIVE : 平台管理员发布规则 (排他行锁校验区间无重叠 + 条件更新)
    ACTIVE --> RETIRED : 新版本上线或手动废弃 (后续演进)
```

1. **版本号生成并发串行化**：
   - 新建规则草稿时，通过 `productMapper.selectByIdForUpdate(productId)` 对所属产品加排他行锁；
   - 在事务内锁定产品后，查询当前产品最大 `versionNo`，执行 `maxVersion + 1`，彻底杜绝并发新建草稿时的版本号冲突。
2. **规则发布防竞态与半开区间冲突检测**：
   - 发布规则前同样锁定所属产品行（`selectByIdForUpdate`）；
   - 锁定后重新读取规则最新记录，二次确认规则状态仍为 `DRAFT`；
   - 校验当前产品状态必须为 `ACTIVE`；
   - 执行半开区间 `[effectiveFrom, effectiveTo)` 冲突判定算法：
     - 若待发布规则与库内已有的 `ACTIVE` 规则在时间线上发生交集，立即拒绝并抛出 `422 TEMPERATURE_RULE_EFFECTIVE_CONFLICT`；
     - 允许区间紧邻相接（例如 `[2026-01-01, 2026-06-01)` 与 `[2026-06-01, 2026-12-01)` 不重叠）。
3. **原子条件更新与版本递增防御并发假成功**：
   - 使用状态条件更新 + 版本递增（`version = version + 1`）：
     `UPDATE temperature_rule SET status = 'ACTIVE', version = version + 1, ... WHERE id = #{id} AND status = 'DRAFT' AND is_deleted = 0`；
   - 显式检查返回值 `affectedRows`，若受影响行数为 0，表明规则状态已被并发线程修改，立即抛出 **`409 INVALID_STATE_TRANSITION`**，杜绝返回假成功。

---

## 四、API 接口调用契约 (遵循 ISO 8601 与显式 UTC 偏移)

所有 API 遵循统一设计规范：请求与响应中的时间必须携带明确偏移量（例如 `+00:00` 或 `Z`），服务端统一转为 UTC 时间持久化。

### 1. 创建海产品主数据 (`POST /api/v1/products`)
- **权限**：平台管理员（`PLATFORM` scope），需要 CSRF；
- **入参**：
  ```json
  {
    "productCode": "PRD-FSH-001",
    "publicName": "舟山大黄鱼",
    "scientificName": "Larimichthys crocea",
    "category": "fish",
    "specification": "500g-600g/条",
    "sourceType": "import",
    "baseUnitCode": "kg"
  }
  ```
  *(注：`category` 与 `sourceType` 支持大小写混写，服务端自动转换为标准大写入库；`baseUnitCode` 目前仅支持 `kg`)*
- **响应**：`201 Created`
  ```json
  {
    "data": {
      "id": 100,
      "productCode": "PRD-FSH-001",
      "publicName": "舟山大黄鱼",
      "scientificName": "Larimichthys crocea",
      "category": "FISH",
      "specification": "500g-600g/条",
      "sourceType": "IMPORT",
      "baseUnitCode": "kg",
      "status": "DRAFT",
      "version": 0,
      "createdAt": "2026-09-07T08:00:00Z",
      "createdBy": 1,
      "updatedAt": "2026-09-07T08:00:00Z",
      "updatedBy": 1
    },
    "meta": {
      "requestId": "01J...",
      "timestamp": "2026-09-07T16:00:00+08:00"
    }
  }
  ```

### 2. 分页查询产品列表 (`GET /api/v1/products`)
- **权限**：所有已登录用户；
- **查询参数**：
  - `page`：页码，从 1 开始，默认 1（传 `<=0` 返回 400 `INVALID_REQUEST`）；
  - `size`：分页大小，默认 20，取值范围 `1..100`（超出返回 400 `INVALID_REQUEST`）；
  - `keyword`：模糊匹配产品编码（`productCode`）或公开名称（`publicName`）；
  - `category` / `sourceType` / `status`：精确过滤（自动规范化为大写）。
- **响应**：`200 OK`
  ```json
  {
    "data": [
      {
        "id": 100,
        "productCode": "PRD-FSH-001",
        "publicName": "舟山大黄鱼",
        "category": "FISH",
        "sourceType": "IMPORT",
        "baseUnitCode": "kg",
        "status": "ACTIVE",
        "version": 1
      }
    ],
    "meta": {
      "requestId": "01J...",
      "timestamp": "2026-09-07T16:00:00+08:00",
      "page": {
        "number": 1,
        "size": 20,
        "totalElements": 1,
        "totalPages": 1
      }
    }
  }
  ```

### 3. 乐观锁更新海产品 (`PATCH /api/v1/products/{id}`)
- **权限**：平台管理员（`PLATFORM` scope），需要 CSRF；
- **入参**：
  ```json
  {
    "publicName": "东海舟山大黄鱼",
    "status": "ACTIVE",
    "version": 0
  }
  ```
- **并发冲突**：若入参 `version` 与当前库内最新版本不一致，返回 **`409 VERSION_CONFLICT`**。

### 4. 创建温控规则草稿 (`POST /api/v1/products/{productId}/temperature-rules`)
- **权限**：平台管理员（`PLATFORM` scope），需要 CSRF；
- **入参**：
  ```json
  {
    "name": "东海大黄鱼冷链流通温控方案v1",
    "effectiveFrom": "2026-06-01T08:00:00+08:00",
    "effectiveTo": "2026-12-31T20:00:00+08:00",
    "basisNote": "依据 GB/T 32204-2015 水产品冷链物流规范",
    "stages": [
      {
        "stageCode": "PROCESSING",
        "lowerLimit": -35.00,
        "upperLimit": -30.00,
        "unitCode": "CELSIUS",
        "allowedDurationSeconds": 0,
        "sequenceNo": 1
      },
      {
        "stageCode": "STORAGE",
        "lowerLimit": -25.00,
        "upperLimit": -18.00,
        "unitCode": "CELSIUS",
        "allowedDurationSeconds": 600,
        "sequenceNo": 2
      }
    ]
  }
  ```
- **业务校验规则**：
  - `effectiveFrom` 必须有效；若提供 `effectiveTo` 则必须严格大于 `effectiveFrom`；支持任意合法带时区输入，服务端换算为 UTC 持久化；
  - `lowerLimit` 不得高于 `upperLimit`（违规返回 422 `TEMPERATURE_RULE_INVALID_LIMITS`）；
  - `unitCode` 仅支持 `CELSIUS`（违规返回 422 `INVALID_TEMPERATURE_UNIT`）；
  - 温度阈值整数最多 4 位，小数最多 2 位（违规返回 400 `INVALID_REQUEST`）；
  - `allowedDurationSeconds >= 0`、`sequenceNo >= 1`（违规返回 400 `INVALID_REQUEST`）；
  - 规则草稿创建成功后，初始状态为 `DRAFT`。

### 5. 发布温控规则 (`POST /api/v1/temperature-rules/{id}/publish`)
- **权限**：平台管理员（`PLATFORM` scope），需要 CSRF；
- **约束前置检查**：
  - 所属产品状态必须为 `ACTIVE`（草稿或停用产品返回 422 `PRODUCT_NOT_ACTIVE`）；
  - 规则自身必须处于 `DRAFT` 状态（重复发布返回 409 `INVALID_STATE_TRANSITION`）；
  - 待发布规则的半开区间 `[effectiveFrom, effectiveTo)` 不得与当前产品已发布的生效中规则重叠（冲突返回 422 `TEMPERATURE_RULE_EFFECTIVE_CONFLICT`）；
  - 发布更新返回 0 行时抛出 409 `INVALID_STATE_TRANSITION`；
- **发布结果**：成功将规则状态流转为 **`ACTIVE`**，通用乐观锁字段 `version` 从 0 原子递增为 1（响应及数据库持久状态同步递增），规则成为不可变基准。

### 6. 查询产品温控规则版本列表 (`GET /api/v1/products/{productId}/temperature-rules`)
- **权限**：所有已登录用户；
- **说明**：按版本序号（`versionNo`）升序列出该产品下的所有规则方案（包含草稿、生效中方案及其环节明细），白名单输出，杜绝泄露 `isDeleted`。企业端已登录用户可查看草稿规则但不授予修改权限。

---

## 五、测试分层策略与执行方法

### 1. 默认测试套件（无外置依赖，用于本地与 CI 快速门禁）

默认测试完全采用纯单元测试（MockitoExtension）与 Web 契约切片测试（WebMvcTest + MockMvc + MockitoBean），不依赖 Docker 或真实外部网络，可在无容器环境下极速运行。

| 测试类 | 覆盖范围与测试目标 |
|---|---|
| `ProductApplicationServiceTest` | 产品创建校验、代码查重冲突（409）、枚举大小写规范化、非 kg 单位拦截、分页查询过滤、乐观锁版本冲突（409）与行锁验证。 |
| `TemperatureRuleApplicationServiceTest` | 悲观行锁串行化版本生成、草稿状态校验（409）、环节空/重复/逆序阈值拦截（422）、半开区间防重叠判定、原子条件更新 0 行防并发冲突（409）。 |
| `ProductControllerTest` | 匿名 401、操作员写 403、平台管理员写 201、参数校验（400，含 `page=0` 与 `size=999` 分页边界、非 kg 单位）、响应封套与时间格式验证。 |
| `TemperatureRuleControllerTest` | 匿名 401、操作员只读可查（包含草稿规则）但写 403、参数校验（400，含 4 位整数 2 位小数超限、负缓冲秒数、非正序号）、带 `+08:00` 偏移的 ISO 8601 输入转 UTC 语义与带 `Z` 偏移响应契约测试。 |

**执行命令（需在 `server` 目录下执行）**：
```powershell
cd server
.\mvnw.cmd clean test
```

### 2. 真实 MySQL 8.4 集成测试（受条件环境变量控制）

测试类：`ProductRuleMysqlIntegrationTest`
- **控制条件**：受环境变量 `MYSQL_IT_ENABLED=true` 控制；若本地未启动 Docker 容器或未显式配置，则在默认构建中自动安全跳过（Skipped），不影响构建结果；
- **测试内容**：
  1. 真实会话与 CSRF 流程；
  2. 真实产品创建、分页检索与乐观锁版本控制；
  3. 温控规则草稿创建与行锁版本自增（v1、v2、v3）；
  4. 规则发布状态机流转与区间重叠排他拒绝；
  5. **Flyway V2 物理 CHECK 约束拦截能力验证**：绕过应用服务直插数据库，验证非法品类、非法单位（非 kg）、反向阈值（lowerLimit > upperLimit）、非法温标（非 CELSIUS）、负缓冲秒数和非正次序号均被 MySQL 8.4 物理约束精准拦截并抛出包含具体约束名的 `DataAccessException`（底层携带 MySQL 错误码 3819）；
  6. 数据清理：在 `@AfterEach` 中按依赖反向顺序执行物理 `DELETE FROM`，保持数据库纯净。

**在本地真实 MySQL 环境中运行该测试的方式**：
```powershell
# 1. 在仓库根目录启动本地 Docker 数据库（映射 3307 端口）
docker compose --env-file .env -f deploy/docker-compose.yml up -d --wait

# 2. 从根目录安全加载 .env 到当前 PowerShell 环境变量
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
        $parts = $line.Split('=', 2)
        [System.Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
}
$env:MYSQL_IT_ENABLED = "true"

# 3. 运行全量集成测试
cd server
.\mvnw.cmd clean test
```

---

# 第三部分：组织范围批次草稿生命周期交付规范 (Issue #11)

## 一、核心目标与职责边界

严格遵循 GitHub Issue #11 与整体架构设计，实现基础批次（Batch）草稿的创建、组织范围隔离分页查询、详情查看、增量更新及提交流转：

1. **业务范围收敛**：
   - 本切片仅实现基础批次的生命周期流转（`DRAFT -> ACTIVE`）；
   - **不实现**批次拆分、合并、加工（`batch_operation`）、不生成谱系边（`batch_relation`）、不记录追溯事件（`trace_event`）、不生成或暴露消费者公开追溯码（`public_trace_code`）、不处理业务冻结/召回/关闭。
2. **分层架构与控制反转**：
   - 严格遵循 `web -> application -> domain -> mapper` 四层设计；
   - 表现层 `BatchController` 严禁直接调用持久层 `BatchMapper`，所有跨表校验、权限检查、并发版本控制与幂等重试逻辑统一收敛于应用服务层 `BatchApplicationService`。
3. **安全与组织隔离边界**：
   - **写权限拦截**：创建草稿、更新草稿、提交激活强制要求企业操作员角色（`TraceSecurityPrincipal.roles` 必须包含 `OPERATOR`）；系统管理员按 RBAC 原则默认只读企业业务，无权代写，违者返回 **`403 ACCESS_DENIED`**；
   - **组织上下文唯一服务端推导**：`orgId`、操作人 `userId`、审计时间及初始 `DRAFT` 状态严格由服务端生成，绝对不信任客户端传递；
   - **读权限与数据范围隔离**：
     - `PLATFORM` 作用域角色可查询全平台批次；
     - 其余所有企业端已认证用户在列表查询时**必须在 Mapper/SQL 层强制限定 `org_id = principal.orgId`**，杜绝先查全表再在内存过滤；
     - 详情查询强制校验所有权：库内已存在但属于其他组织的批次，返回 **`403 ORG_SCOPE_DENIED`**；批次不存在返回 **`404 RESOURCE_NOT_FOUND`**；
   - **严格白名单数据投影**：响应仅暴露白名单业务属性，严禁泄露 `isDeleted` 逻辑删除标记及 `creationIdempotencyKey` 内部防重键；所有审计时间输出带明确 UTC 偏移的 ISO 8601 格式字符串。

---

## 二、领域模型与生命周期约束

### 1. 实体核心属性与规范

- **追溯批次主表 (Batch)**：
  - `id`：内部自增主键；
  - `orgId`（库字段 `org_id`）：当前持有企业组织 ID；
  - `productId`（库字段 `product_id`）：关联海产品主数据 ID（创建与提交时必须处于 `ACTIVE` 状态）；
  - `batchNo`（库字段 `batch_no`）：业务批次号，同组织内排他唯一（复合唯一索引 `uk_batch_org_no`），不同组织允许复用同一批次号；
  - `batchType`（库字段 `batch_type`）：批次环节枚举（`SOURCE`, `PROCESSING`, `DISTRIBUTION`, `SALE`）；
  - `quantity`（库字段 `quantity`）：批次初始/声明数量，可用量派生，`DECIMAL(18,3)`，必须 `> 0` 且最多保留 3 位小数；
  - `unitCode`（库字段 `unit_code`）：计量单位，Phase 1 严格限定为 `kg`，传入其他单位返回 400 `INVALID_REQUEST`；
  - `originType`（库字段 `origin_type`）：水产品来源枚举（规范大写代码：`DOMESTIC_CAPTURE`, `DOMESTIC_FARMED`, `IMPORT`）；
  - `originText`（库字段 `origin_text`）：企业端完整来源产地文本描述（最长 255 字符）；
  - `productionDate`（`production_date`）：生产加工日期（`LocalDate`，可选）；
  - `captureDate`（`capture_date`）：捕捞出塘日期（`LocalDate`，可选）；
  - `freezeDate`（`freeze_date`）：速冻完成日期（`LocalDate`，可选）；
  - `shelfLifeDays`（`shelf_life_days`）：保质期天数（可选，若提供必须 `> 0`）；
  - `status`（库字段 `status`）：批次生命周期状态（`DRAFT`, `ACTIVE`, `FROZEN`, `RECALLED`, `CLOSED`）；
  - `creationIdempotencyKey`（库字段 `creation_idempotency_key`）：创建防重幂等键（最长 128 字符，同组织唯一，响应 DTO 屏蔽）；
  - `version`：乐观锁版本号（草稿更新与提交成功时原子递增）；
  - `createdAt` / `updatedAt`：UTC 创建/更新时间。

### 2. 数据库物理级约束保障 (Flyway V3)

在 `V3__batch_constraints.sql` 中新增幂等键字段、同组织唯一索引及 MySQL 8.4 物理级 CHECK 约束：
- `uk_batch_org_idempotency`：同一组织下创建幂等键唯一 `(org_id, creation_idempotency_key)`；
- `chk_batch_batch_type`：限定批次类型枚举值域 `('SOURCE', 'PROCESSING', 'DISTRIBUTION', 'SALE')`；
- `chk_batch_origin_type`：限定来源枚举值域 `('DOMESTIC_CAPTURE', 'DOMESTIC_FARMED', 'IMPORT')`；
- `chk_batch_status`：限定批次状态值域 `('DRAFT', 'ACTIVE', 'FROZEN', 'RECALLED', 'CLOSED')`；
- `chk_batch_quantity`：限定数量必须严格大于 0 (`quantity > 0`)；
- `chk_batch_unit_code`：限定基础单位必须为 `kg` (`unit_code = 'kg'`)；
- `chk_batch_shelf_life_days`：限定保质期天数必须可空或大于 0 (`shelf_life_days IS NULL OR shelf_life_days > 0`)。

---

## 三、并发控制、幂等防重与安全隔离机制

```mermaid
stateDiagram-v2
    [*] --> DRAFT : OPERATOR 创建批次草稿 (校验产品 ACTIVE + 悲观行锁防TOCTOU + 幂等重放前置)
    DRAFT --> DRAFT : OPERATOR 增量更新草稿 (单条 SQL 约束 id + org_id + status='DRAFT' + version + 当前读精准诊断)
    DRAFT --> ACTIVE : OPERATOR 提交批次 (产品 ACTIVE 悲观行锁 + 单条 SQL 条件流转与版本自增 + 当前读诊断)
    ACTIVE --> [*] : 进入下游业务流转 (后续切片)
```

1. **创建防重幂等键机制与当前读（Locking Read）并发竞态恢复**：
   - 客户端在 `POST /api/v1/batches` 中强制携带 `Idempotency-Key` 请求头（长度限定为 16..128 字符）；
   - 服务端首先查询 `(org_id, creationIdempotencyKey)` 进行同组织幂等预检：
     - 若已存在且完整业务载荷相同（批号、产品、类型、数量、单位、来源、日期、保质期），直接返回原批次封套且不重复插入（即使产品在创建后变为 `INACTIVE` 仍正常重放）；
     - 若已存在但载荷语义不一致，立即拒绝并抛出 **`409 IDEMPOTENCY_CONFLICT`**；
   - **MySQL REPEATABLE READ 隔离级别当前读恢复机制**：在 MySQL 默认的 `REPEATABLE READ` 隔离级别下，事务内首次普通 `SELECT` 会建立 Read View 快照视图。若两个并发请求同时发起首次创建，另一个事务成功插入并提交后，本事务并发 `insert` 必然抛出 `DuplicateKeyException`。此时若使用普通快照读将无法看到另一事务已提交的记录，导致幂等重试误报为 `BATCH_NO_CONFLICT`。因此服务端必须采用当前锁定读 `SELECT ... FOR UPDATE`（`selectByOrgIdAndIdempotencyKeyForUpdate` 与 `selectByOrgIdAndBatchNoForUpdate`）穿透快照读盲区，精确获取存储引擎最新已提交行，正确返回原批次响应或判定真实批次号冲突。
2. **单条 SQL 条件更新与当前读版本/状态精准分类**：
   - 草稿更新与提交均执行单条原子更新 SQL：
     `UPDATE batch SET ... version = version + 1 WHERE id = #{id} AND org_id = #{orgId} AND status = 'DRAFT' AND version = #{version} AND is_deleted = 0`；
   - 检查返回值 `affectedRows`，若受影响行数为 0，同样通过当前锁定读 `selectByIdIgnoreTenantForUpdate(batchId)` 打破前置快照盲区，获取底层存储引擎最新记录，杜绝将并发状态变更（如已被并发提交为 ACTIVE）误分类为版本冲突（`VERSION_CONFLICT`），精准区分：
     - 批次不存在：抛出 **`404 RESOURCE_NOT_FOUND`**；
     - 跨组织越权：抛出 **`403 ORG_SCOPE_DENIED`**；
     - 状态非草稿（如并发已被提交变为 ACTIVE）：抛出 **`409 INVALID_STATE_TRANSITION`**；
     - 版本号不匹配（请求版本为旧值）：抛出 **`409 VERSION_CONFLICT`**。
3. **关联产品活性强校验与排他行锁（TOCTOU 竞态消除）**：
   - 创建批次（首次非重放）与提交激活批次前，均强制复核关联产品的最新状态；
   - 采用 `productMapper.selectByIdForUpdate(productId)` 对产品记录加排他悲观行锁，确保在批次创建落库或草稿激活提交流转事务完成前，关联产品不能被并发事务停用，彻底消除“检查时为 ACTIVE 但流转时已停用”的 TOCTOU（Time-of-Check to Time-of-Use）竞态；
   - 幂等命中重放路径绝不查询或锁定产品，保障高并发只读重试的高吞吐。
4. **软删除批号与幂等键复用边界说明**：
   - 软删除批号与幂等键复用策略属于未来删除/归档生命周期规范；本切片严格收敛于基础批次生命周期（`DRAFT -> ACTIVE`），系统不提供批次删除接口，保持数据库物理唯一索引 `(org_id, batch_no)` 与 `(org_id, creation_idempotency_key)` 约束稳定，不变更现有索引结构。

---

## 四、API 接口调用契约 (遵循 ISO 8601 与显式 UTC 偏移)

### 1. 创建批次草稿 (`POST /api/v1/batches`)
- **权限**：企业操作员（`OPERATOR` 角色），强制校验 CSRF；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **入参**：
  ```json
  {
    "batchNo": "BATCH-20260908-001",
    "productId": 100,
    "batchType": "SOURCE",
    "quantity": 100.500,
    "unitCode": "kg",
    "originType": "DOMESTIC_CAPTURE",
    "originText": "东海舟山渔场3号捕捞作业区",
    "productionDate": "2026-09-01",
    "captureDate": "2026-09-01",
    "freezeDate": "2026-09-02",
    "shelfLifeDays": 180
  }
  ```
- **响应**：`201 Created`
  ```json
  {
    "data": {
      "id": 1000,
      "orgId": 10,
      "productId": 100,
      "batchNo": "BATCH-20260908-001",
      "batchType": "SOURCE",
      "quantity": 100.500,
      "unitCode": "kg",
      "originType": "DOMESTIC_CAPTURE",
      "originText": "东海舟山渔场3号捕捞作业区",
      "productionDate": "2026-09-01",
      "captureDate": "2026-09-01",
      "freezeDate": "2026-09-02",
      "shelfLifeDays": 180,
      "status": "DRAFT",
      "version": 0,
      "createdAt": "2026-09-08T09:30:00Z",
      "createdBy": 101,
      "updatedAt": "2026-09-08T09:30:00Z",
      "updatedBy": 101
    },
    "meta": {
      "requestId": "01J...",
      "timestamp": "2026-09-08T17:30:00+08:00"
    }
  }
  ```

### 2. 分页查询批次列表 (`GET /api/v1/batches`)
- **权限**：所有已登录用户；
- **过滤参数**：`status`（状态精确过滤，支持 `DRAFT/ACTIVE/FROZEN/RECALLED/CLOSED`）、`page`（从 1 开始，默认 1）、`size`（1~100，默认 20）；
- **响应**：`200 OK`，包含 `meta.page` 分页元数据。

### 3. 获取批次详情 (`GET /api/v1/batches/{batchId}`)
- **权限**：所有已登录用户；本组织或 `PLATFORM` scope 可查；跨组织已存在返回 403 `ORG_SCOPE_DENIED`，不存在返回 404；
- **响应**：`200 OK`，白名单数据投影。

### 4. 增量更新批次草稿 (`PATCH /api/v1/batches/{batchId}`)
- **权限**：企业操作员（`OPERATOR` 角色），强制校验 CSRF；
- **入参**（仅允许修改数量、产地描述、日期与保质期天数）：
  ```json
  {
    "version": 0,
    "quantity": 120.000,
    "originText": "更新后的产地说明",
    "shelfLifeDays": 240
  }
  ```
- **响应**：`200 OK`，`version` 原子递增为 1。

### 5. 提交激活批次草稿 (`POST /api/v1/batches/{batchId}/submit`)
- **权限**：企业操作员（`OPERATOR` 角色），强制校验 CSRF；
- **入参**：
  ```json
  {
    "version": 1
  }
  ```
- **流转前置校验**：关联海产品必须处于 `ACTIVE` 状态；批次当前必须为 `DRAFT` 状态；版本号必须匹配；
- **响应**：`200 OK`，批次 `status` 流转为 `ACTIVE`，`version` 原子递增。

---

## 五、测试分层策略与执行方法

### 1. 默认测试套件（CI 快速门禁与离线快速验证）
采用纯单元测试（MockitoExtension）与 WebMvc 切片测试（WebMvcTest + MockMvc），不依赖外置容器：
- `BatchApplicationServiceTest`：覆盖合法创建、非 OPERATOR 拒绝（403）、缺失/短幂等键（400）、非法枚举/单位/数量/保质期（400）、关联产品不存在（404）与停用（422）、批次号冲突（409）、同幂等键重试与语义冲突（409）、并发 DuplicateKey 恢复、草稿更新版本自增、跨组织更新拒绝（403）、旧版本冲突（409）、非草稿更新与提交拒绝（409）、草稿提交为 ACTIVE、跨组织详情拒绝（403）与全平台/本组织分页隔离。
- `BatchControllerTest`：覆盖全接口匿名拦截（401）、写操作 CSRF 防护（403）、分页边界参数拦截（400）、缺少必填字段/版本号拦截（400）、白名单响应投影与带 UTC 偏移量的 ISO 8601 时间格式校验。

**执行命令（需在 `server` 目录下执行）**：
```powershell
cd server
.\mvnw.cmd clean test
```

### 2. 真实 MySQL 8.4 集成测试（受条件环境变量控制）
测试类：`BatchMysqlIntegrationTest`
- **控制条件**：仅在环境变量 `MYSQL_IT_ENABLED=true` 时激活；未配置时在默认构建中安全跳过（Skipped）；
- **物理清理**：注入 `JdbcTemplate`，在 `@AfterEach` 中先按 `org_id` 彻底物理清理测试组织下的全部 `batch`（涵盖 MockMvc 接口生成与直接 SQL 插入），再按依赖反向顺序清理 `product -> user_role -> app_user -> role -> organization`，确保测试成功或失败均不污染数据库；
- **测试内容**：
  1. 真实会话登录与 CSRF 流程；
  2. 双组织环境验证：组织 A 与组织 B 均可创建相同批次号，同组织内批次号排他冲突；
  3. 组织 A 列表查询与详情查询严格隔离，组织 B 跨组织访问组织 A 批次返回 403 `ORG_SCOPE_DENIED`；
  4. 关联停用产品创建批次返回 422 `PRODUCT_NOT_ACTIVE`；
  5. 幂等键重放与冲突检测：同组织同 key 且归一化载荷相同时返回原批次（即使关联产品在创建后变为 `INACTIVE` 仍成功重放原批次）；不同载荷优先返回 409 `IDEMPOTENCY_CONFLICT`；
  6. 平台管理员（`PLATFORM` scope）跨组织读列表，普通企业用户读隔离，平台非 `OPERATOR` 用户写操作拦截（403 `ACCESS_DENIED`）；
  7. 草稿增量更新版本自增与提交流转为 ACTIVE；
  8. **Flyway V3 物理 CHECK 约束拦截能力验证**：绕过应用层直插数据库，验证非法批次类型、非法来源类型、非法批次状态、数量小于等于 0、单位非 kg、保质期天数小于等于 0 以及重复幂等键均被 MySQL 8.4 物理约束精准拦截并抛出包含具体约束名（如 `chk_batch_batch_type`、`chk_batch_quantity`、`uk_batch_org_idempotency` 等）的 `DataAccessException`；
  9. **MySQL REPEATABLE READ 快照读盲区与当前读穿透证据**：在同一 `TRANSACTION_REPEATABLE_READ` 事务中，普通快照读无法看到并发事务后提交的记录，而当前锁定读（`SELECT ... FOR UPDATE`）能够穿透快照读盲区，直接读取到底层最新已提交行；
  10. **并发幂等双请求竞争验证**：两个并发线程携带相同幂等键同时发起创建请求，由数据库唯一索引和并发竞态恢复机制协同处理，只生成一行记录且两线程返回相同的批次 ID。

---

# 第四部分：批次操作、物料平衡与谱系边交付规范 (Issue #13)

## 一、核心目标与职责边界

严格遵循 GitHub Issue #13 与系统整体架构规范，实现批次操作草稿的创建、物料平衡计算、批次可用量与唯一来源强校验、有向图成环检测、两套独立幂等键以及谱系边的事务写入：

1. **业务边界隔离**：
   - 完整表达冷冻海产品的拆分（`SPLIT`）、合并（`MERGE`）、加工（`PROCESS`）与分装（`REPACK`）多对多业务拓扑，杜绝退化为固定线性流程；
   - **不实现**谱系查询 API（`GET /batches/{id}/genealogy`）、图谱 UI 交互、库存台账自动扣减、单位非 kg 的换算规则及草稿删除。
2. **安全与权限边界**：
   - 创建草稿与提交操作强制要求企业操作员角色（`TraceSecurityPrincipal.roles` 必须包含 `OPERATOR`），平台管理员及其他角色无权代写（返回 403 `ACCESS_DENIED`）；
   - 跨组织访问或操作其他企业批次返回 403 `ORG_SCOPE_DENIED`；
   - 响应严格执行白名单投影，绝不泄露逻辑删除标识 `isDeleted` 与内部幂等防重键。
3. **批次数量语义确立**：
   - `batch.quantity` 严格保持为批次的初始/声明数量；批次的当前可用量与消耗量统一由已提交操作明细累计派生。

---

## 二、领域模型与生命周期约束

### 1. 实体核心属性与规范

- **批次操作主表 (BatchOperation)**：
  - `id`：主键 ID；
  - `orgId`（`org_id`）：执行组织 ID（服务端推导）；
  - `operationNo`（`operation_no`）：业务流水单号（服务端自动生成）；
  - `operationType`（`operation_type`）：操作类型（`MERGE`, `SPLIT`, `PROCESS`, `REPACK`）；
  - `occurredAt`（`occurred_at`）：业务实际发生时间（UTC）；
  - `recordedAt`（`recorded_at`）：系统登记时间（UTC，服务端生成）；
  - `status`：生命周期状态（`DRAFT` 草稿，`SUBMITTED` 已提交，`CORRECTED` 已更正）；
  - `idempotencyKey`（`idempotency_key`）：创建防重幂等键（16..128 字符，同组织唯一）；
  - `submissionIdempotencyKey`（`submission_idempotency_key`）：提交防重幂等键（16..128 字符，同组织唯一）；
  - `note`：操作备注（最长 500 字符）；
  - `version`：乐观锁版本号（草稿提交时自增）。

- **批次操作明细项目表 (BatchOperationItem)**：
  - `id`：明细 ID；
  - `operationId`（`operation_id`）：关联操作 ID；
  - `batchId`（`batch_id`）：关联批次 ID（`INPUT`/`OUTPUT` 必须提供；`LOSS`/`WASTE`/`SAMPLE` 严禁提供）；
  - `role`：项目角色（`INPUT`, `OUTPUT`, `LOSS`, `WASTE`, `SAMPLE`）；
  - `quantity`：数量（必须 `> 0` 且最多 3 位小数）；
  - `unitCode`（`unit_code`）：计量单位（Phase 1 限定为 `kg`）；
  - `normalizedQuantity`（`normalized_quantity`）：折算基准单位数量（当前阶段恒等于 `quantity`）。

- **批次谱系图谱边表 (BatchRelation)**：
  - `id`：主键 ID；
  - `operationId`（`operation_id`）：引起关系的转换操作 ID；
  - `parentBatchId`（`parent_batch_id`）：原料父批次 ID；
  - `childBatchId`（`child_batch_id`）：产出子批次 ID；
  - `relationType`（`relation_type`）：关系类型（`MERGE`, `SPLIT`, `TRANSFORM`）；
  - `createdAt`（`created_at`）：创建时间（UTC）。

### 2. 数据库物理级约束保障 (Flyway V4)

在 `V4__batch_operation_constraints.sql` 中补充 MySQL 8.4 物理约束与复合唯一索引：
- `uk_op_org_idempotency`：同一组织下创建幂等键唯一 `(org_id, idempotency_key)`；
- `uk_op_org_submission_idempotency`：同一组织下提交幂等键唯一 `(org_id, submission_idempotency_key)`；
- `chk_op_type`：限定操作类型枚举 `('MERGE', 'SPLIT', 'PROCESS', 'REPACK')`；
- `chk_op_status`：限定操作状态枚举 `('DRAFT', 'SUBMITTED', 'CORRECTED')`；
- `chk_item_role`：限定明细角色枚举 `('INPUT', 'OUTPUT', 'LOSS', 'WASTE', 'SAMPLE')`；
- `chk_item_quantity`：限定明细数量严格大于 0；
- `chk_item_unit_code`：限定计量单位必须为 `kg`；
- `chk_item_normalized_quantity`：限定基准折算数量严格大于 0；
- `chk_relation_type`：限定关系类型枚举 `('TRANSFORM', 'SPLIT', 'MERGE')`；
- `chk_relation_no_self_loop`：限定父子批次不得自环 `(parent_batch_id <> child_batch_id)`。

---

## 三、物料平衡、环检测与并发安全机制

```mermaid
flowchart TD
    A[收到提交请求] --> B[校验提交幂等键与版本号]
    B --> C[锁定操作行 SELECT FOR UPDATE]
    C --> D[按 batchId 升序对批次排他加锁]
    D --> E[复核批次本组织归属与 ACTIVE 状态]
    E --> F[服务端独立重新计算物料平衡 0.001kg 容差]
    F --> G[校验输出批次无既有上游且数量等于声明量]
    G --> H[校验输入批次 历史累计+本次 <= 批次声明量]
    H --> I[INPUT x OUTPUT 生成关系边并查自环]
    I --> J[MySQL 8.4 recursive CTE 检测有向环]
    J --> K[原子更新操作状态为 SUBMITTED 并递增版本]
    K --> L[批量持久化谱系边至 batch_relation]
```

1. **物料平衡重新计算（Mass Balance）**：
   服务端独立累计计算：
   `sum(INPUT.normalizedQuantity) = sum(OUTPUT.normalizedQuantity) + sum(LOSS.normalizedQuantity) + sum(WASTE.normalizedQuantity) + sum(SAMPLE.normalizedQuantity)`
   容差设定为绝对值 `<= 0.001 kg`。不平衡立即抛出 422 `BATCH_MASS_BALANCE_VIOLATION`，事务完整回滚，不留痕迹。
2. **唯一产出来源与声明量一致性强校验**：
   - 输出批次在 `batch_relation` 中不能存在既有上游边，一个批次只能由一次已提交操作产出（违规报 422 `BATCH_OUTPUT_ALREADY_PRODUCED`）；
   - 输出明细数量必须与该批次声明数量一致（违规报 422 `BATCH_OUTPUT_QUANTITY_MISMATCH`）。
3. **输入累计量派生与不可超额占用校验**：
   - 统计该批次在所有状态为 `SUBMITTED` 的操作项目中的历史累计 INPUT 数量；
   - 要求 `历史已提交 INPUT + 本次 INPUT <= 批次声明数量`；超出报 422 `BATCH_QUANTITY_EXCEEDED`。
4. **MySQL 8.4 recursive CTE 成环检测**：
   - 对拟生成的每条 `parent -> child` 有向边，执行递归公用表表达式（CTE）遍历图谱；
   - 若从 `child` 出发沿着已提交下游边能到达 `parent`，则加入该边将形成环路，立即抛出 422 `BATCH_RELATION_CYCLE`。
5. **死锁防范与当前读恢复**：
   - 操作行先锁，涉及的批次按 `batchId` 严格数值升序排列逐行加锁；
   - 并发冲突通过锁定当前读 `SELECT ... FOR UPDATE` 穿透 `REPEATABLE READ` 快照读，实现精准幂等重放或状态分类。

---

## 四、API 接口调用契约

### 1. 创建批次操作草稿 (`POST /api/v1/batch-operations`)
- **权限**：企业操作员（`OPERATOR`），强制校验 CSRF；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **入参示例**：
  ```json
  {
    "operationType": "MERGE",
    "occurredAt": "2026-09-09T10:00:00+08:00",
    "note": "两产地扇贝原料合并清洗加工",
    "items": [
      { "role": "INPUT", "batchId": 101, "quantity": 600.000, "unitCode": "kg" },
      { "role": "INPUT", "batchId": 102, "quantity": 420.000, "unitCode": "kg" },
      { "role": "OUTPUT", "batchId": 103, "quantity": 480.000, "unitCode": "kg" },
      { "role": "OUTPUT", "batchId": 104, "quantity": 520.000, "unitCode": "kg" },
      { "role": "LOSS", "quantity": 20.000, "unitCode": "kg" }
    ]
  }
  ```
- **响应**：`201 Created`，返回包含系统生成流水号、时间、状态 `DRAFT` 及各项目的白名单封套。

### 2. 提交批次操作并生成谱系边 (`POST /api/v1/batch-operations/{operationId}/submit`)
- **权限**：企业操作员（`OPERATOR`），强制校验 CSRF；
- **请求头**：`Idempotency-Key: <16..128字符>`（独立提交防重键）；
- **入参示例**：
  ```json
  {
    "version": 0
  }
  ```
- **响应**：`200 OK`，返回状态 `SUBMITTED`、版本号递增至 1 的操作、明细以及生成的全部 `relations` 谱系边。

---

## 五、测试分层策略与执行方法

### 1. 默认测试套件（本地及 CI 离线门禁）
- `BatchOperationApplicationServiceTest`：纯业务逻辑单元测试（覆盖创建参数、基数约束、同键幂等重放与冲突诊断、物料平衡容差、输出批次已有上游拒绝、输入超额拒绝、自环拒绝、CTE 环检测拒绝、稳定顺序锁及提交流转）。
- `BatchOperationControllerTest`：WebMvc 切片契约测试（覆盖匿名 401、操作员权限与 CSRF 403、参数校验 400、白名单响应字段与 UTC 时间格式验证）。

### 2. 真实 MySQL 8.4 集成测试（环境变量控制）
- 测试类：`BatchOperationMysqlIntegrationTest`（受 `MYSQL_IT_ENABLED=true` 控制）
- 覆盖端到端标准链路（600kg+420kg -> 480kg+520kg+20kg损耗）、物料不平衡完整回滚、多跳环路检测回滚、双并发提交幂等恢复以及 Flyway V4 物理 CHECK 约束精准拦截验证。

---

# 第五部分：追溯事件与更正工作流交付规范 (Trace Events & Correction Workflow)

本文档记录冷冻海产品溯源系统 Phase 3（GitHub Issue #15 “feat: implement append-only trace events and correction workflow”）的接口契约、安全设计、状态流转控制、并发加锁与当前读恢复机制，以及测试分层策略。

---

## 一、核心目标与不可篡改设计

1. **追加式记录（Append-Only）原则**：
   - 追溯事件作为海产品供应链各环节真实发生的证据，持久化后严禁物理删除或直接原地修改；
   - 所有发现的记录错误、参数校准或审计补充，必须通过**链式追加更正记录**的方式进行修正；
   - 原事件被更正后，其状态原子流转为 `CORRECTED`，新记录标记为 `SUBMITTED` 并显式关联 `correctsEventId` 与 `correctionReason`；
   - 历史旧事件保持可追溯、可审计，完整重现事件演变轨迹。
2. **防分叉链式更正（Anti-Forking Corrections）**：
   - 每条追溯事件在生命周期内**最多只能被更正一次**；
   - 链式更正仅允许对当前最新生效版本（状态为 `SUBMITTED`）发起，对已被更正的事件（`CORRECTED`）再次发起更正直接拒绝（`409 EVENT_ALREADY_CORRECTED`）；
   - 数据库底层通过 `uk_trace_event_corrects` 唯一索引对 `corrects_event_id` 提供硬件级防分叉兜底。
3. **独立双时间维度**：
   - `occurredAt`：业务实际发生时间（由客户端设备/操作员上报，带时区偏移）；
   - `recordedAt`：系统接收并持久化的审计登记时间（由服务端依据 UTC 统一生成，只读属性）；
   - 两者独立持久化并在响应中以 ISO 8601 毫秒精度 UTC 时间格式输出。
4. **严格数据隔离与白名单投影**：
   - 仅允许企业操作员（`OPERATOR`）写入同组织批次的事件（跨组织操作返回 `403 ORG_SCOPE_DENIED`，非操作员返回 `403 ACCESS_DENIED`，平台角色禁止写企业事件）；
   - 响应 DTO 严格过滤 `isDeleted`、`idempotencyKey`、乐观锁等内部敏感字段。
5. **数据来源类型与边界约束 (`DataSource`)**：
   - `MANUAL`：企业操作员通过前端交互界面进行的人工填报；
   - `IMPORT`：企业内部/外部系统批量或文件导入数据；
   - `SIMULATED`：**教学演练与仿真模拟数据**（专用于高校教学实训推演、数字孪生测试与原型演练，**绝对不能描述为真实物联网数据**）；
   - `DEVICE`：**来源类型标准化保留值**（仅作为数据模型对未来物联网扩展的规范保留，**不代表本项目当前阶段已接入真实硬件设备或真实物联网感知基础设施**）。

---

## 二、批次状态矩阵与校验规则

追溯事件的录入与更正严格受所属批次生命周期状态控制：

| 批次状态 (`Batch.status`) | 创建普通事件 (`POST .../events`) | 提交链式更正 (`POST .../corrections`) | 业务设计考量 |
|:---|:---|:---|:---|
| `DRAFT` (草稿) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | 草稿批次尚未激活，不允许录入正式流通追溯数据 |
| `ACTIVE` (激活生效) | ✅ 允许 | ✅ 允许 | 正常流通中批次，全面接受事件上报与更正 |
| `FROZEN` (质量冻结) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | 冻结批次处于质量排查状态，阻断一切流转写入 |
| `RECALLED` (已召回) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | 批次已召回，物理流转终结，禁止写入新事件与更正 |
| `CLOSED` (已归档关闭) | ❌ 拒绝 (`422 BATCH_FLOW_BLOCKED`) | ✅ **允许** (`201 Created`) | 批次已完成生命周期归档，禁止产生新业务事件；但合规审计允许对历史记录做补充更正 |

---

## 三、受控扩展属性 `detailsJson` 规范

为防止非结构化 JSON 滥用导致系统性能退化或存储拒绝服务，`detailsJson` 遵循以下严密约束：
1. **单层结构**：必须为平铺单层 JSON 对象，严禁嵌套对象或数组；
2. **属性基数**：属性数量最多不得超过 20 个；
3. **键名格式**：键名必须符合小驼峰命名正则 `^[a-z][A-Za-z0-9]{0,63}$`；
4. **敏感与核心键禁用**：严禁占用核心领域字段名（如 `batchId`, `orgId`, `id`, `status`, `eventType`, `occurredAt`, `recordedAt`, `version`, `isDeleted` 等）；
5. **值类型受限**：值仅允许基本标量（字符串、数值、布尔值或 null）；字符串长度单属性不得超过 500 个字符；
6. **存储总容量上限**：整个 `detailsJson` 序列化后的 UTF-8 字节数不得超过 8 KiB。

---

## 四、并发安全与当前读恢复机制

### 1. 写路径批次排他锁消除 TOCTOU
在事件录入和更正处理前，首先通过 `batchMapper.selectByIdIgnoreTenantForUpdate(batchId)` 锁定批次行。
彻底规避“读批次为 ACTIVE -> 外部并发将批次冻结/关闭 -> 继续写入事件”的 TOCTOU 竞态漏洞。

### 2. 目标事件排他锁与同 Key 唤醒当前读恢复
在更正工作流中，两个携带相同 `Idempotency-Key` 的并发更正请求会发生行锁竞争：
- 事务 A 先获得目标事件行锁，完成插入并将目标事件状态更新为 `CORRECTED` 后提交；
- 事务 B 阻塞在目标事件行锁上。当事务 B 获得锁被唤醒时，若仅读取目标事件，会发现其已是 `CORRECTED` 状态；
- 若直接进入状态判断，事务 B 会错误抛出 `409 EVENT_ALREADY_CORRECTED`；
- **当前读恢复设计**：事务 B 在获取锁之后、检查状态之前，立即执行 `traceEventMapper.selectByOrgIdAndIdempotencyKeyForUpdate(orgId, idempotencyKey)`；
- 此时利用 `FOR UPDATE` 当前锁定读穿透 MySQL `REPEATABLE READ` 的快照读盲区，直接读取到事务 A 已提交的更正行；
- 若载荷一致，安全重放返回事务 A 的更正记录，完美实现并发幂等恢复。

### 3. 三重安全条件 CAS 原子更新
更新原事件状态采用高安全条件 SQL：
```sql
UPDATE trace_event
SET status = 'CORRECTED', version = version + 1, updated_at = #{updatedAt}, updated_by = #{updatedBy}
WHERE id = #{id} AND batch_id = #{batchId} AND org_id = #{orgId} AND status = 'SUBMITTED' AND is_deleted = 0
```
联合校验 `id`、`batchId`、`orgId` 和原状态 `SUBMITTED`，受影响行数为 0 时立即抛出 409 异常并触发事务回滚。

---

## 五、数据库底层约束保障 (Flyway V5)

在 MySQL 8.4 LTS 环境下，Flyway V5 脚本应用了以下物理级约束：
1. **唯一约束**：
   - `uk_trace_event_org_idempotency` (`org_id`, `idempotency_key`)：租户级幂等防重键；
   - `uk_trace_event_corrects` (`corrects_event_id`)：防分叉更正唯一索引；
   - `idx_trace_event_org_batch_time` (`org_id`, `batch_id`, `occurred_at`, `recorded_at`, `id`)：高效且排序稳定的复合索引。
2. **物理 CHECK 约束**：
   - `chk_trace_event_type`：限制 10 种枚举值；
   - `chk_trace_event_data_source`：限制 `MANUAL`, `IMPORT`, `SIMULATED`, `DEVICE`（注：`SIMULATED` 为教学仿真模拟数据，`DEVICE` 仅为来源类型保留值，不代表本项目已接入真实物联网设备）；
   - `chk_trace_event_status`：限制 `SUBMITTED`, `CORRECTED`；
   - `chk_trace_event_correction_shape`：`corrects_event_id` 与 `correction_reason` 必须同时为 NULL 或同时非 NULL；
   - *注意*：MySQL 8.4 语法明确禁止 CHECK 约束引用 AUTO_INCREMENT 列，防自更正由应用层与自增序列天然隔离保证。

---

## 六、API 接口调用契约

### 1. 查询批次追溯事件列表 (`GET /api/v1/batches/{batchId}/events`)
- **权限**：同组织用户（`OPERATOR`, `MANAGER`, `VIEWER`）或全平台角色，支持 CSRF 保护下的安全 GET 请求；
- **响应**：`200 OK`，按 `occurredAt ASC, recordedAt ASC, id ASC` 排序的完整事件数组（包含已更正的历史事件与生效事件）。

### 2. 录入追溯事件 (`POST /api/v1/batches/{batchId}/events`)
- **权限**：同组织企业操作员（`OPERATOR`，平台角色禁止录入）；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **入参示例**：
  ```json
  {
    "eventType": "PROCESS",
    "occurredAt": "2026-09-09T10:00:00+08:00",
    "siteId": 12,
    "dataSource": "MANUAL",
    "summary": "车间去头去内脏粗加工并速冻",
    "detailsJson": {
      "temperature": -18.5,
      "equipmentId": "EQ-009"
    }
  }
  ```
- **响应**：`201 Created`，返回包含独立 `occurredAt`、`recordedAt`、状态 `SUBMITTED` 的事件对象。

### 3. 提交追加式更正记录 (`POST /api/v1/batches/{batchId}/events/{eventId}/corrections`)
- **权限**：同组织企业操作员（`OPERATOR`，平台角色禁止更正）；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **入参示例**：
  ```json
  {
    "eventType": "PROCESS",
    "occurredAt": "2026-09-09T10:00:00+08:00",
    "siteId": 12,
    "dataSource": "MANUAL",
    "summary": "车间去头去内脏粗加工并深度速冻",
    "detailsJson": {
      "temperature": -22.0,
      "equipmentId": "EQ-009"
    },
    "correctionReason": "温度巡检传感器零点误差校准"
  }
  ```
- **响应**：`201 Created`，返回新更正事件，`correctsEventId` 指向目标事件，目标事件原子更新为 `CORRECTED`。

### 4. 不可篡改性与方法排他保护 (`PUT / PATCH / DELETE /api/v1/batches/{batchId}/events/{eventId}`)
- **行为**：尝试通过 `PUT`、`PATCH` 就地修改已录入事件或尝试 `DELETE` 物理删除事件；
- **响应**：统一返回 `405 Method Not Allowed`，并确保底层数据库数据、版本与状态完全不受影响，刚性捍卫 Append-only 不可变设计。

---

## 七、测试分层策略与执行方法

### 1. 默认测试套件（本地及 CI 离线门禁）
- `TraceEventApplicationServiceTest` (29 tests)：纯业务逻辑单元测试，覆盖 10 枚举白名单、受控 detailsJson 边界（基数、小驼峰正则、核心词禁用、禁止嵌套、标量类型受限、字符串长度超限、序列化体积上限）、场所归属与状态校验、DRAFT 拦截、CLOSED 批次允许更正、平台角色写入/更正拦截（403 ACCESS_DENIED）、非 OPERATOR 角色拦截、并发锁后同 key 唤醒恢复、异 key 语义冲突、捕获 DuplicateKeyException 后防分叉当前锁定读 (`selectByCorrectsEventIdForUpdate`) 诊断等；
- `TraceEventControllerTest` (13 tests)：WebMvc 切片测试，覆盖权限矩阵拦截（非 OPERATOR 报 403、跨组织报 403、平台角色写操作与更正拦截 403）、参数校验（Header 缺少/非法长度 400、字段错误 400）、双时间输出格式与内部敏感字段白名单过滤。

### 2. 真实 MySQL 8.4 集成测试（环境变量控制）
- 测试类：`TraceEventMysqlIntegrationTest`（受 `MYSQL_IT_ENABLED=true` 控制，9 tests）；
- 覆盖范围：
  1. `testTraceEventLifecycle_EndToEnd_WithOrgIsolationAndIdempotency`：端到端全流程链路、DRAFT 状态阻断、停用场所/跨组织场所拦截、正常录入、同 key 幂等重放、跨组织隔离、链式更正流转、数据库物理状态核验、防分叉拦截、更正重试在状态检查前识别以及 CLOSED 归档批次合规更正；
  2. `testConcurrentSameKeyCorrection_IdempotentRecovery`：真实高并发场景下两个事务携带相同幂等键同时更正，通过目标事件行排他锁与锁后当前读重查，只生成单条更正记录并稳定返回相同更正 ID；
  3. `testConcurrentDifferentKeyCorrection_ConflictRejection`：真实高并发场景下两个事务携带不同幂等键同时更正，后获锁事务被目标状态已变更精准拦截（409 EVENT_ALREADY_CORRECTED），杜绝数据分叉；
  4. `testRepeatableRead_SnapshotBlindSpot_And_ForUpdateCurrentRead`：真实 MySQL 默认 REPEATABLE READ 快照读盲区复现，以及通过 `FOR UPDATE` 当前锁定读穿透 Read View 盲区实现可靠读写恢复的完整实证；
  5. `testFlywayV5_PhysicalCheckConstraints_EnforcedByDatabase`：Flyway V5 全部 4 项物理 CHECK 约束（枚举类型、数据来源、事件状态、更正形状成对性）与防分叉唯一索引在真实 MySQL 8.4 底层生效拦截脏数据证据；
  6. `testAccessControl_NonOperatorAndCrossOrg_Forbidden`：端到端权限矩阵拦截（非 OPERATOR 拦截 403、平台用户拦截 403、跨组织创建/更正拦截 403），以及 **Append-only 不可变性反例：PUT 覆盖修改返回 405 Method Not Allowed、DELETE 物理删除返回 405 Method Not Allowed，数据库物理核对事件记录依然存在且核心字段、版本与状态保持不变**；
  7. `testCorrectionWorkflow_AtomicRollback_WhenOldVersionUpdateFails`：**真实数据库事务原子性回滚实证**（通过注入临时 CHECK 约束模拟新版本已插入但旧版本更新失败，验证 Spring 声明式事务完整回滚，新版本物理撤销，旧版本状态保持 SUBMITTED，并在 finally 严格物理移除临时约束恢复 schema）；
  8. `testEventListOrdering_IdenticalTimestamps_StrictlyOrderedByIdAsc`：至少三条事件具有完全相同 `occurred_at` 与 `recorded_at` 时，`GET /api/v1/batches/{batchId}/events` 严格按 `id ASC` 递增确定性稳定排序；
  9. `testDataSourceSimulated_And_RecordedAtAfterOccurredAt`：`SIMULATED` 数据来源真实通过 API 写入 MySQL 并原样存储与回显，解析响应时间明确断言 `recordedAt` 严格晚于历史 `occurredAt`，双时间体系严谨区分。

---

# 第六部分：公开追溯码与消费者端投影研发文档 (当前分支 `feat/17-public-trace-consumer` 正在实现 / 待合并验收)

本文档记录冷冻海产品溯源系统 Phase 3（GitHub Issue #17 “feat: generate public trace codes and expose consumer trace projection”，当前分支正在实现中、待合并验收）的接口调用契约、安全设计机制、并发控制与数据库约束规范。

---

## 一、核心目标与安全边界

1. **企业端与消费者端权限严格物理分流**：
   - **企业端管理端点**：
     - `POST /api/v1/batches/{batchId}/public-trace-code/activate`
     - `POST /api/v1/batches/{batchId}/public-trace-code/disable`
     - 强制受 Spring Security 会话认证与 CSRF 校验保护，仅限批次同组织的 `OPERATOR` 角色操作，必须携带合规的 `Idempotency-Key` 请求头（16..128 字符）。平台管理员 (`PLATFORM`) 与只读查看员 (`VIEWER`) 严格拦截（403 `ACCESS_DENIED`）。
   - **消费者端公开查询端点**：
     - `GET /api/public/v1/public/traces/{publicTraceId}`
     - 唯一匿名放行的公开 GET 端点。Spring Security 精确放行 `GET /api/public/v1/public/traces/*`，同路径下的非 GET 请求落入认证与 CSRF 拦截（返回 401/403），杜绝 CSRF 绕过风险。
2. **全链路 SQL 组织隔离与防止实体窥探**：
   - 企业端激活与停用路径在执行行级锁时，强制显式携带 `org_id`：两条路径先通过 `selectByIdAndOrgIdForUpdate` 锁定所属批次；停用路径及激活的唯一键竞争恢复路径再通过 `selectByBatchIdAndOrgIdForUpdate` 当前读锁定公开码。严禁使用 `selectByIdIgnoreTenantForUpdate` 窥探他组织实体；
   - 仅当本组织未命中时，通过标量查询 `selectOrgIdByIdIgnoreTenant` 获取所属组织 ID 判断是 404（不存在）还是 403 `ORG_SCOPE_DENIED`（越权），绝不将他组织完整实体加载至内存。
3. **统一幂等表绑定与语义指纹设计**：
   - Flyway V6 新增统一幂等记录表 `public_trace_code_idempotency`，以 `(org_id, idempotency_key)` 为物理唯一约束；
   - **统一持久绑定规则**：每一次成功返回（包括不同 key 命中已有 ACTIVE 资源、不同 key 命中已 DISABLED 终态资源），均在同一事务内将该 key 绑定至统一幂等表；
   - 相同 key 跨批次或跨动作调用，统一抛出 **`409 IDEMPOTENCY_KEY_REUSED`**；
   - 并发同 key 竞争通过排他锁当前读恢复并校验语义，并发不同 key 激活同一批次通过物理唯一索引 `uk_public_trace_code_batch` 保证只生成一条码并锁定当前读恢复。
4. **公开追溯码生成与内部指纹**：
   - 使用 `SecureRandom` 随机生成 26 位大写 RFC 4648 Base32 编码（字符集 `^[A-Z2-7]{26}$`），天然消除易混淆字符；
   - 内部索引存储：数据库表 `public_trace_code` 保存 `token_hash = SHA-256(public_id)` 索引列，仅作内部查询索引，对外 API 契约与序列化绝不暴露 `token_hash`；
   - 一批一码原则：同一批次在全生命周期内有且仅有一条公开追溯码资源，由物理唯一索引 `uk_public_trace_code_batch` 刚性约束。
5. **停用终态不可逆与不可探测性**：
   - 公开追溯码一旦停用（`status = 'DISABLED'`），进入不可逆终态，严禁重新激活或轮换；
   - 未知码、格式非法码（未通过 `^[A-Z2-7]{26}$` 正则直接在服务层阻断且不查 Mapper）与已停用码在消费者端表现严格一致，统一返回 `404 PUBLIC_TRACE_NOT_FOUND`（状态码、错误码、title 与 detail 完全相同），杜绝探测码的存在性。
6. **消费者端严格白名单安全投影与敏感自由文本隔离**：
   - 响应格式仅包含白名单安全字段：
     - `publicTraceId`：26 位公开标识；
     - `product`：产品公共展示信息（`name`, `category`, `specification`）；
     - `batch`：脱敏后的批次特征（`publicBatchNo` 如 `BAT****001`、`originType`、`maskedOrigin` 如 `东海****业区`、`productionDate`）；
     - `timeline`：仅包含当前批次所有生效中的 `SUBMITTED` 事件（已更正版本被排除），按 `occurred_at ASC, recorded_at ASC, id ASC` 严格稳定递增排序；
     - **公开 `event` 仅为受控业务类型标准标签（如“原料采收/出塘”），严禁拼接内部自由文本 `summary` 或泄露任何机密哨兵字符串**；
     - `temperatureSummary`：当前冷链温度结论，因 Phase 3 尚未接入 IoT 硬件与实时温度巡检，如实返回 `result: "INSUFFICIENT_DATA"`，并附带诚实说明；
     - `batchStatus`：当前批次状态（`ACTIVE`, `FROZEN`, `RECALLED`, `CLOSED`）；
     - `recallNotice`：若批次状态为 `RECALLED`，输出显式模拟召回声明（包含明确的“模拟召回演练”字样）；若非召回状态则为 null；
     - `disclosure`：公开声明，严正提示本项目所包含的模拟数据与合规用途。
   - **严格禁止词典检查**：消费者投影绝不包含内部主键 ID、真实批次号 raw `batch_no`、真实产地文本 raw `origin_text`、`token_hash`、`detailsJson` 内部扩展、`is_deleted`、以及审计字段（`created_by`, `updated_by` 等）。

---

## 二、接口调用契约与交互时序

```mermaid
sequenceDiagram
    autonumber
    actor Op as 企业操作员 (OPERATOR)
    actor Consumer as 消费者 (匿名游客)
    participant Sec as Spring Security
    participant Ctrl as 表现层 Controller
    participant Svc as PublicTraceApplicationService
    participant DB as MySQL 8.4 (InnoDB RR)

    Note over Op,DB: 1. 企业端激活批次公开追溯码
    Op->>Sec: POST /api/v1/batches/{batchId}/public-trace-code/activate (Header: Idempotency-Key, Cookie, CSRF)
    Sec->>Ctrl: 鉴权通过 (同组织 OPERATOR)
    Ctrl->>Svc: activatePublicTraceCode(batchId, idempotencyKey, principal)
    Svc->>DB: SELECT * FROM public_trace_code_idempotency WHERE org_id = ? AND idempotency_key = ?
    Svc->>DB: SELECT * FROM batch WHERE id = ? AND org_id = ? AND is_deleted = 0 FOR UPDATE
    Svc->>DB: SELECT * FROM public_trace_code WHERE batch_id = ? AND org_id = ?
    alt 批次尚未激活
        Svc->>DB: INSERT INTO public_trace_code (26位 Base32, SHA-256 hash, ACTIVE)
    else 批次已处于 ACTIVE 状态
        Note over Svc,DB: 语义等价调用，复用已有码
    end
    Svc->>DB: INSERT INTO public_trace_code_idempotency (org_id, idempotency_key, action, batch_id, ...)
    Svc-->>Op: 200 OK (PublicTraceCodeResponse: publicId, status: ACTIVE)

    Note over Consumer,DB: 2. 消费者匿名查询公开追溯信息
    Consumer->>Sec: GET /api/public/v1/public/traces/{publicTraceId}
    Sec->>Ctrl: 匿名放行 (免认证、免 CSRF)
    Ctrl->>Svc: getPublicTrace(publicTraceId)
    Note over Svc: 校验正则 ^[A-Z2-7]{26}$，非法直接 404 且不查 DB
    Svc->>DB: SELECT * FROM public_trace_code WHERE public_id = ? AND is_deleted = 0
    alt 未找到或已停用 (status = 'DISABLED')
        Svc-->>Consumer: 404 PUBLIC_TRACE_NOT_FOUND (结构与信息完全一致)
    else 状态为 ACTIVE
        Svc->>DB: 查询批次、产品与生效事件 (SUBMITTED, 按 occurred_at ASC, recorded_at ASC, id ASC)
        Svc-->>Consumer: 200 OK (PublicTraceProjectionResponse: 白名单脱敏投影)
    end

    Note over Op,DB: 3. 企业端停用追溯码 (终态不可逆)
    Op->>Sec: POST /api/v1/batches/{batchId}/public-trace-code/disable (Header: Idempotency-Key, Cookie, CSRF)
    Sec->>Ctrl: 鉴权通过
    Ctrl->>Svc: disablePublicTraceCode(batchId, idempotencyKey, principal)
    Svc->>DB: SELECT * FROM batch WHERE id = ? AND org_id = ? FOR UPDATE
    Svc->>DB: UPDATE public_trace_code SET status='DISABLED', disabled_at=NOW(6) WHERE id=? AND org_id=? AND status='ACTIVE'
    Svc->>DB: INSERT INTO public_trace_code_idempotency (org_id, idempotency_key, action, batch_id, ...)
    Svc-->>Op: 200 OK (status: DISABLED)
```

### 1. 激活批次公开追溯码 (`POST /api/v1/batches/{batchId}/public-trace-code/activate`)
- **权限**：同组织企业操作员 (`OPERATOR`)，平台管理员与非操作员报 `403 ACCESS_DENIED`；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **校验规则**：
  - 仅允许批次当前状态为 `ACTIVE` 时首次激活；处于 `DRAFT`、`FROZEN`、`RECALLED`、`CLOSED` 的批次禁止首次激活，返回 `422 BATCH_FLOW_BLOCKED`；
  - 若已处于 `DISABLED` 终态，再次调用激活返回 `409 INVALID_STATE_TRANSITION`；
  - 若已处于 `ACTIVE` 状态，不同 key 再次调用属于语义等价调用，返回 200 原码并在当前事务内持久绑定该 key；
  - 同 key 跨批次或跨动作调用报 `409 IDEMPOTENCY_KEY_REUSED`。
- **响应示例**：
  ```json
  {
    "data": {
      "id": 1,
      "batchId": 100,
      "publicId": "WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A",
      "status": "ACTIVE",
      "activatedAt": "2026-09-10T08:00:00Z",
      "disabledAt": null,
      "createdAt": "2026-09-10T08:00:00Z",
      "updatedAt": "2026-09-10T08:00:00Z"
    },
    "meta": {
      "requestId": "<req-id>",
      "timestamp": "2026-09-10T08:00:00Z"
    }
  }
  ```

### 2. 停用批次公开追溯码 (`POST /api/v1/batches/{batchId}/public-trace-code/disable`)
- **权限**：同组织企业操作员 (`OPERATOR`)；
- **请求头**：`Idempotency-Key: <16..128字符>`；
- **行为**：
  - 将追溯码状态原子更新为 `DISABLED`，记录 `disabled_at` 和内部 `updated_by` 审计（数据库无 `disabledBy` 冗余列）；
  - 停用为终态操作，不可逆，不可重新启用；
  - 任何成功返回（包括针对已处于 DISABLED 终态码的调用）均持久绑定其幂等键；
  - 事务具备严格原子性，若更新失败则完整回滚（码保持 ACTIVE、disabled_at 为空、幂等表无残留）。
- **响应示例**：
  ```json
  {
    "data": {
      "id": 1,
      "batchId": 100,
      "publicId": "WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A",
      "status": "DISABLED",
      "activatedAt": "2026-09-10T08:00:00Z",
      "disabledAt": "2026-09-10T09:00:00Z",
      "createdAt": "2026-09-10T08:00:00Z",
      "updatedAt": "2026-09-10T09:00:00Z"
    },
    "meta": {
      "requestId": "<req-id>",
      "timestamp": "2026-09-10T09:00:00Z"
    }
  }
  ```

### 3. 消费者公开追溯查询 (`GET /api/public/v1/public/traces/{publicTraceId}`)
- **权限**：匿名公开，免认证免 CSRF；
- **入参**：`publicTraceId` (26 位大写 RFC 4648 Base32 编码，匹配正则 `^[A-Z2-7]{26}$`)；
- **格式非法、未命中或停用返回**：`404 PUBLIC_TRACE_NOT_FOUND`（对外不可探测）；
- **命中返回示例**：
  ```json
  {
    "data": {
      "publicTraceId": "WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A",
      "product": {
        "name": "东海野生大黄鱼",
        "category": "FISH",
        "specification": "500g-600g/条"
      },
      "batch": {
        "publicBatchNo": "BAT****001",
        "originType": "DOMESTIC_CAPTURE",
        "maskedOrigin": "东海****业区",
        "productionDate": "2026-09-01"
      },
      "timeline": [
        {
          "event": "原料采收/出塘",
          "occurredAt": "2026-09-01T08:00:00Z",
          "dataSourceLabel": "教学演练与仿真模拟数据（SIMULATED）"
        }
      ],
      "temperatureSummary": {
        "result": "INSUFFICIENT_DATA",
        "ruleNote": "当前切片尚未接入冷链实时温控采集流，冷链温度状态暂不具备判定条件，请以企业实际出厂单据为准。"
      },
      "batchStatus": "ACTIVE",
      "recallNotice": null,
      "queriedAt": "2026-09-10T09:30:00Z",
      "disclosure": "本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。"
    },
    "meta": {
      "requestId": "<req-id>",
      "timestamp": "2026-09-10T09:30:00Z"
    }
  }
  ```

---

## 三、并发控制与统一幂等表设计

1. **统一幂等表 `public_trace_code_idempotency`**：
   - 字段包含 `org_id`, `idempotency_key`, `action`, `batch_id`, `public_trace_code_id`, `request_hash`, `created_at`；
   - 物理唯一索引：`uk_ptc_idem_org_key (org_id, idempotency_key)`；
   - 任何成功返回均在当前事务内持久绑定该 key，确保同一组织下每个 key 稳定绑定唯一的 action 与 batch；
   - 跨批次或跨动作复用 key 抛出 **`409 IDEMPOTENCY_KEY_REUSED`**。
2. **并发竞争与锁定当前读恢复**：
   - 激活前先对批次执行带 `org_id` 的 `SELECT ... FOR UPDATE` 锁定读，再在该批次锁保护下以带 `org_id` 的普通查询检查已有公开码；避免不存在公开码时对唯一索引间隙加锁而放大并发死锁风险；
   - 若并发多事务携带不同 key 激活同一批次，底层唯一约束 `uk_public_trace_code_batch` 确保仅一条成功插入，竞争失败方捕获 `DuplicateKeyException` 后通过排他锁定当前读穿透快照盲区恢复并持久绑定自身 key；
   - 若并发多事务携带相同 key 竞争，底层统一幂等表唯一索引保证单条插入，竞争方通过 `selectByOrgIdAndKeyForUpdate` 当前锁定读安全恢复。

---

## 四、Flyway V6 数据库级物理约束

Flyway 迁移脚本 `V6__public_trace_code_constraints.sql` 在 MySQL 8.4 LTS 物理层面施加了以下保障：
1. **安全三步平滑迁移 `org_id`**：
   - 先添加 nullable `org_id` 列，再通过 JOIN `batch` 表按 `batch_id` 回填存量租户归属（严禁使用 0 或猜测默认值填充），最后收紧为 `NOT NULL`（孤儿数据收紧明确报错失败）；
2. **公开追溯码物理唯一约束与组织复合索引**：
   - `uk_public_trace_code_batch (batch_id)`：物理级一批一码保证；
   - `idx_trace_code_org_batch (org_id, batch_id)` 与 `idx_trace_code_org_status (org_id, status)`：组织租户隔离复合索引；
   - 注：`public_id` 与 `token_hash` 的全局物理唯一索引由 V1 初始架构原生提供（`uk_trace_public_id` 与 `uk_trace_token_hash`），V6 绝不虚构同名或冗余索引；
3. **物理 CHECK 约束**：
   - `chk_public_trace_code_status`：约束 `status IN ('ACTIVE', 'DISABLED', 'RECALLED')`，与业务及 OpenAPI 契约保持完全一致；
   - `chk_public_trace_code_disable_shape`：状态形状校验，`status = 'ACTIVE'` 时 `disabled_at` 必须为 NULL；`status = 'DISABLED'` 时 `disabled_at` 必须非 NULL；`status = 'RECALLED'` 保留可见。
4. **新建统一幂等记录表**：
   - `CREATE TABLE public_trace_code_idempotency`，含物理唯一索引 `uk_ptc_idem_org_key (org_id, idempotency_key)`。

---

## 五、测试分层策略与执行方法

### 1. 默认测试套件（本地及 CI 离线门禁，无外部依赖）
- `PublicTraceApplicationServiceTest` (21 tests)：纯领域与应用服务测试，覆盖 26 位 Base32 格式、SHA-256 哈希计算、脱敏掩码边界、非 ACTIVE 批次阻断、同批次一批一码重用、同 key 语义重放、异 key 语义冲突（409 IDEMPOTENCY_KEY_REUSED）、停用终态不可逆、未知码与停用码 404 隐藏、正则非法字符拦截且不查 DB、Mockito verify 验证企业端全链路带 org_id 锁定读且绝不调用无租户查询、消费者白名单无敏感泄露（机密哨兵字符串拦截）、模拟温度 INSUFFICIENT_DATA 诚实说明、RECALLED 模拟召回声明。
- `PublicTraceCodeControllerTest` (8 tests)：企业端 WebMvc 切片测试，覆盖非 OPERATOR 拦截（403 ACCESS_DENIED）、跨组织越权拦截（403 ORG_SCOPE_DENIED）、Idempotency-Key 缺失与非法长度参数校验（400 BAD_REQUEST）、CSRF 防护拦截、激活与停用正常 200 响应。
- `PublicTraceConsumerControllerTest` (4 tests)：消费者匿名端 WebMvc 切片测试，覆盖匿名免认证免 CSRF 放行、匿名 POST 请求安全拦截（401/403，防绕过 CSRF）、非法格式 publicTraceId 404 拦截、以及严格禁止词典（严禁泄露内部 ID、raw batch_no、raw origin_text、token_hash、is_deleted、detailsJson、idempotencyKey 等）。

### 2. 真实 MySQL 8.4 集成测试（受 `MYSQL_IT_ENABLED=true` 环境变量控制）
- 测试类：`PublicTraceMysqlIntegrationTest` (16 个综合核心实证用例)：
  1. `testPublicTraceLifecycle_EndToEnd`：端到端全生命周期全流程演练。包含批次创建与提交、有效与更正事件记录、企业端激活、数据库物理 token_hash 核对、统一幂等表记录核验、消费者端匿名免 CSRF 查询、白名单输出核验、严格禁止词典检查、批次流转至 RECALLED 时如实返回召回声明、流转至 FROZEN/CLOSED 时召回声明消失、企业端停用追溯码、停用后消费者端即刻返回 404 PUBLIC_TRACE_NOT_FOUND；
  2. `testIdempotencyCounterExample1_KeyReusedAcrossBatchesAfterActiveHit`：**P0-1 反例1**，key B 对已有 ACTIVE 码调用成功后，再用 key B 激活 batch2 必须拦截报 409 IDEMPOTENCY_KEY_REUSED；
  3. `testIdempotencyCounterExample2_KeyReusedAcrossBatchesAfterDisabledHit`：**P0-1 反例2**，key C 对已 DISABLED 码调用停用成功后，再用 key C 停用 batch2 必须拦截报 409 IDEMPOTENCY_KEY_REUSED；
  4. `testIdempotencyCounterExample3_ConcurrentCrossActionRace`：**P0-1 反例3**，已有 ACTIVE 码时同 key 跨动作并发竞争 (ACTIVATE 语义重放 vs DISABLE 停用)，多会话隔离，恰好一个 200 另一方必为 409 IDEMPOTENCY_KEY_REUSED，统一幂等表仅留 1 条；
  5. `testRollbackAtomicity_OnActivationFailure`：**P0-4A 激活事务原子性回滚实证**，通过动态临时 CHECK 约束触发幂等记录插入失败，断言追溯码和幂等表均回滚为 0 条，并在 finally 恢复 schema；
  6. `testRollbackAtomicity_OnDisableFailure`：**P0-5 停用事务原子性回滚实证**，通过动态注入临时 CHECK 约束让更新为 DISABLED 失败，验证事务完整回滚（码保持 ACTIVE、disabled_at 为空、幂等表无残留），并在 finally 完整恢复 schema；
  7. `testConcurrentActivation_SameBatchDifferentKeys`：**P0-4B 同批次不同 key 并发激活**，多会话独立隔离，两请求均 200、publicId 完全相同、码仅 1 条、幂等表有 2 条持久绑定；
  8. `testConcurrentActivation_SameBatchSameKey`：**P0-4C 同批次同 key 并发激活**，两请求均 200、publicId 完全相同、码仅 1 条、幂等表仅 1 条持久绑定；
  9. `testConcurrentActivation_DifferentBatchesSameKey_TriggersDuplicateKeyAnd409`：**P0-4D 不同批次同 key 并发激活**，一成 (200) 一败 (409 IDEMPOTENCY_KEY_REUSED)，实证触发 MySQL DuplicateKeyException 后 SELECT ... FOR UPDATE 当前读恢复，成功批次有码，失败批次回滚为 0；
  10. `testTimelineDeterministicOrder_SameTimestampOrderedByIdAsc`：**P0-5 多事件完全相同时间戳排序稳定性**，插入 3 条具有完全相同 occurred_at 与 recorded_at 的有效事件，公开 timeline 按 `occurred_at ASC, recorded_at ASC, id ASC` 严格单调递增稳定排序；
  11. `testConfidentialSentinelStringNeverLeaked`：**P0-3 机密哨兵字符串测试**，在 summary、detailsJson、组织、场所中填入机密文本，断言消费者响应全文绝无泄漏，公开 event 仅为受控业务标签；
  12. `testUnknownAndDisabled404ResponseIndistinguishable`：**P0-5 响应一致性对比**，对比未知码与 DISABLED 码的 404 响应结构（status、code、title、detail 完全一致），外部无法探测；
  13. `testPlatformAdminWriteOperationRejected`：**P0-5 权限反例**，平台管理员角色 (PLATFORM) 尝试写操作被拒绝（403 ACCESS_DENIED）；
  14. `testAnonymousPostToPublicTraceRejected`：**P0-4 安全反例**，公开路径匿名 POST 请求被拦截（401/403），不能跨方法绕过 CSRF；
  15. `testFlywayV6_PhysicalCheckConstraints_EnforcedByDatabase`：Flyway V6 物理级约束真实数据库验证（一批一码硬件级唯一约束拦截、统一幂等表唯一索引 `uk_ptc_idem_org_key` 真实拦截、非法状态值拦截、ACTIVE 状态携带 disabled_at 违规拦截）；
  16. `testV6_OrgIdNotNull_AndConsistentWithBatchOrgId`：**V6 升级兼容性实证**，真实 MySQL 证明追溯码 org_id 非空且与 batch.org_id 一致，元数据验证 `IS_NULLABLE = 'NO'`，孤儿数据插入物理报错。
  - `@AfterEach` 中彻底清空 `public_trace_code_idempotency` 表与全部夹具，保证测试无污染。
