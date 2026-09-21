# Demo MVP 实施路线图

> 状态：已确认，作为当前实施顺序基线
>
> 业务规则来源：[统一业务契约 v1.1](BUSINESS_CONTRACT_V1.1.md)
>
> 目标：尽快形成一条前后端真实可操作、3～5 分钟能够演示完整业务流转的纵向链路。

本文只回答“当前实现差距是什么、按什么顺序实施、每个阶段如何验收”。业务对象定义、状态含义、数量规则和异常处置规则不在此重复，以统一业务契约 v1.1 为准。

## 1. 当前仓库基线

截至本路线图确认时：

- 已实现后端：Session Auth、Product、TemperatureRule、Batch 草稿生命周期、BatchOperation、BatchRelation、TraceEvent、PublicTraceCode、Transfer。
- 已实现生产 Vue：消费者 PublicTraceCode 查询页。
- 仅存在数据库表、没有可执行 Java/API：Shipment、TemperatureRecord、Alert、InspectionReport、Recall。
- 完全不存在业务表和实现：Sale。
- Transfer 实体虽有 `shipmentId`，但当前请求、状态流转和权限并未真正使用 Shipment。
- 当前 PublicTrace 投影只查询目标 Batch 自身事件，没有聚合祖先 Batch 谱系。
- V1～V7 Flyway 已是历史基线，后续只能增加迁移，不能改写。

因此，数据库中存在表或 OpenAPI 中存在占位描述，不等于该能力已经实现。

## 2. Demo MVP 阶段划分

### 2.1 Phase A：正常业务闭环

Phase A 必须完成：

```text
来源企业建立 Batch
→ 发起 Transfer
→ Shipment 发运和到达
→ 加工企业接受
→ PROCESS / SPLIT
→ 自有冷库存储
→ 再次 Shipment + Transfer
→ 零售企业接受
→ 激活 PublicTraceCode
→ 终端 Sale
→ 消费者查询
```

Phase A 固定使用 `riskStatus=NORMAL`，不开放风险状态写入。仓储只使用当前责任组织自己的 `Site(COLD_STORE)`。没有真实温度数据时，消费者页面必须继续显示 `INSUFFICIENT_DATA`，不得为了演示虚构“全程温控正常”。

### 2.2 Phase B：异常与召回闭环

Phase B 在 Phase A 稳定后实施：

- Shipment 温度记录；
- 持续超限判定；
- Alert；
- Batch FROZEN/解除冻结；
- Transfer QUARANTINED；
- InspectionReport；
- Recall；
- 正向/反向影响范围；
- 消费者模拟召回提示。

第三方独立仓储仍为 Non-goal。

## 3. 当前实现“保留 / 修改 / 新增”矩阵

| 业务能力 | 当前真实状态 | 可以保留 | 必须修改 | 必须新增 | 生产前端 |
|---|---|---|---|---|---|
| Auth | 登录、登出、CSRF、`/me`、Session 和活性复核已实现 | 后端认证、安全主体、异常响应和测试 | 后端原则上不改业务语义 | Vue 登录、Session 状态、CSRF 写请求封装、路由守卫 | 不存在 |
| Product | 产品和温控规则 Controller/Service/Mapper 已实现 | Phase A 使用产品列表/详情 | Phase A 不扩展温控规则 | 建批表单中的产品选择 | 不存在 |
| Batch | 草稿、修改、提交、列表、详情已实现 | 幂等、乐观锁、组织隔离、基础字段校验 | 单状态改双状态；双编号；查询和响应；SOURCE 自动事件 | 企业批次列表、详情和来源建批页 | 不存在 |
| BatchOperation | MERGE/SPLIT/PROCESS/REPACK、平衡校验、DAG 和锁已实现 | 物料平衡、幂等、环检测、关系表 | 禁止部分 INPUT；关闭输入；输出原子激活；PROCESS 自动事件 | 输出草稿协议、操作详情查询、加工/拆分向导 | 不存在 |
| TraceEvent | 追加式创建、更正和列表已实现 | 不可变更正链、受控枚举、幂等 | 跨组织历史读取；ARRIVAL 触发源；限制人工伪造自动事件 | SOURCE/PROCESS/TRANSPORT/ARRIVAL/SALE 自动投影 | 不存在 |
| Transfer | DRAFT/PENDING/ACCEPTED/REJECTED 生命周期已实现 | 幂等、事务、并发、双方列表、责任组织转移 | 提交前绑定 Shipment；接受前要求 DELIVERED；移除 ACCEPT→ARRIVAL；移除接收方批号冲突 | `shipmentId` 响应和绑定校验 | 不存在 |
| Shipment | 只有 V1 表，没有 Java/API | 表名和主要概念字段可参考 | 现表不支持完整 PLANNED 生命周期 | Domain、Mapper、Service、Controller、OpenAPI、测试和页面 | 不存在 |
| Warehouse events | 通用 TraceEvent 可写 WAREHOUSE_IN/OUT | 事件类型和 Site 归属校验思路 | 收紧结构化字段和责任组织校验 | Site 查询 API、仓储操作页 | 不存在 |
| Sale | 无表、无 Java、无 API | 无 | 无旧实现可改 | 最小 Sale 台账、数量校验、自动关闭、SALE 事件和页面 | 不存在 |
| PublicTraceCode | 激活、停用、匿名查询已实现 | 不可猜测码、一批一码、白名单与真实性声明 | 双状态投影、权限语义、祖先谱系聚合 | 企业端查询当前码接口和管理页 | 不存在 |
| Consumer Web | 响应式查询页和测试已实现 | 查询、错误状态、温度不足说明、召回展示框架 | 双状态类型、时间线标签和谱系摘要 | 与真实 Phase A 全链端到端整合 | 已实现 |

