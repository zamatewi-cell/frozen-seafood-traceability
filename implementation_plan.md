# 实施计划 (implementation_plan.md) - GitHub Issue #13

## 1. 项目背景与目标

本项目为冷冻海产品溯源系统 Phase 3 的核心业务切片，实现 PRD `FR-BATCH-002` 与 GitHub Issue #13：
通过“批次操作 (Batch Operation) + 操作明细 (Batch Operation Item) + 谱系边 (Batch Relation)”完整表达水产品的拆分（SPLIT）、合并（MERGE）、加工（PROCESS）和分装（REPACK），打破固定线性流程，建立网状谱系图结构。
同时，系统在操作提交时执行服务端物料平衡校验、引用批次活性/组织归属校验、输出批次唯一生产来源校验、输入批次历史累计量校验、MySQL 8.4 recursive CTE 环检测，并在同一事务内稳定顺序加锁、原子流转状态与插边，支持高并发环境下的两套幂等重放与当前读恢复。

## 2. 技术选型与规范

- **开发框架**：Java 25 LTS + Spring Boot 4.1.1 + Spring Framework 7.0.9 + Spring Security 7
- **持久层技术**：MyBatis-Plus 3.5.16 + MySQL 8.4.0 LTS Connector
- **数据库迁移**：Flyway 迁移脚本（遵循严格单向追加原则，绝不修改 V1~V3，新增 `V4__batch_operation_constraints.sql`）
- **图算法与环检测**：MySQL 8.4 recursive CTE（递归公用表表达式）在插边前进行从 child 到 parent 的有向可达性检测
- **并发控制**：
  - 悲观行锁：对操作主表、所有涉及的输入/输出批次按 `batchId` 升序排列加锁（`SELECT ... FOR UPDATE`），杜绝死锁与 TOCTOU 竞态；
  - 乐观锁：`version` 字段比对与原子递增；
  - 幂等恢复：基于 MySQL 当前读（Locking Read）穿透 REPEATABLE READ 快照盲区，实现并发 DuplicateKey 诊断恢复。

## 3. 实施步骤与关键设计

### 3.1 数据库约束与迁移（Flyway V4）
- 迁移文件：`server/src/main/resources/db/migration/V4__batch_operation_constraints.sql`
- 结构变更：
  1. 删除 `batch_operation` 的全局唯一索引 `uk_op_idempotency`；
  2. 将 `idempotency_key` 字段扩展为 `VARCHAR(128) NOT NULL`；
  3. 增加组织内唯一索引 `uk_op_org_idempotency (org_id, idempotency_key)`；
  4. 新增提交幂等键字段 `submission_idempotency_key VARCHAR(128) NULL` 及其组织内唯一索引 `uk_op_org_submission_idempotency (org_id, submission_idempotency_key)`；
  5. 补充物理 CHECK 约束：
     - `chk_op_type`：`operation_type IN ('MERGE', 'SPLIT', 'PROCESS', 'REPACK')`
     - `chk_op_status`：`status IN ('DRAFT', 'SUBMITTED', 'CORRECTED')`
     - `chk_item_role`：`role IN ('INPUT', 'OUTPUT', 'LOSS', 'WASTE', 'SAMPLE')`
     - `chk_item_quantity`：`quantity > 0`
     - `chk_item_unit_code`：`unit_code = 'kg'`
     - `chk_item_normalized_quantity`：`normalized_quantity > 0`
     - `chk_relation_type`：`relation_type IN ('TRANSFORM', 'SPLIT', 'MERGE')`
     - `chk_relation_no_self_loop`：`parent_batch_id <> child_batch_id`

### 3.2 领域实体、枚举与 DTO
- 包路径：`com.example.traceability.batch`
- 实体与枚举：
  - `BatchOperation`（映射 `batch_operation` 表）
  - `BatchOperationItem`（映射 `batch_operation_item` 表）
  - `BatchRelation`（映射 `batch_relation` 表）
  - `BatchOperationType`（`MERGE`, `SPLIT`, `PROCESS`, `REPACK`）
  - `BatchOperationStatus`（`DRAFT`, `SUBMITTED`, `CORRECTED`）
  - `BatchItemRole`（`INPUT`, `OUTPUT`, `LOSS`, `WASTE`, `SAMPLE`）
  - `BatchRelationType`（`TRANSFORM`, `SPLIT`, `MERGE`）
- DTO 定义：
  - `BatchOperationCreateRequest`（包含 `operationType`, `occurredAt`, `note`, `items`）
  - `BatchOperationItemRequest`（包含 `role`, `batchId`, `quantity`, `unitCode`）
  - `BatchOperationSubmitRequest`（包含 `version`）
  - `BatchOperationResponse`（白名单投影：`id`, `orgId`, `operationNo`, `operationType`, `occurredAt`, `recordedAt`, `status`, `note`, `version`, `createdAt`, `createdBy`, `items`, `relations`）
  - `BatchOperationItemResponse`（`id`, `operationId`, `batchId`, `role`, `quantity`, `unitCode`, `normalizedQuantity`）
  - `BatchRelationResponse`（`id`, `operationId`, `parentBatchId`, `childBatchId`, `relationType`, `createdAt`）

### 3.3 数据访问层（Mapper & XML）
- `BatchOperationMapper`：
  - `selectByOrgIdAndIdempotencyKey` / `selectByOrgIdAndIdempotencyKeyForUpdate`
  - `selectByOrgIdAndSubmissionKey` / `selectByOrgIdAndSubmissionKeyForUpdate`
  - `selectByIdForUpdate`
  - `submitOperation(operationId, orgId, expectedVersion, submissionKey, nowUtc, updatedBy)`
