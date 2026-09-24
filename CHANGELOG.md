# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的基本结构，并使用语义化版本。

## [Unreleased]

### Added

- Phase B PB1 批次风险状态核心（人工 FROZEN ⇄ NORMAL）：
  - Flyway V12：只新建追加式风险状态转换台账 `batch_risk_transition`（转换时责任组织、流转状态快照 ACTIVE / CLOSED、NORMAL ⇄ FROZEN 转换对、来源仅 `MANUAL` 且必须有操作人、原因去空白后非空、组织内幂等键唯一 + 请求语义哈希、批次与组织外键）；不修改 `batch`、不回填、不引用占位表 alert / recall，也不预留多态 `source_ref_id`（ALERT / RECALL 来源随 PB3 / PB5 的类型化外键与代码同时扩展）。
  - `BatchRiskService` 是 `batch.risk_status` 唯一运行期写入者：`POST /api/v1/batches/{batchId}/risk/freeze` 与 `/risk/release` 仅限批次当前责任组织的 QUALITY_MANAGER，READ COMMITTED 下幂等预读 → 批次行锁 → 锁后复读 → 状态校验 → 台账 → 批次条件更新（版本 +1）→ `RISK_FREEZE` / `RISK_RELEASE` 审计，同一事务；ACTIVE 与 CLOSED 批次均可冻结 / 解除（从不重新打开），DRAFT 不参与，RECALLED 为终态；不改变数量 / 责任组织 / 流转状态 / 公开追溯码，不生成 TraceEvent，不向谱系传播；原因必填、时间由服务端生成、`SYS:` 幂等键前缀保留。
  - 写入收敛：`Batch.riskStatus` 的 MyBatis-Plus 更新策略为 NEVER（通用实体更新无法覆盖风险状态），风险 mapper 不继承 `BaseMapper` 且只允许 `BatchRiskService` 注入，源码扫描测试覆盖 `src/main/java` 与 `src/main/resources` 的全部生产 SQL。
  - `GET /api/v1/batches/{batchId}/risk-transitions`：当前责任组织与平台只读读取完整历史；历史参与组织沿用追溯事件 / 批次操作的判定方式，只读取本组织登记的转换；其他组织 403。
  - 冻结期间完全复用 Phase A 既有守卫（交接创建 / 绑定 / 提交 / 接受、批次操作、冷库仓储与更正、首次激活公开码、终端销售）；运输发运 / 到达、PENDING+DELIVERED 拒收、查询与公开投影保持可用。
  - 生产 Vue：批次详情“风险状态”面板（质量管理员填写原因并二次确认冻结 / 解除、冻结影响说明、风险转换历史、幂等重试）；消费者 FROZEN 状态改为“模拟风险冻结 / 模拟冻结”，并声明仅用于教学实训、不代表真实的产品安全判定、监管措施或产品扣留。
  - 冒烟夹具新增 QUALITY_MANAGER 角色与加工 / 零售 / 来源质量管理员账号（仅夹具，不进入 Flyway）；真实浏览器验收 PB1-A（加工企业冻结 / 解除）、PB1-S（零售企业冻结阻断销售后解除）、PB1-B（CLOSED 冻结 / 解除 + 匿名消费者投影），并证明 Phase A 主链随后照常完成。

- Phase A Slice 6 PublicTraceCode 与消费者全链（无 Flyway 迁移）：
  - 消费者公开投影 `GET /api/public/v1/public/traces/{publicTraceId}` 聚合祖先谱系：一次 recursive CTE 只沿 `batch_relation` 向上遍历（兄弟批次永不进入、DAG 共同祖先去重、环安全），新增 `lineage`（响应内局部键 `N1…Nk`、世代、角色、产品公开名，谱系边来自已提交批次操作而非追溯事件）；时间线聚合目标与祖先批次的有效（SUBMITTED）事件，新增 `eventType` / `nodeKey`，按业务时间 → 世代 → 固定公开事件权重 → 内部稳定次序排序。
  - 显式公开事件白名单（SOURCE、PROCESS、FREEZE、PACK、WAREHOUSE_IN、WAREHOUSE_OUT、TRANSPORT、ARRIVAL、SALE），SQL 只读取白名单列与白名单类型；PURCHASE 与未知类型不再出现在匿名投影中，未知数据来源不回显原值。
  - 谱系完整性失败关闭：祖先批次缺失 / 已删除、谱系边批次操作无法解析、未受控操作类型或成环时返回 500 `PUBLIC_TRACE_LINEAGE_INTEGRITY`（通用说明、无内部 ID），绝不返回截断谱系。
  - 只读事务一致性快照、固定 5 次集合查询（与谱系深度无关）；已售罄 CLOSED + RECALLED 时同时展示流转已关闭与按流转状态措辞的模拟召回提示（仅由批次风险状态决定）。
  - 企业端 `GET /api/v1/batches/{batchId}/public-trace-code`：当前责任组织任意角色与平台只读；未激活 404 `PUBLIC_TRACE_CODE_NOT_FOUND`；激活 / 停用语义保持不变（一批一码、交接不换码、停用终态、关闭后不能首次激活）。
  - 生产 Vue：批次详情“公开追溯码”面板（激活、查看 / 复制 / 打开消费者入口、二次确认停用）；消费者页新增“批次上游谱系”与时间线节点标签，公开码标签改为“公开追溯码”（移除“证书”措辞）。
  - 业务时间精度：自有冷库出入库与终端销售表单改为按秒登记（`datetime-local step="1"`，本地 `YYYY-MM-DDTHH:mm:ss`，不编造毫秒），修复同一分钟内晚于运输到达的销售被登记为更早时间、公开时间线呈现“先销售后到达”的问题；服务端语义不变。
  - 消费者 RECALLED 综合状态改为“模拟召回演练”，并在同一条可见信息中声明仅用于教学实训、不代表真实产品召回、安全鉴定或监管结论（移除脱离演练语境的“请勿继续食用或销售”）。
  - 真实浏览器验收：`real-slice5.spec.ts` 在零售接受之后、终端销售之前激活 B2 / B3 公开追溯码；新增 `real-slice6.spec.ts` 以未登录浏览器扫码查询已售罄的 B2（B0 → B1 → B2）与 B3（B0 → B1 → B3），并做匿名探测、业务时间先后（S1 发运 ≤ 到达 ≤ 销售）与零写入校验。