## 4. Phase 0 业务纠偏影响范围

Phase 0 只处理会阻塞所有后续纵向切片的基础模型，不实现 Shipment、Sale、温度、告警或召回业务。

### 4.1 Batch 双状态

主要影响：

- `batch/domain/Batch.java`
- `batch/domain/BatchStatus.java`，目标拆为 Flow/Risk 两个枚举
- `BatchApplicationService`
- `BatchMapper`
- Batch create/patch/query/response DTO
- `BatchOperationApplicationService`
- `TransferApplicationService`
- `TraceEventApplicationService`
- `PublicTraceApplicationService`
- PublicTrace 投影 DTO
- OpenAPI Batch/PublicTrace schema
- Batch、BatchOperation、Transfer、TraceEvent、PublicTrace 全部相关测试
- Vue 消费者类型、状态展示和 fixture

历史状态回填基线：

| 旧 status | flowStatus | riskStatus |
|---|---|---|
| DRAFT | DRAFT | NORMAL |
| ACTIVE | ACTIVE | NORMAL |
| FROZEN | ACTIVE | FROZEN |
| RECALLED | ACTIVE | RECALLED |
| CLOSED | CLOSED | NORMAL |

### 4.2 Batch 双编号

实施基线：

1. 现有 `batch_no` 作为历史企业输入值迁移到 `external_batch_no`。
2. 新增全局唯一 `trace_batch_no`，为历史行生成稳定值。
3. 新建 Batch 时由服务端生成 traceBatchNo；客户端只可提交 externalBatchNo。
4. 删除 Transfer 接收时的同组织批号冲突检查。
5. 创建幂等哈希包含 externalBatchNo，不包含服务端生成的 traceBatchNo。

主要影响：Batch Domain/DTO/Service/Mapper、TraceDataMasker、PublicTrace 投影、Transfer 接收逻辑、OpenAPI、前端表单及测试夹具。

### 4.3 PublicTraceCode 迁移结论

不重新生成公开码，不迁移 token，不复制 PublicTraceCode。

- 保留现有 `public_id`、`token_hash`、`batch_id` 和一批一码约束。
- `org_id` 在 Phase A 暂作为当前操作权限缓存，不作为公开码身份。
- Transfer ACCEPTED 可以继续同步权限字段。
- 激活条件改为 `ACTIVE + NORMAL`。
- CLOSED 后已激活码继续可查询。
- PublicTraceCode 自身的 RECALLED 状态清理不阻塞 Phase A，可与 Phase B 风险链一并收敛。

## 5. Phase A 关键改造范围

### 5.1 Transfer 与 Shipment

- Transfer DRAFT 可先创建，但提交 PENDING 前必须绑定 PLANNED Shipment。
- Shipment 创建时记录发送组织、承运组织、起止 Site、车辆或容器。
- Shipment 发运前，全部关联 Transfer 必须为 PENDING。
- Shipment 到达前，Transfer 不可 ACCEPT/REJECT。
- Shipment IN_TRANSIT 后禁止增删 Transfer。
- 一个 Shipment 支持多个 Transfer；同一 Batch 只能出现一次。
- `loaded_at` 在 PLANNED 阶段允许为空，发运时写入；`unloaded_at` 在到达时写入。
- TRANSPORT 和 ARRIVAL 按 Shipment 中的每个 Batch 自动生成。

