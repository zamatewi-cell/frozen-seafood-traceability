# 冷冻海产品溯源系统统一业务契约 v1.1

> 状态：已确认，作为当前开发基线
>
> 适用范围：高校实训项目 Demo MVP 及其后续异常链实现
>
> 优先级：如 PRD、Phase 1/Phase 3 文档、数据库设计、OpenAPI、原型或现有代码与本文冲突，以本文为准；差异应通过后续版本化变更收敛，不得修改已有 Flyway 迁移伪造历史。

## 1. 业务目标与范围

系统以可追溯物料批次为核心，连接来源、加工、批次转换、企业交接、冷链运输、自有仓储、终端销售、异常处置和消费者查询。

业务必须满足以下原则：

1. 同一物料批次跨企业保持身份稳定。
2. 只有加工、拆分、合并、分装等物料转换产生新 Batch。
3. 运输、交接、事件、温度、销售和召回各自具有唯一职责。
4. 数量变化必须由 BatchOperation、Sale 或受控处置记录支撑。
5. 正常流转与质量风险是两个独立维度。
6. 自动 TraceEvent 必须能够追溯到结构化业务事实。
7. 消费者只能看到公开白名单，并始终看到真实性与教学演练声明。

### 1.1 MVP Non-goals

- 第三方独立仓储、委托保管、货主与保管人分离；
- 真实 IoT 设备接入和设备可信认证；
- 多温区混装、多起点、多终点、多站卸货；
- Batch 部分交接；
- B2B 订单、支付、发票和结算；
- 消费者个人信息、支付信息和一物一码；
- 真实防伪、区块链、政府或海关真实对接；
- 法定召回执行和生产级合规认证。

## 2. 核心业务对象及唯一职责

### 2.1 Organization

参与供应链并承担业务责任的数据租户。

- 负责：用户、角色、组织数据权限和业务责任边界。
- 不负责：表达具体物理地点；不等同于法律货权所有人。

### 2.2 Site

Organization 管理的具体业务场所。

- 负责：表达港口、养殖场、加工厂、企业自有冷库、物流中心和门店。
- 不负责：独立登录或独立承担 Batch 责任。

### 2.3 Batch

在组成和追踪边界不变期间保持稳定身份的可追溯物料批次。

- 负责：产品、声明数量、派生剩余量、当前责任组织、流转状态和风险状态。
- 不负责：替代加工单、交接单、运单、温度记录或销售单。

### 2.4 BatchOperation

一次加工、拆分、合并或分装的物料转换单据。

- 负责：INPUT、OUTPUT、LOSS、WASTE、SAMPLE 及物料平衡。
- 不负责：企业交接、物理运输或终端销售。

### 2.5 BatchRelation

由已提交 BatchOperation 派生的不可编辑谱系边。

- 负责：表达父子 Batch 关系，支持正向追踪和反向溯源。
- 不负责：独立创建、修改或代替 BatchOperation。

### 2.6 TraceEvent

由结构化业务对象自动投影，或由受控人工提交的追加式业务事实。

- 负责：形成可读、可审计的业务时间线。
- 不负责：代替 BatchOperation、Transfer、Shipment、Sale、Alert 或 Recall。

### 2.7 Transfer

两个 Organization 之间针对一个 Batch 的责任交接凭证。

- 负责：发送、待接收、隔离、接受和拒收决定。
- 不负责：车辆、路线、装卸和运输温度。

### 2.8 Shipment

一次物理冷链运输任务。

- 负责：承运商、车辆或容器、路线、装载、发运、到达和运输温区。
- 不负责：转移 Batch 当前责任组织。

### 2.9 TemperatureRecord

某时间点对 Batch 场所环境或 Shipment 运输环境的温度测量。

- 负责：记录测量时间、数值、来源和判定依据。
- 不负责：单独证明持续超温，也不等于 Alert。

### 2.10 InspectionReport

