# 冷冻海鲜溯源系统 - 后端工程 (Server)

本工程为冷冻海产品溯源系统的后端核心服务，基于 Java 25 LTS 与 Spring Boot 4.1.1 搭建，采用分层架构与领域驱动思想构建清晰的 8 大业务域骨架。

---

## 一、技术栈与依赖体系

- **核心语言与平台**：Eclipse Temurin Java 25 LTS（编译目标 `maven.compiler.release=25`）
- **基础框架**：Spring Boot 4.1.1 (基于 Spring Framework 7.0.9 与 Jakarta EE 11)
- **Web 容器**：`spring-boot-starter-webmvc`（Servlet 6.1 模块化组件）
- **持久层框架**：`mybatis-plus-spring-boot4-starter` 3.5.17（专为 Boot 4 适配的新一代 Starter）
- **数据库版本迁移**：`spring-boot-starter-flyway` + Flyway 12.4.0 (MySQL 驱动)
- **JSON 序列化**：Boot 4 统一管理的 Jackson 3（全局输出 ISO 8601 时区时间戳，禁用整数时间戳）
- **参数校验**：`spring-boot-starter-validation` (Jakarta Validation / Hibernate Validator)
- **安全与运维**：Spring Security 7 默认保护非公开路径并启用 CSRF；Actuator 仅公开 `/actuator/health`
- **测试**：Boot 4 模块化 `spring-boot-starter-webmvc-test` / `spring-boot-starter-security-test`
- **构建工具**：Apache Maven 3.9.x 纯脚本包装器 (`mvnw` / `mvnw.cmd`，only-script 模式)

---

## 二、项目代码分层与 8 大业务域边界

基准包路径：`com.example.traceability`

### 1. 8 大核心业务包
| 业务包 | 领域职责描述 |
|---|---|
| `common` | 全局统一响应封套 (`SuccessEnvelope`)、RFC 9457 Problem 统一异常处理、`RequestIdFilter` 链路追踪拦截器、全局配置与基础基类 |
| `identity` | 企业组织、场所租户、用户账号、角色权限与会话认证上下文 |
| `masterdata` | 水产品品类与物料主数据、冷链温控多阶段基准阈值规则 |
| `batch` | 批次主数据（捕捞/加工/流通/销售）、批次转换与物料平衡操作、批次谱系图、对外公开追溯码绑定 |
| `trace` | 不可变追加式追溯事件（双时间体系）、冷链运输任务、企业间批次交接流转 |
| `quality` | 仓储与在途多源温控时序记录、检验检疫报告、质量越界告警、应急模拟召回及范围确认 |
| `attachment` | 资质与检验单据受控元数据管理、文件哈希防篡改与安全访问控制 |
| `audit` | 全系统结构化操作审计日志（追加式只读记录） |

### 2. 包内四层调用规范
每个业务包内部遵循标准分层架构：
`web` (控制器/端点) -> `application` (应用编排服务) -> `domain` (业务实体/规则) -> `infrastructure` (仓储实现/Mapper/外部适配)
- **调用约束**：严禁 Controller 直接注入 Mapper；核心业务校验规则下沉至 Domain 层；跨业务包协作优先通过 Application 服务接口。

---

## 三、本地数据库容器 (MySQL 8.4) 启动指南

### 3.1 端口隔离决策 (3307 : 3306)
由于开发宿主机环境通常已安装本地数据库（如 Windows MySQL91 服务常驻占用 `3306` 端口），为彻底避免端口冲突与数据污染，本项目 Docker Compose 将**宿主机端口严格映射为 `3307`**，容器内部保持原生 `3306`。

### 3.2 启动数据库容器
统一使用仓库根目录的 `deploy/docker-compose.yml` 启动 MySQL 8.4，避免维护两份容易漂移的编排配置：

```bash
# 先复制本地环境变量模板并修改开发密码
cp .env.example .env

# 在仓库根目录启动；显式读取根目录 .env
docker compose --env-file .env -f deploy/docker-compose.yml up -d --wait
```