现有 Transfer 的幂等、组织隔离、锁顺序和责任组织原子转移思路继续保留。

### 5.2 ARRIVAL 迁移

- 删除 Transfer ACCEPTED 时产生 ARRIVAL 的副作用。
- Shipment DELIVERED 成为 ARRIVAL 唯一自动触发源。
- 事件幂等身份改为 Shipment ID + Batch ID。
- 公开标签使用“冷链运输到达”，不再使用“到货验收”。

重点回归：`TransferApplicationServiceTest`、`TransferMysqlIntegrationTest`、TraceEvent/PublicTrace 测试及消费者时间线测试。

### 5.3 BatchOperation 全量关闭与输出激活

- INPUT 数量必须等于当前全部剩余量。
- 操作创建时由服务端生成 OUTPUT DRAFT，而不是引用客户端预先激活的输出 Batch。
- 操作提交时原子关闭全部 INPUT、激活全部 OUTPUT、固化关系和事件。
- 普通 Batch submit 必须阻止作为 Operation OUTPUT 的草稿绕过操作独立激活。
- PROCESS 自动生成事件；SPLIT 只生成谱系，不生成 PACK。

### 5.4 Sale

Phase A 新增最小不可变 Sale 台账：

- 一条 Sale 对应一个 Batch；
- 仅 RETAILER 的 STORE Site 可提交；
- 支持多次部分销售；
- 服务端锁定 Batch 后校验剩余量，禁止并发超卖；
- 第一次 Sale 后禁止 Transfer 和 BatchOperation；
- 售罄时原子关闭 Batch；
- 成功提交后自动产生 SALE TraceEvent。

### 5.5 消费者谱系聚合

当前消费者投影只查询目标 Batch 自身事件。Phase A 必须使用已有 BatchRelation 递归获得祖先 Batch，并聚合：

- 祖先有效事件；
- BatchOperation 的结构化谱系事实；
- 当前 Batch 的仓储、运输和销售事件。

这只是消费者闭环所需的只读谱系摘要，不建设企业端完整图谱可视化。

### 5.6 演示基础资料

企业操作页需要最小只读目录能力：

- 可选接收 Organization；
- 可选发送方、接收方和自有冷库 Site；
- 可选 Product。

演示需要确定性的来源、加工、承运、零售组织及账号、场所和产品。基础资料应由显式 demo fixture 准备，不写入生产 Flyway，不硬编码生产默认密码，也不预造业务单据。

## 6. Phase A 最小企业端页面

标记：`现` 表示已有 API，`改` 表示修改已有 API，`新` 表示新增 API。

| 页面 | 用户角色 | 入口 | 核心按钮 | API | 成功后的下一页 |
|---|---|---|---|---|---|
| 登录 `/login` | 所有企业用户 | 未登录访问企业路由 | 登录 | 现：CSRF、login、me | 工作台 |
| 工作台 `/app` | 已登录用户 | 登录成功 | 我的批次、待接收、运输任务、新建来源批次 | 现：batches/transfers；新：shipments | 对应列表 |
| 批次列表 `/app/batches` | 已登录用户 | 工作台 | 筛选、查看、新建来源批次 | 改：batches 按 flow/risk 筛选 | 详情或建批 |
| 批次详情 `/app/batches/:id` | 当前或历史参与组织 | 列表/操作结果 | 交接、加工、仓储、Sale、追溯码 | 现/改：batch、events；新：lineage summary、current public code | 对应业务页 |
| 新建来源批次 | SOURCE OPERATOR | 批次列表 | 保存草稿、提交激活 | 改：create/submit batch；现：products | 批次详情 |
| 加工/拆分向导 | PROCESSOR OPERATOR | 输入 Batch 详情 | 定义输出、校验平衡、创建并提交 | 改：batch operations；新：operation detail | 输出 Batch 详情 |
| 发出交接 | 当前责任组织 OPERATOR | Batch 详情 | 选择接收方、创建 Transfer | 新：组织目录；现：create transfer | Shipment 页 |
| 待接收交接 | 接收方 OPERATOR | 工作台 | 查看、接受、拒绝 | 改：inbound transfers、accept/reject | Batch 详情或列表 |
| Shipment 创建/发运/到达 | 发货方或承运商 OPERATOR | Transfer 详情/工作台 | 创建、绑定、提交 Transfer、发运、到达 | 新：Shipment 全路径；改：Transfer submit | 运输详情或待接收列表 |
| 自有冷库入库/出库 | PROCESSOR OPERATOR | Batch 详情 | 入库、出库 | 新：own sites；改：batch events | Batch 详情 |
| 终端 Sale | RETAILER OPERATOR | Batch 详情 | 输入数量、提交销售 | 新：sales | Batch 详情/追溯码 |
| PublicTraceCode 管理 | 当前责任组织 OPERATOR | Batch 详情 | 激活、复制链接、停用、打开查询页 | 现：activate/disable；新：get current code | 消费者页 |
| 消费者查询 `/trace/:id` | 匿名消费者 | 扫码/链接 | 查询其他码 | 改：public trace projection | 当前页 |