针对 Batch 的结构化检验报告。

- 负责：记录报告主体、检测项目摘要和结论。
- 不负责：自动冻结、自动召回或验证外部机构真实性。

### 2.11 Alert

需要质量人员处理的异常工作项。

- 负责：异常原因、严重度、影响范围、确认、分派和处理结论。
- 不负责：代替原始温度测量、检验报告或 Recall。

### 2.12 Recall

对确定风险批次执行的模拟召回案件。

- 负责：召回范围、通知、库存/在途/已售数量和终态处置。
- 不负责：表示真实法定召回。

### 2.13 Sale

零售企业面向供应链体系外部消费者的终端数量出库记录。

- 负责：终端销售数量台账和售罄判断。
- 不负责：企业间买卖、订单、支付、发票或消费者身份。

### 2.14 PublicTraceCode

消费者查询一个 Batch 的不可猜测公开入口。

- 负责：把匿名查询安全地映射到一个 Batch 的公开投影。
- 不负责：代替 Batch 编号，也不提供真实性或防伪证明。

## 3. Batch 身份、编号与责任组织

### 3.1 身份规则

- Batch 跨企业交接时保持同一 ID，不创建接收方副本。
- PROCESS、SPLIT、MERGE、REPACK 等物料转换创建新的 Batch。
- Transfer、Shipment、仓储和 Sale 不复制 Batch。

### 3.2 编号规则

Batch 使用两个不同概念的编号：

- `traceBatchNo`：服务端生成、全局唯一、创建后不可修改、跨企业稳定；用于企业端追踪和业务关联。
- `externalBatchNo`：企业可选填写，可重复；保存供应商、捕捞方、进口单据或外部系统中的原始批号。

客户端不得指定或覆盖 `traceBatchNo`。`externalBatchNo` 不作为 Batch 身份，不参与 Transfer 接收冲突判断。消费者使用 PublicTraceCode，不直接依赖 `traceBatchNo`。

### 3.3 `batch.org_id` 语义

`batch.org_id` 表示当前责任持有组织，不直接宣称法律货权或第三方保管权。

- Shipment 运输期间仍由发送方负责。
- 承运商不会成为 Batch 当前责任组织。
- 只有 Transfer ACCEPTED 才把责任组织改为接收方。
- Transfer QUARANTINED 或 REJECTED 不改变责任组织。

## 4. Batch 双维状态模型

### 4.1 流转状态

`flowStatus = DRAFT / ACTIVE / CLOSED`

- DRAFT：草稿，可编辑和提交，不能正式流转。
- ACTIVE：存在可管理物料，可参与符合条件的正常业务。
- CLOSED：正常数量流转已经结束，不返回 ACTIVE。

状态机：

```text
DRAFT → ACTIVE → CLOSED
```

### 4.2 风险状态

`riskStatus = NORMAL / FROZEN / RECALLED`

- NORMAL：没有阻断正常业务的风险状态。
- FROZEN：暂停正常流转，等待调查或质量结论。
- RECALLED：已经正式进入模拟召回，作为历史风险终态保留。

状态机：

```text
NORMAL → FROZEN → NORMAL
NORMAL → FROZEN → RECALLED
NORMAL → RECALLED
```

`NORMAL → RECALLED` 仅用于证据充分的紧急召回。

### 4.3 合法组合与操作前提

允许的组合：

| flowStatus | riskStatus | 业务含义 |
|---|---|---|
| DRAFT | NORMAL | 尚未生效的草稿 |
| ACTIVE | NORMAL | 正常可流转 |
| ACTIVE | FROZEN | 尚有剩余物料，但暂停流转 |
| ACTIVE | RECALLED | 尚有剩余物料，已进入召回 |
| CLOSED | NORMAL | 已全部消耗、售罄或正常处置 |
| CLOSED | FROZEN | 流转已结束，但历史风险正在调查 |
| CLOSED | RECALLED | 已售罄或结束流转，之后进入召回 |