- Phase A Slice 5 终端 Sale：
  - Flyway V11：新建追加式终端销售台账 `sale`（正数量、kg、仅 `SUBMITTED`、组织内幂等键唯一 + 请求语义哈希、`(site_id, org_id)` 复合外键保证场所属于销售组织；无消费者 / 支付 / 订单 / 发票字段）；`site` 增加 `UNIQUE (id, org_id)`；`batch` 增加写一次标记 `first_sale_id`，复合外键 `(first_sale_id, id, org_id) → sale(id, batch_id, org_id)` 保证只能指向同批次同组织的 Sale，CHECK 禁止同时被操作全量消耗、禁止 DRAFT；历史批次不回填。
  - 终端销售 API `POST / GET /api/v1/batches/{batchId}/sales`：仅当前责任 RETAILER 的 OPERATOR 在本组织启用 STORE 门店提交；部分销售、超卖 422、售罄同事务 CLOSED；每笔成功 Sale 自动生成且仅生成一条 SALE 追溯事件（`SYS:SALE:SALE:{saleId}`）；幂等重放先于状态校验，业务时间统一规范化为 UTC 微秒参与哈希与持久化。
  - 生产 Vue：批次详情“终端销售”面板（门店选项来自本组织场所目录 STORE、剩余量只展示服务端派生值、全部剩余快捷填入、幂等重试）、终端销售记录表、首次销售 / 售罄提示、SALE 事件结构化事实。
  - 真实浏览器验收 `tests/e2e/real-slice5.spec.ts`（加工企业 T2 / T3 同一 S1 → 承运 → 零售接受 → B2 200 + 400、B3 360 售罄，首次销售后真实 API 拒绝探测）与 MySQL 事实校验。

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

- 首次销售锁定（Slice 5）：批次 `firstSaleId` 非空后，交接创建 / 提交 / 接受、运输任务装载与批次操作创建 / 提交一律 409 `BATCH_SALE_STARTED`（剩余量大于 0 时同样适用）；守卫读取已持有行锁的批次行，REPEATABLE READ 事务中也能看到并发已提交的首次销售。
- `remainingQuantity` 改为 `声明数量 - 已提交 INPUT 消耗量 - 已提交终端销售数量`，批次列表 / 详情、批次操作全量投入校验与终端销售超卖校验共用同一派生服务；批次响应新增 `firstSaleId`。
- 消费者公开标签 SALE 由“经销零售出库”改为“终端零售销售”（Sale 只表示终端消费出库，企业间流转使用 Transfer）。
- 批次详情：已开始终端销售的批次隐藏“发起交接”与加工 / 拆分入口。

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

### Fixed

- 终端销售（Slice 5 潜在缺陷，PB1 并发测试发现）：同组织同幂等键的并发请求在等待批次行锁后复读幂等键时，MyBatis 会话级一级缓存会返回预读时缓存的空结果；先到者售罄关闭批次后，后到者因此得到 422 `BATCH_FLOW_BLOCKED` 而不是重放原 Sale。`SaleMapper.selectByOrgIdAndIdempotencyKey` 改为执行前清空会话缓存（非锁定读，锁顺序仍为 batch → sale 幂等索引），后到者现在重放同一 Sale（201），同键不同载荷仍 409 `IDEMPOTENCY_CONFLICT`；新增确定性 MySQL 竞态回归。
- 企业端按组织范围读取的快照一致性（PB1 最终安全审查发现；PB1 风险历史与 Phase A 既有的追溯事件、终端销售、批次详情 / 列表读路径）：这些读取先按可变的 `batch.org_id` 授权，再以多条自动提交语句读取数据或派生数量，各语句使用不同读视图，响应可能把“旧责任组织授权”与交接接受之后新责任组织才提交的行 / 数量事实拼在一起（风险转换的原因与操作人、追溯事件、首笔销售与门店、剩余量）。风险历史、追溯事件、终端销售、批次详情与批次列表现在各自在一个只读 REPEATABLE READ 事务内执行，授权事实与响应数据来自同一 InnoDB 一致性快照；只用非锁定读，不增加任何锁，不阻塞交接接受或业务写入；新增确定性 MySQL 竞态回归（测试专用 mapper 门闩）与事务注解契约测试。