前端只需要两个布局：消费者 `PublicLayout` 和企业 `EnterpriseLayout`。Phase A 不引入完整管理后台、复杂图谱编辑器或不必要的状态管理框架。

## 7. 纵向 Slice 开发顺序

每个 Slice 必须同时交付后端、OpenAPI、Vue、权限、测试和手工演示路径。任何 Slice 都不得以“后端已完成、页面以后再写”结束。

### Slice 0：业务纠偏基础与企业壳

- 后端：Batch 双状态、双编号、PublicTrace 状态字段、最小目录读取。
- OpenAPI：同步 Batch 和公开投影字段。
- Vue：登录、Session、企业布局、工作台、批次列表和详情。
- 权限：登录态读取和组织数据范围。
- 测试：迁移回填、Batch/Auth、Vue 路由和 API client。
- 手工验收：真实登录后能看到正确双状态、traceBatchNo 和 externalBatchNo。

### Slice 1：来源建批

- 后端：服务端生成 traceBatchNo；来源 Batch 激活自动产生 SOURCE。
- OpenAPI：更新 Batch create/submit 契约。
- Vue：来源建批表单和提交结果页。
- 权限：SOURCE Organization 的 OPERATOR。
- 测试：幂等、重复 externalBatchNo、SOURCE 唯一性、前后端 E2E。
- 手工验收：从页面创建并激活 1000kg B0。

### Slice 2：Transfer + Shipment

- 后端：Shipment 全层；绑定、提交、发运、到达、接受；ARRIVAL 迁移。
- OpenAPI：Shipment 全路径及 Transfer 响应/约束。
- Vue：发出交接、Shipment、待接收交接。
- 权限：发送方、承运商、接收方动作分离。
- 测试：状态机、越权、幂等、事务、真实 MySQL、Vue E2E。
- 手工验收：B0 从来源企业通过 Shipment 真实转给加工企业。

### Slice 3：PROCESS / SPLIT

- 后端：输出草稿、原子提交、输入关闭、PROCESS 事件和谱系摘要。
- OpenAPI：Operation 创建、提交和详情契约。
- Vue：加工/拆分向导。
- 权限：当前责任 PROCESSOR 的 OPERATOR。
- 测试：1000=960+30+10、960=600+360、并发与事务回滚。
- 手工验收：B0→B1→B2/B3，B0/B1 均正确 CLOSED。

### Slice 4：自有冷库

- 后端：Site 查询和 WAREHOUSE_IN/OUT 结构校验。
- OpenAPI：Site read 和仓储事件字段。
- Vue：自有冷库入库/出库页。
- 权限：当前责任组织；Site 必须属于本组织且为 COLD_STORE。
- 测试：跨组织 Site、非法状态、事件幂等、Vue E2E。
- 手工验收：B2/B3 完成入库和出库，数量和责任组织不变。

### Slice 5：终端 Sale

- 后端：Sale 台账、剩余量、首次销售锁定、售罄关闭和 SALE 事件。
- OpenAPI：Sale create/list。
- Vue：终端 Sale 页面。
- 权限：当前责任 RETAILER 的 OPERATOR，Site 必须为本组织 STORE。
- 测试：部分销售、超卖、并发、售罄关闭和后续流转禁止。
- 手工验收：零售企业把 B2/B3 销售至剩余量 0。

### Slice 6：PublicTraceCode 与消费者全链