禁止 `DRAFT + FROZEN` 和 `DRAFT + RECALLED`。

加工、拆分、合并、分装、交接、正常接收和 Sale 的共同前提是：

```text
flowStatus = ACTIVE AND riskStatus = NORMAL
```

风险状态变化不修改数量，也不重新打开 CLOSED Batch。

## 5. 数量与剩余量规则

`declaredQuantity` 是 Batch 创建或产出时的声明数量，提交后不原地改写。

`remainingQuantity` 由已提交业务记录派生：

```text
remainingQuantity
= declaredQuantity
- operationConsumedQuantity
- soldQuantity
- disposedQuantity
```

规则：

1. BatchOperation INPUT 必须全量消耗输入 Batch，不允许部分 INPUT。
2. 需要部分加工或部分交接时，必须先 SPLIT。
3. INPUT 被全量消耗后，在同一事务中变为 CLOSED。
4. OUTPUT 在 BatchOperation 提交成功时原子变为 ACTIVE。
5. Transfer 和 Shipment 不改变数量。
6. Sale 可以多次部分销售，但不得超卖。
7. 第一次有效 Sale 后，该 Batch 不得再参与 Transfer 或 BatchOperation。
8. Sale 使剩余量归零时，Batch 自动变为 CLOSED。
9. CLOSED 后禁止加工、拆分、合并、分装、交接、仓储流转和新增 Sale；仍允许查询、审计、风险调查和召回处置。

## 6. BatchOperation 与谱系规则

- MERGE：至少两个 INPUT、一个 OUTPUT。
- SPLIT：至少一个 INPUT、两个 OUTPUT。
- PROCESS、REPACK：至少一个 INPUT、一个 OUTPUT。
- 物料平衡必须由服务端重新计算：

```text
sum(INPUT) = sum(OUTPUT) + sum(LOSS) + sum(WASTE) + sum(SAMPLE)
```

- 操作提交必须原子完成状态变更、明细固化和 BatchRelation 生成。
- 输出 Batch 在操作草稿阶段保持 DRAFT，不能绕过 BatchOperation 独立激活。
- BatchRelation 不允许自环、重复上游或有向环。
- 普通 SPLIT 不自动声称 PACK；普通 PROCESS 不自动声称 FREEZE。

## 7. Transfer 与 Shipment 最终关系

### 7.1 基数与约束

- 一个 Shipment 可以承载多个 Transfer。
- 一个 Transfer 只对应一个 Batch。
- 一个 Transfer 提交前必须绑定一个 Shipment。
- 同一个 Batch 在同一个 Shipment 中只能出现一次。
- MVP 中同一 Shipment 的 Transfer 必须具有相同发送方、接收方、起点、终点和兼容的运输温控规则。
- Transfer 表达责任交接；Shipment 表达物理运输，二者不得互相代替。

### 7.2 唯一动作顺序

```text
发货方创建 Transfer DRAFT
→ 发货方创建 Shipment PLANNED 并绑定 Transfer
→ 发货方提交 Transfer 为 PENDING
→ 承运商确认装载，Shipment 进入 IN_TRANSIT
→ 承运商确认到达，Shipment 进入 DELIVERED
→ 接收方执行 ACCEPT / QUARANTINE / REJECT
```

### 7.3 状态职责

Transfer：

```text
DRAFT → PENDING → ACCEPTED
                → QUARANTINED → ACCEPTED
                              → REJECTED
                → REJECTED
```

- DRAFT → PENDING：发货方操作，前提是已绑定 PLANNED Shipment。
- PENDING → ACCEPTED：接收方操作，前提是 Shipment 已 DELIVERED 且 Batch 风险正常。
- PENDING → QUARANTINED：接收方记录物理到货后的隔离决定。
- PENDING → REJECTED：接收方拒收。
- QUARANTINED → ACCEPTED/REJECTED：接收方根据质量结论执行。

