# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的基本结构，并使用语义化版本。

## [Unreleased]

### Added

- Phase A Slice 4 自有冷库出入库（无 Flyway 迁移）：
  - 批次详情“自有冷库仓储”面板：当前责任组织 OPERATOR 对 ACTIVE + NORMAL 批次记录冷库入库 / 出库，冷库选项来自本组织场所目录（`siteType = COLD_STORE`），时间线展示冷库场所。
  - 真实浏览器验收 `tests/e2e/real-slice4.spec.ts` 与 MySQL 事实校验（B2 / B3 各一条 IN + OUT，批次数量 / 责任组织 / 状态 / 版本不变，无批次 / 谱系 / 交接 / 运输副作用）。

- Phase A Slice 3 PROCESS / SPLIT：
  - Flyway V10：`batch` 增加 `produced_by_operation_id` / `consumed_by_operation_id`（外键，被操作全量消耗的批次必须 CLOSED）；`batch_operation_item` 增加角色与批次引用形状 CHECK、未删除 OUTPUT 唯一产出（虚拟生成列唯一索引）、同操作同批次唯一与外键；`batch_relation` 增加外键与禁止重复上游边；存在旧协议 DRAFT 批次操作时迁移 fail-fast，历史已提交操作原样保留、不回填。
  - 批次操作 API：`GET /api/v1/batch-operations/{id}`、`GET /api/v1/batch-operations?batchId=`、`DELETE /api/v1/batch-operations/{id}?expectedVersion=`；批次响应新增派生 `remainingQuantity` 与 `producedByOperationId` / `consumedByOperationId`。
  - 生产 Vue 页面：加工 / 拆分向导（投入固定为全部剩余量、实时平衡提示）、批次操作详情（提交 / 删除草稿）、批次详情谱系面板与剩余量；批次转出后本组织历史操作只读可见。
  - 真实浏览器验收 `tests/e2e/real-slice3.spec.ts` 与 MySQL 事实校验（1000 = 960 + 30 + 10，960 = 600 + 360）。

- Phase A Slice 2 Transfer + Shipment：
  - Flyway V9：`shipment` 补齐 PLANNED 生命周期（发送 / 接收组织、PLANNED 时 `loaded_at` 可空、发运 / 到达 / 取消留痕、状态与形状 CHECK、组织与场所外键），新增 `shipment_idempotency`；`transfer` 增加运输任务外键、同运输任务同发送 / 接收方复合外键、`(shipment_id, batch_id)` 唯一约束，并重建生命周期形状 CHECK；存量 shipment 行或未绑定的新规 PENDING 交接会使迁移 fail-fast。
  - 运输任务 API `/api/v1/shipments`：创建、绑定 / 解绑交接、承运商发运 / 到达、取消、三方组织范围查询；装载清单变更统一遵循 shipment → transfer → batch 锁顺序并递增运输任务版本。
  - 目录只读 API：`GET /api/v1/organizations`、`GET /api/v1/organizations/{orgId}/sites`。
  - 生产 Vue 页面：发起交接、新建运输任务、运输任务列表 / 详情（发货方 / 承运方 / 接收方分视角）、待接收交接；批次详情展示交接与运输记录、运输事件事实及转出后的本组织历史记录。
  - 真实三账号浏览器验收 `tests/e2e/real-slice2.spec.ts` 与 MySQL 事实校验；`SMOKE_KEEP=true` 手工验收模式。

- 服务端子工程 `server/`：基于 Eclipse Temurin Java 25 LTS 与 Spring Boot 4.1.1。
- 引入 Apache Maven Wrapper 3.9.x（纯脚本模式 `mvnw` / `mvnw.cmd`），配置 `maven.compiler.release=25`。
- 引入 Spring Boot 4 模块化依赖：WebMVC、Validation、Security、Actuator、Flyway、官方 WebMVC/Security 测试模块、`mybatis-plus-spring-boot4-starter 3.5.17` 及 Jackson 3。
- 配置分层安全机制：`application.yml` 与 `application-local.yml.example`，全环境变量占位，严禁敏感密码硬编码。
- 规划 8 大业务包边界：`common`、`identity`、`masterdata`、`batch`、`trace`、`quality`、`attachment`、`audit`，确立四层架构规范。
- common 模块链路追踪：`RequestIdFilter` 自动提取/生成 `X-Request-Id`，HTTP 响应头透传与 SLF4J MDC 绑定。
- 统一成功响应包装器：`SuccessEnvelope`，匹配 OpenAPI 3.1 契约标准（data + meta）。
- 统一错误处理：基于 `@RestControllerAdvice` 遵循 RFC 9457 Problem Details 规范，集成参数校验字段错误 `fieldErrors` 格式化。
- 受控示例端点及官方 Boot 4 MockMvc/Security 测试，覆盖统一 200/400/401/403/404、CSRF 与请求 ID 契约。
- 本地数据库容器编排 `deploy/docker-compose.yml`：基于 MySQL 8.4 LTS，配置宿主机端口 3307 映射、`utf8mb4`、UTC 与健康检查；密码只从本地 `.env` 注入。
- 最小基准数据库迁移脚本 `server/src/main/resources/db/migration/V1__init_schema.sql`：覆盖 8 大业务域 24 张核心基准表，包含雪花算法 BIGINT 主键、5 大通用审计字段、软删除标记、DECIMAL 精度度量与追加式追溯/审计模型，支持空库干净执行。
- GitHub Actions 后端持续集成工作流 `.github/workflows/backend-ci.yml`：集成 Temurin JDK 25、真实 MySQL 8.4 空库迁移/健康检查、Maven 缓存与失败报告归档。
- 工程文档：`server/README.md`（后端快速开发与容器指南）、`docs/phase2/README.md`（Phase 2 交付总结与规范索引）。
- PRD v0.1、领域调研、开发流程规范。
- Issue、Pull Request 模板及基础仓库配置。
- Phase 1 页面清单、交互规则、角色权限矩阵和业务状态机。
- 批次操作/谱系数据模型、数据字典、OpenAPI 3.1 草案和统一错误规范。
- Java 25 LTS/Spring Boot 4.1.1/MySQL 8.4 LTS/Vue 3 技术架构及 7 项 ADR；本机 MySQL 9.1.0 仅作为兼容验证环境。
- Phase 1 可交互原型、测试计划和三条版本化演示数据方案。

