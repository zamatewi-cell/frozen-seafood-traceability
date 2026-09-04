# Phase 2 交付总结：后端工程骨架与基础设施建设

**交付阶段**：Phase 2 (迭代开发 - 服务端最小骨架启动)

**关联任务**：GitHub Issue #2 (`chore: bootstrap Phase 2 Spring Boot backend`)

**技术栈基线**：Java 25 LTS + Spring Boot 4.1.1 + Spring Security 7 + Actuator + MyBatis-Plus 3.5.17 + MySQL 8.4 LTS + Flyway 12.4.0 + GitHub Actions CI

**更新日期**：2026-09-04

---

## 一、阶段建设目标与定位

依据 Phase 1 形成的架构规约（`docs/phase1/`）与 7 项架构决策记录（`docs/adr/`），Phase 2 的首要目标是搭建稳定、可编译、可测试且具备标准化规范的 Java/Spring Boot 后端工程骨架，为后续业务纵向闭环（Phase 3）提供完备的基础底座。

本阶段严格遵循**最小改动与分层边界原则**：
1. 建立清晰的 8 大业务域分包骨架，但**严禁提前堆砌未经测试的复杂业务逻辑**。
2. 建立标准化统一通信契约：符合 OpenAPI 3.1 的统一成功包装器与遵循 RFC 9457 的 Problem 异常处理。
3. 建立数据库与容器环境：配置 MySQL 8.4 Docker Compose，使用 3307 端口规避本地开发环境冲突，并编写覆盖全业务域基准模型的 Flyway V1 迁移脚本。
4. 建立自动化质量保证体系：配置 GitHub Actions CI，使用官方 Boot 4 WebMVC/Security 测试模块，并在真实 MySQL 8.4 服务上验证空库迁移与健康检查。

---

## 二、8 大业务域边界与架构分层

在工程基包 `com.example.traceability` 下规划了 8 大业务模块边界，各业务模块预留四层调用结构（`web` -> `application` -> `domain` -> `infrastructure`）：

```text
com.example.traceability
├── common/             # 统一响应、RFC 9457 异常处理、RequestId 链路追踪拦截器、基类与常量
├── identity/           # 企业租户、场所设施、用户账号、角色权限与会话认证上下文
├── masterdata/         # 水产品物料主数据、冷链温控阈值规则方案
├── batch/              # 批次主数据、批次转换与物料平衡操作、谱系图、对外公开追溯码绑定
├── trace/              # 不可变业务追溯事件、冷链运输任务、企业间批次交接凭证
├── quality/            # 多源温控明细时序数据、检验检测报告、越界质量告警、应急模拟召回
├── attachment/         # 受控单据与资质文件元数据、内容哈希防篡改与权限存储控制
└── audit/              # 系统追加式操作审计日志（不可篡改安全留痕）
```

**分层约束规则**：
- 控制器严禁直接调用持久层 Mapper；
- 核心业务规则强内聚于 Domain 层；
- 模块间解耦，跨域调用通过 Application 服务接口交互。

---

## 三、接口通信协议与统一契约

### 3.1 统一成功响应封套 (`SuccessEnvelope`)
匹配 `docs/api/openapi.yaml` 约定的数据规范：
- **数据结构**：`data` 承载业务数据实体，`meta` 承载系统追踪元数据（`requestId`、ISO 8601 时区时间戳、分页元数据等）。
- **示例**：
  ```json
  {
    "data": {
      "message": "pong"
    },
    "meta": {
      "requestId": "6f94726d-2098-4c6e-a22a-6058e3903112",
      "timestamp": "2026-09-04T09:30:00+08:00"
    }
  }
  ```

### 3.2 统一错误响应结构 (RFC 9457 Problem Details)
基于 `@RestControllerAdvice` 拦截所有系统与业务异常，严格按 RFC 9457 输出：
- **响应头**：`Content-Type: application/problem+json`
- **字段规范**：`type`, `title`, `status`, `code`, `detail`, `instance`, `requestId`。
- **校验错误扩展**：当 Jakarta Validation 参数校验失败（`MethodArgumentNotValidException`）时，错误对象附加 `fieldErrors: [{field, code, message}]` 数组；畸形 JSON 同样稳定返回 400。

### 3.3 全链路追踪拦截 (`RequestIdFilter`)
- 请求进入时，从 HTTP 请求头提取 `X-Request-Id`；若不存在则自动生成 UUID。
- 将 `requestId` 回显至 HTTP 响应头，并注入 SLF4J `MDC` 日志上下文中，确保全链路日志一致性。

---

## 四、数据库容器与 Flyway 迁移体系