Shipment：

```text
PLANNED → IN_TRANSIT → DELIVERED
PLANNED → CANCELLED
```

- PLANNED → IN_TRANSIT：承运商确认装载；所有关联 Transfer 必须已 PENDING。
- IN_TRANSIT → DELIVERED：承运商确认物理到达。
- IN_TRANSIT 后禁止增删 Transfer 或更换 Batch。
- Shipment 状态变化不自动修改 Transfer 状态。
- Shipment DELIVERED 不代表接收方已经验收。

## 8. 仓储边界

MVP 只支持当前责任组织使用自己 Organization 下的 `Site(COLD_STORE)`。

仓储过程中：

- Batch ID 不变；
- 当前责任组织不变；
- 数量不变；
- 温度记录绑定 Batch 和 Site；
- WAREHOUSE_IN/WAREHOUSE_OUT 记录明确的场所流转事实。

数据库中存在 `Organization(type=WAREHOUSE)` 不代表系统已经支持第三方仓储。没有委托仓储或保管任务模型时，不得用 Transfer 或 Site 模拟第三方保管业务。

## 9. Sale 与消费者查询

### 9.1 Sale 规则

Sale 仅表示 RETAILER 在 `Site(STORE)` 面向供应链外部消费者的终端数量出库。

- 企业之间即使现实中发生买卖，系统仍使用 Transfer 表达责任交接。
- 一条 Sale 只对应一个 Batch。
- 只有当前责任组织可以创建 Sale。
- Sale 不改变当前责任组织。
- 可以多次部分 Sale，直到剩余量为零。
- 第一次有效 Sale 后禁止再次加工、拆分、合并、分装或交接。
- 每次 Sale 必须自动生成 SALE TraceEvent。

### 9.2 PublicTraceCode 规则

- 一批一码，始终绑定同一个 Batch。
- 跨企业交接不换码、不复制码。
- CLOSED 后继续可查询。
- 风险状态为 RECALLED 时显示模拟召回提示。
- 公开页面不得暗示防伪、真实认证或法定合规结论。
- 最终 Batch 的公开投影应包含允许公开的祖先谱系事实，而不是只显示当前 Batch 自身事件。

## 10. 温度、告警与异常收货

### 10.1 温度归属

- 仓储及其他固定场所温度绑定 Batch 和 Site。
- 在途运输温度绑定 Shipment。
- Shipment 受影响 Batch 通过 `Shipment → Transfer → Batch` 反查。
- 单点温度越界不等于持续超温。

只有连续越界达到规则允许时长，或规则允许时长为 0，才能形成持续超温 Alert。规则选择必须基于产品、业务阶段和测量业务时间，并保留匹配规则版本。

### 10.2 持续超温处理顺序

1. 记录 Shipment TemperatureRecord。
2. 达到持续超限条件时创建 Shipment 级 Alert。
3. 快照或可靠确定 Shipment 中受影响的 Batch。
4. 受影响 Batch 的 `riskStatus` 变为 FROZEN，`flowStatus` 不变。
5. Shipment 可以按应急规则继续到达或停止，但责任组织仍为发送方。
6. 到达后接收方选择 QUARANTINE 或 REJECT，不得直接正常使用被冻结物料。
7. 当前责任组织质量管理员负责调查、放行、拒收或启动 Recall。

### 10.3 QUARANTINED 语义

- 货物已经物理到达并记录实收数量与隔离 Site。
- Batch 当前责任组织仍为发送方。
- 接收方不得加工、销售或再次交接。
- 接收方可以查看相关 Alert 并提交检查证据。
- 风险解除后可以 ACCEPT；风险不通过可以 REJECT 或进入 Recall。

## 11. TraceEvent 自动投影规则

自动事件必须保存结构化来源对象类型和 ID。无法可靠推导的事件保持受控人工录入。