### Changed

- TraceEvent（Slice 4）：`WAREHOUSE_IN` / `WAREHOUSE_OUT` 必须指定调用方组织启用的 `COLD_STORE` 场所（缺失 400、跨组织 403、停用 422 `SITE_NOT_ACTIVE`、非冷库 422 `WAREHOUSE_SITE_TYPE_INVALID`），`dataSource` 必须为 `MANUAL` 且不接受 `detailsJson`；仓储事件只能在 IN ↔ OUT 之间更正（422 `WAREHOUSE_EVENT_TYPE_CHANGE_FORBIDDEN`），更正保留 CLOSED 批次审计能力；人工接口拒绝创建或更正 `SALE`（422 `EVENT_TYPE_NOT_MANUAL`）。不强制 IN / OUT 配对或顺序（契约未定义仓储状态机的最小实现解释）。

- BatchOperation（Slice 3）：PROCESS / SPLIT 恰好一个 INPUT 且必须等于输入批次当前剩余量（禁止部分 INPUT，422 `PARTIAL_INPUT_NOT_ALLOWED`）；OUTPUT 不再由客户端引用既有批次，而由服务端生成为 DRAFT 并只能随操作提交激活（普通批次接口修改 / 提交返回 409 `BATCH_OWNED_BY_OPERATION`）；提交在同一事务内关闭 INPUT、激活 OUTPUT、固化谱系；物料平衡取消 0.001 kg 容差，改为数值精确相等；SPLIT 产出继承输入批次类型；写操作仅限当前责任组织为 PROCESSOR 的 OPERATOR；存在草稿或待接收交接的批次不能作为 INPUT（409 `BATCH_TRANSFER_OPEN`）。`MERGE` / `REPACK` 在当前 Slice 返回 422 `OPERATION_TYPE_NOT_SUPPORTED`（范围限制，非永久规则）。
- TraceEvent：PROCESS 由 PROCESS 批次操作提交为每个 OUTPUT 自动生成一条，人工接口不得创建或更正 PROCESS；SPLIT 不生成 PACK / PROCESS，普通 PROCESS 不推导 FREEZE。

- Transfer（Slice 2）：提交请求只携带 `expectedVersion`，必须已绑定 PLANNED 运输任务；接受 / 拒收要求运输任务已 DELIVERED；**ACCEPT 不再生成 ARRIVAL**；接收方不能是承运组织；已绑定草稿禁止修改接收方；响应新增 `traceBatchNo`、`shipmentId`、`shipmentNo`、`shipmentStatus`，列表支持 `batchId` 筛选。
- TraceEvent：TRANSPORT / ARRIVAL 与 SOURCE 一样只能自动投影，人工接口拒绝创建或更正；当前责任组织读取批次完整时间线，历史参与组织只读本组织记录的事件。
- 消费者公开标签 ARRIVAL 改为“冷链运输到达”。
- 组织摘要 `GET /api/v1/organizations/{orgId}` 对已认证企业用户开放交易对手白名单摘要。
- 缺失必填查询参数或参数类型不匹配时返回 400 `INVALID_REQUEST`（此前为 500）。
- 真实冒烟脚本改为异步运行 Playwright，避免阻塞事件循环导致后端日志管道写满、请求挂起。

- 更新根 `README.md`：当前阶段推进至 `Phase 2 / 迭代开发（服务端最小骨架启动）`，标明 Phase 1 评审与 Phase 2 后端工程骨架建设已达成。