### 3.3 数据库默认连接参数
| 配置项 | 容器内部 | 宿主机开发机映射 | 环境变量覆盖 |
|---|---|---|---|
| **服务地址** | `mysql` | `127.0.0.1` / `localhost` | `DB_HOST` |
| **端口** | `3306` | **`3307`** | `DB_PORT` |
| **数据库名** | `seafood_trace` | `seafood_trace` | `DB_NAME` |
| **应用用户** | `trace_user` | `trace_user` | `DB_USERNAME` |
| **应用密码** | 环境变量注入 | 环境变量注入 | `DB_PASSWORD`（必填） |
| **ROOT密码**| 环境变量注入 | 环境变量注入 | `DB_ROOT_PASSWORD`（必填） |
| **字符集** | `utf8mb4` | `utf8mb4_0900_ai_ci` | 固定参数配置 |
| **时区** | `UTC (+00:00)` | `UTC` | 强制统一 UTC |

> **提示**：在仓库根目录执行 `docker compose --env-file .env -f deploy/docker-compose.yml ps` 可查看健康检查状态（`healthy` 即代表 MySQL 初始化完成）。停止容器请执行 `docker compose --env-file .env -f deploy/docker-compose.yml down`；如需重置数据，可在明确不再需要本地数据后执行 `docker compose --env-file .env -f deploy/docker-compose.yml down -v`。

---

## 四、本地开发配置与环境变量

工程遵循严格的敏感信息隔离规范，真实密码决不允许进入 Git 仓库：
1. **主配置** `src/main/resources/application.yml`：
   定义通用配置与默认环境变量占位符。
2. **本地配置模板** `src/main/resources/application-local.yml.example`：
   首次本地开发时，可复制该文件为 `application-local.yml`（已加入 `.gitignore`）：
   ```bash
   cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
   ```
   并在启动服务前设置 `DB_PASSWORD`；Compose 会从仓库根目录 `.env` 读取本地值，Spring Boot 则从当前进程环境变量读取。

---

## 五、编译构建与测试执行指南

### 5.1 编译与资源打包
使用工程自带的 Maven Wrapper 验证编译与 Flyway 资源构建：
- **Windows (PowerShell / CMD)**：
  ```powershell
  .\mvnw.cmd clean test-compile
  ```
- **Linux / macOS**：
  ```bash
  chmod +x mvnw
  ./mvnw clean test-compile
  ```

### 5.2 执行全量测试套件
执行单元测试、官方 Boot 4 WebMVC/Security 切片测试；MySQL 迁移测试在 CI 的真实 MySQL 8.4 服务上自动启用：
```powershell
# Windows
.\mvnw.cmd clean test --no-transfer-progress

# Linux / macOS
./mvnw clean test --no-transfer-progress
```

### 5.3 启动后端服务
```powershell
# Windows
.\mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```
服务默认启动于 `http://localhost:8080`。

健康检查：`GET http://localhost:8080/actuator/health`。除健康检查与 `/api/v1/samples/**` 外，其余路径在登录模块实现前默认返回统一的 `401 AUTH_REQUIRED`；写请求继续受 CSRF 保护。

---

## 六、协议契约与接口自测

### 1. 成功响应封套契约 (OpenAPI 3.1 兼容)
所有业务正常响应统一包装为 `data` 与 `meta`：
- `meta.requestId`: 由 `RequestIdFilter` 生成或透传的调用追踪 ID。
- `meta.timestamp`: ISO 8601 标准带时区时间戳。

### 2. 异常响应契约 (RFC 9457 Problem Details)
当发生业务异常或系统错误时，统一响应 `Content-Type: application/problem+json`：
- 包含字段：`type`, `title`, `status`, `code`, `detail`, `instance`, `requestId`。
- 当 `@Valid` 参数校验不通过（400/422）时，响应体附加 `fieldErrors: [{field, code, message}]` 数组。

### 3. 受控验证端点
本地启动服务后，可访问预留示例端点验证核心通信协议：
- **心跳/成功响应**：`GET /api/v1/samples/ping`
- **受控 404 响应**：`GET /api/v1/samples/not-found`
- **参数校验失败响应**：`POST /api/v1/samples/validate`