- 后端：查询当前公开码、祖先谱系和事件聚合、双状态输出。
- OpenAPI：PublicTraceCode 管理和公开投影。
- Vue：公开码管理页和消费者页升级。
- 权限：企业端当前责任组织；公开查询匿名只读。
- 测试：脱敏、祖先去重、稳定排序和完整 Playwright 链。
- 手工验收：扫描 B2 公开码可以看到 B0→B1→B2 的允许公开事实。

## 8. 三阶段实施路线

### Phase 0：业务纠偏基础重构

- 目标：固定 Batch 身份、状态和编号语义，并交付可登录、可读取的企业端骨架。
- 修改模块：Batch、PublicTrace 状态投影、最小 Organization/Site 目录、Vue Session 和企业布局。
- 页面：登录、工作台、批次列表、批次详情。
- 数据库迁移：下一可用版本，当前预计为 V8；只处理双状态、双编号、回填和约束替换。
- 主要风险：旧状态映射、API 破坏性变更、测试夹具批量更新。
- 完成标准：旧单状态和旧 batchNo 不再参与业务判断，前后端字段一致，真实 MySQL 迁移验证通过。

### Phase A：正常业务闭环

- 目标：从空业务数据开始，通过生产 Vue 和真实 API 完成正常主链。
- 修改模块：来源 Batch、Transfer、Shipment、BatchOperation、TraceEvent、仓储事件、Sale、PublicTraceCode、消费者谱系投影。
- 页面：本路线图第 6 节列出的全部页面。
- 数据库迁移：预计 V9 调整 Shipment/Transfer；预计 V10 新增 Sale。实际编号以实施时仓库下一可用版本为准。
- 主要风险：跨单据状态机、操作原子性、多 Transfer 装运、Sale 并发超卖、公开谱系排序。
- 完成标准：完整 Playwright/E2E 通过；业务单据不靠 SQL 手工改状态；准备好的多账号会话下可在 3～5 分钟演示完整链。

### Phase B：温度异常与模拟召回

- 目标：在稳定的 Shipment、谱系和 Sale 数量台账上完成异常闭环。
- 修改模块：TemperatureRecord、持续超限计算、Alert、Transfer QUARANTINED、InspectionReport、风险状态、Recall、影响范围。
- 页面：温度记录、告警、隔离收货、冻结/放行、正反向追溯、模拟召回、消费者风险提示。
- 数据库迁移：增强现有 temperature/alert/recall 表约束，增加必要影响范围记录，并扩展 Transfer 状态约束。
- 主要风险：连续温度区间算法、隔离责任边界、售罄后召回、消费者演练声明。
- 完成标准：持续超温能冻结 Shipment 关联 Batch；接收方隔离；质量管理员处置；系统圈定上下游；消费者显示模拟召回提示。

## 9. 3～5 分钟演示验收路径

演示前仅准备组织、账号、场所和产品等基础资料，不预造业务单据。建议使用四个独立浏览器 Profile 保持来源、加工、承运和零售账号的真实 Session，避免增加身份切换后门。

1. 来源窗口登录，创建并激活 B0。
2. 来源窗口创建 T0/S0 并提交 T0。
3. 承运窗口发运、到达 S0。
4. 加工窗口接受 T0，完成 PROCESS、SPLIT、冷库入库和出库。
5. 加工窗口创建 T2/T3 和共同的 S1。
6. 承运窗口发运、到达 S1。
7. 零售窗口接受 T2/T3，激活追溯码并提交 Sale。
8. 消费者窗口扫码查看来源、加工谱系、仓储、运输、到达和销售事实。

验收时必须确认：

- B0、B1 因全量操作消耗而 CLOSED；
- B2、B3 只在 Transfer ACCEPTED 时改变责任组织；
- B2、B3 售罄后 CLOSED；
- 两个 Transfer 确实装入同一个 Shipment；
- 消费者能看到祖先谱系；
- 无温度数据时明确显示数据不足；
- 全程不使用 Postman 或 SQL 代替页面业务操作。

## 10. 当前暂停项

在 Phase A 完成并通过完整演示验收以前，继续暂停：

- FR-COLD-001A 及其他温度实现；
- Alert；
- QUARANTINED；
- Freeze/Recall；
- InspectionReport；
- 第三方仓储；
- 企业端完整图谱可视化；
- v1.1 之外的新业务对象。

实施必须按 Slice 0 → 1 → 2 → 3 → 4 → 5 → 6 推进，不再采用“连续完成多个后端模块，最后才补企业前端”的方式。