| TraceEvent | 生成方式 | 唯一可靠触发源 |
|---|---|---|
| SOURCE | 自动 | SOURCE Batch 从 DRAFT/NORMAL 激活为 ACTIVE/NORMAL，且来源字段完整 |
| PROCESS | 自动 | PROCESS BatchOperation 成功提交 |
| FREEZE | 受控人工 | 明确完成速冻业务并提交结构化速冻信息 |
| PACK | 自动或受控人工 | REPACK 提交可自动生成；普通 SPLIT 不生成 |
| WAREHOUSE_IN | 受控人工 | 操作员记录进入当前组织自有冷库 |
| WAREHOUSE_OUT | 受控人工 | 操作员记录离开当前组织自有冷库 |
| TRANSPORT | 自动 | Shipment 从 PLANNED 进入 IN_TRANSIT；对每个关联 Batch 生成一条 |
| ARRIVAL | 自动 | Shipment 从 IN_TRANSIT 进入 DELIVERED；对每个关联 Batch 生成一条 |
| SALE | 自动 | Sale 成功提交 |

附加规则：

- Transfer PENDING 不生成 TRANSPORT。
- Transfer ACCEPTED 不生成 ARRIVAL，因为 ARRIVAL 只表示物理到达。
- 普通 PROCESS 不推导 FREEZE。
- SPLIT/MERGE 的谱系事实由 BatchOperation 和 BatchRelation 表达，不伪装成 PACK 或 PROCESS。
- 人工普通事件接口不得单独伪造 SOURCE、PROCESS、TRANSPORT、ARRIVAL 或 SALE。

## 12. 正常业务基线

以 1000kg 冷冻海产品原料为例：

| 步骤 | 操作角色 | 业务动作 | Batch、数量与责任变化 | 单据与事件 |
|---|---|---|---|---|
| 1 | 来源企业 | 创建来源 Batch B0 | B0=1000kg，DRAFT/NORMAL，责任组织为来源企业 | 创建 Batch 草稿 |
| 2 | 来源企业 | 激活 B0 | B0=ACTIVE/NORMAL；系统生成 traceBatchNo | 自动 SOURCE |
| 3 | 来源企业 | 创建 Transfer T0 | B0 不变；T0=DRAFT | 创建 Transfer |
| 4 | 来源企业 | 创建 Shipment S0 并绑定 T0 | S0=PLANNED；T0 仍为 DRAFT | 创建 Shipment、建立关联 |
| 5 | 来源企业 | 提交 T0 | T0=PENDING；B0 责任组织不变 | 更新 Transfer |
| 6 | 承运商 | 装载并发运 S0 | S0=IN_TRANSIT；B0 责任组织仍为来源企业 | 自动 TRANSPORT |
| 7 | 承运商 | 确认到达 S0 | S0=DELIVERED；T0 仍为 PENDING | 自动 ARRIVAL |
| 8 | 加工企业 | ACCEPT T0 | T0=ACCEPTED；B0 责任组织变为加工企业 | 不重复 ARRIVAL |
| 9 | 加工企业 | 提交 PROCESS OP1 | B0 全量消耗并 CLOSED；产生 B1=960kg ACTIVE；30kg LOSS、10kg SAMPLE | 自动 PROCESS |
| 10 | 加工企业 | 明确记录速冻完成 | B1 数量和状态不变 | 受控人工 FREEZE |
| 11 | 加工企业 | 提交 SPLIT OP2 | B1 CLOSED；产生 B2=600kg、B3=360kg，均 ACTIVE | 生成谱系，不自动 PACK |
| 12 | 加工企业 | 自有冷库入库、出库 | B2/B3 数量和责任组织不变 | 人工 WAREHOUSE_IN/OUT |
| 13 | 加工企业 | 创建 T2、T3 和 S1 | 每张 Transfer 对应一个 Batch；S1 同时绑定 T2/T3 | 创建交接与运输单 |
| 14 | 加工企业 | 提交 T2、T3 | T2/T3=PENDING | 更新 Transfer |
| 15 | 承运商 | 发运 S1 | S1=IN_TRANSIT；责任组织仍为加工企业 | B2/B3 各一条 TRANSPORT |
| 16 | 承运商 | 到达 S1 | S1=DELIVERED；T2/T3 仍为 PENDING | B2/B3 各一条 ARRIVAL |
| 17 | 零售企业 | ACCEPT T2、T3 | B2/B3 责任组织变为零售企业 | 不重复 ARRIVAL |
| 18 | 零售企业 | 激活公开追溯码 | Batch 状态和数量不变 | 创建或恢复 PublicTraceCode |
| 19 | 零售企业 | 提交终端 Sale | B2 售 600kg、B3 售 360kg；剩余量归零并 CLOSED | 自动 SALE |
| 20 | 消费者 | 扫码查询 | 不产生业务写入 | 查看公开谱系和时间线 |