### 4.1 MySQL 8.4 Docker Compose 编排 (`deploy/docker-compose.yml`)
项目以仓库根目录 `deploy/docker-compose.yml` 作为唯一的 MySQL 8.4 容器编排来源：
- **启动方式**：支持通过 `docker compose -f deploy/docker-compose.yml up -d` 或 `cd deploy && docker compose up -d` 一键启动服务。
- **端口映射**：`3307:3306`（严格规避宿主机本地可能存在的 3306 端口冲突）。
- **统一字符集与时区**：字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`，时区 `+00:00` (UTC)。
- **健康检查**：配置 `mysqladmin ping` 容器健康度探针。
- **持久化**：使用 `mysql_data`（`seafood_mysql_data`）具名卷。
- **凭据策略**：应用密码和 root 密码必须从未入库的 `.env` 注入，不在 Compose 文件中设置通用默认密码。

### 4.2 Flyway V1 初始化基准模型 (`V1__init_schema.sql`)
覆盖 8 大业务域的 24 张核心基准表，统一遵循如下规范：
1. **主键标准**：统一使用 `BIGINT UNSIGNED`，兼容分布式雪花算法（Snowflake ID）与自增测试。
2. **公开追溯安全**：对外公开标识使用 `public_id VARCHAR(40)` 与 `token_hash CHAR(64)`，杜绝内部 ID 遍历。
3. **5 大通用审计字段**：
   - `version BIGINT NOT NULL DEFAULT 0`（乐观锁并发控制）
   - `is_deleted TINYINT(1) NOT NULL DEFAULT 0`（逻辑软删除）
   - `created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`
   - `created_by BIGINT UNSIGNED NULL`
   - `updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)`
   - `updated_by BIGINT UNSIGNED NULL`
4. **度量与精度**：数量与重量统一采用 `DECIMAL(18,3)`，温度采用 `DECIMAL(6,2)`，严禁 float/double 精度丢失。
5. **不可篡改特性**：追溯事件（`trace_event`）与审计日志（`audit_log`）设计为追加式模型，事件更正采用新事件记录并引用原事件 ID。

---

## 五、持续集成与质量保证 (CI)

配置了 GitHub Actions 自动化工作流 `.github/workflows/backend-ci.yml`：
- **运行环境**：`ubuntu-latest`，Temurin JDK 25。
- **数据库服务**：启动真实 `mysql:8.4.0` 服务，执行 Flyway V1 空库迁移并检查迁移历史与 Actuator 健康状态。
- **缓存策略**：开启 Maven 依赖缓存，基于 `server/pom.xml` 哈希自动缓存与复用。
- **权限与执行**：前置赋予 `chmod +x server/mvnw`，在 `server/` 工作目录下执行 `./mvnw -B clean test --no-transfer-progress`。
- **失败取证**：当测试断言或编译失败时，自动上传 Surefire 测试报告归档。

---

## 六、交付物核验自检清单

| 需求项 | 交付成果路径 | 状态 | 验证方式 |
|---|---|---|---|
| R1. 后端工程脚手架与依赖 | `server/pom.xml`, `mvnw`, `mvnw.cmd` | 已达成 | Maven Wrapper 纯脚本可用，Java 25 编译通过 |
| R1. 环境分层配置 | `server/src/main/resources/application*.yml` | 已达成 | 敏感信息无明文硬编码，全环境变量占位 |
| R2. 8 大业务包结构 | `server/src/main/java/com/example/traceability/**` | 已达成 | 8 大业务域结构清晰，分层规范确立 |
| R2. 统一成功与 RFC 9457 错误处理 | `com.example.traceability.common.**` | 已达成 | SuccessEnvelope, ProblemDetails, RequestIdFilter 完备 |
| R3. 本地数据库容器 | `deploy/docker-compose.yml` | 已达成 | MySQL 8.4，端口映射 3307:3306，凭据由 `.env` 注入 |
| R3. 数据库版本化迁移脚本 | `server/src/main/resources/db/migration/V1__init_schema.sql` | 已达成 | 标准 MySQL 8.4 语法，8大业务域基准表就绪 |
| R4. 自动化测试套件 | `server/src/test/java/**` | 已达成 | 覆盖 200/400/401/403/404、CSRF、请求 ID、MySQL 迁移和健康检查 |
| R4. GitHub Actions CI | `.github/workflows/backend-ci.yml` | 已达成 | JDK 25、MySQL 8.4、依赖缓存与 `mvnw test` |
| R4. 项目工程与阶段文档 | `server/README.md`, `docs/phase2/README.md` | 已达成 | 中文文档规范详尽，开发指南完整 |

---

## 七、后续阶段（Phase 3）衔接规划

在 Phase 2 工程骨架与基础设施顺利就绪后，系统开发正式进入业务落地阶段，下一阶段重点攻坚**最小纵向业务闭环 (Phase 3)**：
1. **组织与用户上下文**：落地 `identity` 模块企业组织与操作员的基础维护与上下文绑定。
2. **产品与规则定义**：落地 `masterdata` 模块海产品基础物料与冷链温控阶段规则。
3. **批次流转核心闭环**：实现“原料建批次 (`batch`) -> 关键追溯事件记录 (`trace_event`) -> 对外公开追溯码生成 (`public_trace_code`) -> 消费者扫码查询端点”的最小纵向业务闭环。
