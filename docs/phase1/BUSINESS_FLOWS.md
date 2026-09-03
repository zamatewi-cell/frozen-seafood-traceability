# 业务流程与状态机

## 1. 主业务流程

```mermaid
flowchart LR
  S[来源批次草稿] --> S1[提交来源事件]
  S1 --> O[创建加工操作]
  O --> V{数量与单位平衡?}
  V -- 否 --> E[返回逐项校验错误]
  V -- 是 --> P[生成成品批次与谱系边]
  P --> W[冷库入库/出库事件]
  W --> T[运输与温度记录]
  T --> H[发送交接]
  H --> A{接收方确认?}
  A -- 拒绝 --> R[记录拒绝原因/差异]
  A -- 接受 --> D[完成交接并更新持有组织]
  D --> Q[启用公开追溯码]
  Q --> C[消费者白名单查询]
```

批次操作的校验式：`Σ 标准化投入 = Σ 标准化产出 + 损耗 + 废弃 + 留样`。如果单位不同，必须先使用已记录的换算规则得到同一计量维度。

## 2. 批次状态机

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> ACTIVE: 提交并通过校验
  DRAFT --> [*]: 删除草稿
  ACTIVE --> FROZEN: 质量冻结
  FROZEN --> ACTIVE: 复核后解冻
  FROZEN --> RECALLED: 发起模拟召回
  ACTIVE --> RECALLED: 紧急召回
  ACTIVE --> CLOSED: 正常结束
  RECALLED --> CLOSED: 完成回收与处置
```

| 当前状态 | 允许动作 | 禁止动作 |
|---|---|---|
| `DRAFT` | 编辑、删除、提交 | 对外查询、交接、下游操作 |
| `ACTIVE` | 记事件、交接、拆合、公开查询 | 物理删除已提交事件 |
| `FROZEN` | 查看、处置、解冻、召回 | 新发货、接收、销售、生成下游批次 |
| `RECALLED` | 通知、回收、处置、关闭 | 新业务流转 |
| `CLOSED` | 只读、追加更正/审计说明 | 普通业务变更 |

## 3. 交接状态机

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> PENDING: 发送方提交
  PENDING --> ACCEPTED: 接收方验收
  PENDING --> REJECTED: 接收方拒收并说明原因
  REJECTED --> DRAFT: 发送方修正后重建
```

- 提交时锁定批次、数量、单位、发货方、收货方和业务发生时间快照。
- 接收方接受时记录签收数量、差异、业务签收时间和系统记录时间。
- `ACCEPTED/REJECTED` 为终态；重复请求使用幂等键返回原结果。
- 冻结或召回批次不能提交或接受交接。

## 4. 告警与异常闭环

```mermaid
stateDiagram-v2
  [*] --> OPEN
  OPEN --> ACKNOWLEDGED: 质量人员确认
  ACKNOWLEDGED --> PROCESSING: 冻结/调查
  PROCESSING --> RESOLVED: 处置完成
  OPEN --> DISMISSED: 证实为误报
  ACKNOWLEDGED --> DISMISSED: 复核后驳回
```

```mermaid
sequenceDiagram
  participant R as 温控规则引擎
  participant A as 告警服务
  participant Q as 质量管理员
  participant T as 追溯服务
  participant C as 模拟召回服务
  R->>A: 记录越界 + 规则版本
  A->>Q: 创建 OPEN 告警
  Q->>A: 确认告警
  Q->>T: 冻结当前批次及选定影响范围
  T-->>Q: 返回上游/下游/库存/在途范围
  Q->>C: 确认范围并发起模拟召回
  C-->>Q: 生成通知、数量与处置台账
  Q->>A: 复核并关闭
```

## 5. 追溯事件更正

已提交事件不可覆盖：

1. 用户选择需要更正的事件并填写原因。
2. 系统复制原记录为更正输入，保存修改后的新版本。
3. 新记录通过 `corrects_event_id` 指向原记录；原记录状态变为 `CORRECTED`，但仍可读。
4. 审计日志保存字段级差异摘要；附件通过新关联追加，原附件不物理删除。

## 6. 幂等与并发

- 创建批次操作、接受/拒绝交接、冻结、发起召回必须支持 `Idempotency-Key`。
- 可变聚合使用 `version` 乐观锁；冲突返回 `409 CONFLICT` 和稳定错误码。
- 同一批次的状态变化、批次操作和交接确认在单数据库事务中完成。