## 13. 异常业务基线

在 S1 运输途中持续超温时：

1. TemperatureRecord 绑定 S1，而不是分别复制到 B2、B3。
2. 连续越界达到规则时长后创建 Shipment 级 Alert。
3. 通过 S1 关联的 T2、T3 确定 B2、B3 为受影响 Batch。
4. B2、B3 变为 `ACTIVE/FROZEN`，责任组织仍为加工企业。
5. 承运商确认物理到达，S1 进入 DELIVERED，并产生 ARRIVAL。
6. 零售企业选择 QUARANTINE，不执行 ACCEPT。
7. 加工企业质量管理员负责确认异常。
8. 反向追溯 B2/B3 → B1 → B0。
9. 正向圈定后续 Transfer、Sale 和 PublicTraceCode。
10. 调查合格时恢复 NORMAL，Transfer 可以 ACCEPT。
11. 调查不合格时可以 REJECT；需要召回时进入 RECALLED。
12. 已经售罄的 Batch 保持 flowStatus=CLOSED，同时记录 riskStatus=RECALLED。
13. PublicTraceCode 对应 Batch 进入 RECALLED 后，消费者页面立即显示模拟召回提示。
14. Recall 关闭后，Batch 仍保留 RECALLED 历史；消费者可见公开处置结论。

## 14. 权限与组织责任边界

- 平台管理员维护组织、场所、账号和基础配置，不代替企业执行业务。
- 当前责任组织可以执行符合状态条件的正常 Batch 业务。
- 发货方负责创建、绑定和提交 Transfer。
- 承运商只负责 Shipment 装载、发运、到达、运输温度和运输证据。
- 接收方负责 ACCEPT、QUARANTINE 或 REJECT。
- 当前责任组织质量管理员负责冻结、放行和 Recall。
- QUARANTINED 期间接收方只有隔离管理和证据提交权限。
- 历史参与组织可查询自己的历史记录，但不能修改已经转出的 Batch。
- 当前责任组织和授权质量人员可查看经过字段过滤的完整追溯链。
- 消费者只能访问 PublicTraceCode 对应的公开白名单投影。

## 15. 契约一致性不变量

后续 PRD、数据库、OpenAPI、前端和测试必须共同遵守：

1. Transfer 不承担 Shipment 职责。
2. Shipment 不改变 Batch 当前责任组织。
3. TraceEvent 不代替业务单据或数量台账。
4. SALE TraceEvent 不单独承担销售数量扣减。
5. CLOSED 与 RECALLED 可以同时存在，二者不再互相覆盖。
6. 企业外部批号不得充当全局 Batch 身份。
7. 输出 Batch 不得在 BatchOperation 提交前独立激活。
8. ARRIVAL 只表示运输到达，不表示接收方接受。
9. 单点温度不得被描述为持续超温。
10. 未实现的业务对象和页面不得在文档或演示中宣称已完成。