- `BatchOperationItemMapper`：
  - `insertBatch(List<BatchOperationItem> items)`
  - `selectByOperationId(Long operationId)`
  - `sumSubmittedInputQuantityByBatchId(Long batchId)`（统计某批次所有已提交操作中的累计 INPUT 数量）
- `BatchRelationMapper`：
  - `insertBatch(List<BatchRelation> relations)`
  - `selectByOperationId(Long operationId)`
  - `countUpstreamRelationsByChildBatchId(Long childBatchId)`（检测输出批次是否已有上游边）
  - `checkCycleWithCte(Long startChildBatchId, Long targetParentBatchId)`（基于 MySQL 8.4 recursive CTE 遍历有向图，检测是否存在从 start 到 target 的连通路径）

### 3.4 业务服务层（`BatchOperationApplicationService`）
- **创建草稿 (`createDraftOperation`)**：
  1. 权限拦截：`principal.getRoles().contains("OPERATOR")`；
  2. 幂等预检：查询同组织同 key，载荷一致直接重放原草稿，不一致报 409 `IDEMPOTENCY_CONFLICT`；
  3. 参数与基数校验：
     - `MERGE`：>= 2 INPUT, >= 1 OUTPUT；
     - `SPLIT`：>= 1 INPUT, >= 2 OUTPUT；
     - `PROCESS` / `REPACK`：>= 1 INPUT, >= 1 OUTPUT；
     - 违规返回 400 `INVALID_REQUEST`；
  4. 明细校验：
     - `INPUT`/`OUTPUT` 必须提供 `batchId`；`LOSS`/`WASTE`/`SAMPLE` 禁止提供 `batchId`；
     - `quantity > 0`，`scale <= 3`，`unitCode` 仅限 `kg`；
     - 同一操作内 `batchId` 不得重复，且不得兼作入出；
  5. 持久化与并发 DuplicateKey 诊断恢复。
- **提交流转 (`submitOperation`)**：
  1. 权限拦截：仅本组织 `OPERATOR`；
  2. 提交幂等检查：若当前操作已提交且 submissionKey 匹配，直接返回；若 submissionKey 已被其他操作占用，报 409；
  3. 获取操作排他锁，校验处于 `DRAFT` 且版本号匹配；
  4. 对涉及的全部批次 ID（INPUT + OUTPUT）进行升序排序后逐个执行 `SELECT ... FOR UPDATE` 加锁；
  5. 批次合法性校验：所有批次必须存在、属于本组织、状态为 `ACTIVE`（非 ACTIVE 报 422 `BATCH_FLOW_BLOCKED`）；
  6. **物料平衡计算**：
     - 服务端独立累加 `sum(INPUT)` 与 `sum(OUTPUT + LOSS + WASTE + SAMPLE)`；
     - 允许绝对差值 `<= 0.001 kg`；不平衡抛出 422 `BATCH_MASS_BALANCE_VIOLATION`，事务完整回滚；
  7. **输出批次校验**：
     - 输出批次无任何上游谱系边（`countUpstreamRelationsByChildBatchId == 0`），否则报 422；
     - 输出数量必须等于该批次声明数量（`item.quantity.compareTo(batch.quantity) == 0`），否则报 422；
  8. **输入批次累计占用校验**：
     - 查询历史已提交 `INPUT` 数量 + 本次 `INPUT` 数量 `<= batch.quantity`，超额报 422 `BATCH_QUANTITY_EXCEEDED`；
  9. **笛卡尔积生成关系边与自环校验**：
     - `INPUT × OUTPUT` 生成边；父子相同报 422 `BATCH_RELATION_SELF_LOOP`；
     - 操作类型映射：`MERGE -> MERGE`, `SPLIT -> SPLIT`, `PROCESS/REPACK -> TRANSFORM`；
  10. **MySQL 8.4 recursive CTE 成环检测**：
      - 对每条拟生成的边 `parent -> child`，执行递归 CTE 查询是否存在从 `child` 可达 `parent` 的路径；
      - 若存在可达路径，说明加入此边将导致有向图形成环，抛出 422 `BATCH_RELATION_CYCLE`，完整回滚；
  11. 状态条件流转为 `SUBMITTED`，记录 `submission_idempotency_key`，乐观锁版本递增，批量持久化 `batch_relation` 边。

### 3.5 表现层（`BatchOperationController`）
- `POST /api/v1/batch-operations`
- `POST /api/v1/batch-operations/{operationId}/submit`
- 白名单响应封套 `SuccessEnvelope`，隐藏所有敏感/内部字段。

### 3.6 文档同步与测试覆盖
- `docs/api/openapi.yaml` 补齐；
- 修正 `batch.quantity` 描述为“初始/声明数量，可用量派生”；
- `docs/phase3/README.md` 写入第四部分；
- 单测、Web 契约测试与真实 MySQL 8.4 集成测试。

## 4. 风险评估与应对策略

| 风险点 | 影响 | 应对策略 |
|---|---|---|
| 批次并发死锁 | 多个并发操作涉及相同批次可能互相等待 | 所有涉及的批次 ID 必须严格按升序（从小到大）加锁 |
| MySQL REPEATABLE READ 快照读盲区 | 并发重试时普通 SELECT 查不到已提交行导致报错分类错误 | 统一使用带排他锁的当前读（`SELECT ... FOR UPDATE`）穿透快照读 |
| 浮点数精度漂移 | 物料平衡计算可能因为精度出现微小误差 | 统一使用 `BigDecimal` 计算，并设定精确的 `0.001 kg` 容差判断 |
| 递归 CTE 深度与性能 | 复杂大图遍历可能超时或死循环 | 限制递归层数（如 `CYCLE` 语法或 `depth < 50`），防止异常拓扑导致的死循环 |
