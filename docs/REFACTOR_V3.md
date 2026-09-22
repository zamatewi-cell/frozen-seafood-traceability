# 冷冻海产品全链路溯源平台 —— V3 重构开发文档

> 基于用户最终确认的业务模型，对现有系统进行重构。
> 核心变化：**库存批次为第一公民 + 订单级备注流程 + 收货入库生成码 + 树状图溯源**
> 可直接重构，不保留旧逻辑的兼容层。

---

## 0. 业务模型最终版（确认基线）

### 0.1 角色与栏目结构

| 角色 | 类型 | 栏目 |
|---|---|---|
| `market_op` 超市采购员 | 终端 | ①向上游下单 ②库存显示 |
| `shop_op` 电商商家运营 | 终端 | ①向上游下单 ②库存显示 |
| `whl_op` 批发分装开单员 | 非终端 | ①向上游下单 ②库存显示 ③向下游出货 |
| `plant_op` 加工厂操作员 | 非终端 | ①向上游下单 ②库存显示 ③向下游出货（多质检） |
| `capt_ship` 捕捞船长 | 非终端 | ①向上游下单 ②库存显示 ③向下游出货 |
| `plant_qa` / `market_qa` | 质管 | 质检工作台 |
| `sys_admin` | 系统 | 系统管理 |

**上下游关系**（下单方向）：
```
捕捞船长 → 加工厂操作员 → 批发分装开单员 → 终端（超市采购员/电商商家运营）
```
终端两个角色层级相同，互不上下游。

### 0.2 核心规则

1. **溯源码生成时机**：终端收货入库那一刻生成，下游不再补充
2. **库存批次**：每次收货 = 一批 = 一个分支；两次收货 = 两批 = 两个分支
3. **备注流程**：订单级别（不是批次级）。一个订单挂一条备注时间线，上下游实时可见
4. **树状图**：每个环节若用 N 批合成一个订单，该环节就有 N 个分支；多环节叠加形成树
5. **闭环**：每个环节库存里的货一定带全部上游溯源

### 0.3 订单履约流程（以"超市向批发下单 250kg 瑶柱"为例）

```
[超市采购员] 向上游下单 250kg → 订单A（PLACED）
        ↓
[批发分装开单员] 向下游出货栏看到订单A → 确认接收 → 订单转到"出货中"
        ↓
[批发分装开单员] 出货栏 4 个操作：
   ①备注流程（"正在调货""运输中"…）← 超市实时可见
   ②取消订单
   ③确认出货 → 从库存选批次分配
   ④分配库存批次：库存有 100kg(前天批)+150kg(昨天批) → 两批分配给订单A
        ↓
[批发分装开单员] 交付完成 → 超市收货入库 → 生成溯源码
        ↓
[超市采购员] 查溯源码 → 树状图：订单A → 2 个分支（前天批/昨天批）→ 每批溯源链
```

捕捞/加工厂环节同上，加工厂多一个"质检"子流程（出货前必须质检合格）。

---

## 1. 数据模型变更

### 1.1 新增表

#### `order_progress_note`（订单备注时间线）

```sql
CREATE TABLE `order_progress_note` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT,
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '所属订单ID',
    `order_type` VARCHAR(16) NOT NULL COMMENT 'PURCHASE 或 SALES',
    `note_text` VARCHAR(500) NOT NULL COMMENT '备注内容（如：正在调货、运输中）',
    `status_at` VARCHAR(32) NOT NULL COMMENT '该备注对应的订单状态（PROCESSING/SHIPPED等）',
    `is_terminal_visible` TINYINT NOT NULL DEFAULT 1 COMMENT '终端是否可见（1=是）',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '记录方组织ID',
    `created_by` BIGINT UNSIGNED NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_opn_order` (`order_id`, `order_type`, `is_deleted`)
);
```
- 订单级，一个订单挂多条备注
- `is_terminal_visible`：控制终端是否可见（上游对下游的备注，下游可见）

#### `order_batch_allocation`（订单-批次分配）

```sql
CREATE TABLE `order_batch_allocation` (
    `id` BIGINT UNSIGNED AUTO_INCREMENT,
    `order_id` BIGINT UNSIGNED NOT NULL COMMENT '所属订单ID（采购单）',
    `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '分配的批次ID',
    `allocated_quantity` DECIMAL(14,3) NOT NULL COMMENT '本批次分配数量',
    `unit_code` VARCHAR(8) NOT NULL DEFAULT 'kg',
    `allocation_order` INT NOT NULL DEFAULT 0 COMMENT '同订单内分配顺序（树状图分支序）',
    `org_id` BIGINT UNSIGNED NOT NULL COMMENT '分配方组织ID',
    `allocated_by` BIGINT UNSIGNED NOT NULL,
    `allocated_at` DATETIME(6) NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `is_deleted` TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_oba_order_batch` (`order_id`, `batch_id`),
    KEY `idx_oba_batch` (`batch_id`)
);
```
- 交付时从库存选批次分配给订单
- 同一订单可分配多批，形成树状图的多分支

### 1.2 修改现有表

#### `sales_order` 调整
- `trace_code_id` / `packed_package_no` 改为**收货入库时**才填（当前是下单时填）
- 新增字段 `delivered_at`（已有 `placed_at`，补一个送达时间，用于触发生成码）

#### `purchase_order` 调整
- 新增字段 `handling_status` VARCHAR(32)：交接阶段状态（`PENDING`/`IN_PROGRESS`/`COMPLETED`）
  - `PENDING`：刚确认，等出货方操作
  - `IN_PROGRESS`：出货方已开始备注/分配
  - `COMPLETED`：已交付（触发收货方入库）

### 1.3 迁移文件

新建 `V14__order_progress_note_and_allocation.sql`：
- 建 `order_progress_note` 和 `order_batch_allocation` 两表
- 给 `purchase_order` 加 `handling_status` 列（默认 `PENDING`）
- 回填存量采购单的 `handling_status`（CONFIRMED 及以后设为 `IN_PROGRESS`，RECEIVED 设为 `COMPLETED`）

---

## 2. 后端接口变更

### 2.1 订单备注流程（新增）

| 接口 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 添加备注 | POST | `/api/v1/orders/{orderType}/{id}/notes` | body: `{noteText, statusAt}` |
| 查询备注 | GET | `/api/v1/orders/{orderType}/{id}/notes` | 返回时间线列表 |

- `orderType` ∈ `PURCHASE` / `SALES`
- 权限：订单的买方或卖方组织均可添加；查询时买方卖方都能看
- 新建 `OrderProgressNoteController` + `OrderProgressNoteApplicationService`

### 2.2 批次分配（新增）

| 接口 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 分配批次 | POST | `/api/v1/purchase-orders/{id}/allocations` | body: `{batchId, allocatedQuantity}` |
| 查询分配 | GET | `/api/v1/purchase-orders/{id}/allocations` | 返回该订单所有分配 |
| 删除分配 | DELETE | `/api/v1/purchase-orders/{id}/allocations/{allocId}` | 取消错误分配 |

- 权限：仅卖方（供货方）组织可操作
- 校验：批次必须属于卖方组织、状态为 ACTIVE/FROZEN、剩余可用量充足
- 新建 `OrderBatchAllocationController` + `OrderBatchAllocationApplicationService`

### 2.3 库存聚合查询（新增）

| 接口 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 我的库存 | GET | `/api/v1/inventory/my-batches` | 返回当前组织名下所有可售批次 |

- 返回：批次ID、批号、产品、数量、已分配量、可用量、状态、溯源事件数
- 新建 `InventoryController` + `InventoryApplicationService`
- 复用 `BatchMapper`，加一个聚合查询统计已分配量

### 2.4 订单状态推进（重构）

#### `schedulePurchaseOrder` 语义改变
- **旧**：生成一个加工批次
- **新**：把订单 `handling_status` 从 `PENDING` → `IN_PROGRESS`，表示进入交接阶段
- 同时自动添加一条备注"已接单，开始处理"

#### 新增 `completePurchaseOrderDelivery`
- POST `/api/v1/purchase-orders/{id}/complete-delivery`
- 卖方在分配完批次后调用，标记 `handling_status = COMPLETED`
- 触发买方收货入库流程

#### `updateSalesStatus` 调整
- 当状态推进到 `DELIVERED` 时，**生成溯源码**（从 `createSalesOrder` 挪到这里）
- 把当前订单所有分配的批次绑定到该码

### 2.5 溯源查询（重构为树状）

#### `getPublicTrace` 返回结构变更

旧返回（平铺 segments）：
```json
{
  "segments": [{"batchNo": "BT-xxx", "timeline": [...]}]
}
```

新返回（树状）：
```json
{
  "tree": {
    "orderId": 123,
    "orderNo": "SO20260922001",
    "nodes": [
      {
        "stage": "RETAIL",
        "orgName": "鲜活优选连锁超市",
        "allocatedQuantity": 250,
        "batch": {
          "batchNo": "BT-****001",
          "productionDate": "2026-09-20",
          "timeline": [...]
        },
        "children": [
          {
            "stage": "DISTRIBUTION",
            "orgName": "南海冷链分装批发中心",
            "allocatedQuantity": 150,
            "batch": {"batchNo": "BT-****002", "timeline": [...]},
            "children": [...]
          },
          {
            "stage": "DISTRIBUTION",
            "orgName": "南海冷链分装批发中心",
            "allocatedQuantity": 100,
            "batch": {"batchNo": "BT-****003", "timeline": [...]},
            "children": [...]
          }
        ]
      }
    ]
  }
}
```

- `stage`：环节（`SOURCE`=捕捞 / `PROCESSING`=加工 / `DISTRIBUTION`=批发 / `RETAIL`=终端）
- 每个节点代表"某环节用某批次满足该订单的一部分"
- `children` 是该批次向上溯源的下一环节节点
- 新建 `PublicTraceTreeBuilder` 服务，递归构建树

---

## 3. 前端重构

### 3.1 工作台三栏目结构

`WorkbenchView.vue` 重构为**栏目布局**（非 tab）：

#### 终端角色（`market_op` / `shop_op`）—— 2 栏
```
┌─────────────┬─────────────┐
│ ①向上游下单  │ ②库存显示    │
│              │             │
│ [发起采购]   │ 批次列表     │
│ 采购单列表    │ 每批溯源     │
│ 订单进度备注  │             │
└─────────────┴─────────────┘
```

#### 非终端角色（`whl_op` / `plant_op` / `capt_ship`）—— 3 栏
```
┌──────────┬──────────┬──────────────┐
│①向上游下单│②库存显示  │③向下游出货    │
│          │          │              │
│[发起采购] │ 批次列表   │ 待出货订单    │
│ 采购单    │ 可用量    │ [确认接收]    │
│ 进度备注  │ 溯源事件  │ [备注流程]    │
│          │          │ [分配批次]    │
│          │          │ [确认出货]    │
│          │          │ [取消订单]    │
│          │          │ 已分配批次树  │
└──────────┴──────────┴──────────────┘
```

- 加工厂（`plant_op`）在"向下游出货"栏内多一个**质检**子区块
- 质管角色（`plant_qa` / `market_qa`）保留独立质检工作台
- `sys_admin` 保留系统管理

### 3.2 "向下游出货"栏的 4 个操作

```vue
<!-- 待出货订单卡片 -->
<article class="outgoing-order">
  <header>{{ order.orderNo }} | {{ order.status }}</header>
  
  <!-- 备注 timeline（实时可见） -->
  <OrderNoteTimeline :notes="order.notes" />
  
  <!-- 4 个操作按钮 -->
  <div class="actions">
    <button @click="addNote(order)">备注流程</button>
    <button @click="cancelOrder(order)">取消订单</button>
    <button @click="confirmReceipt(order)">确认接收</button>
    <button @click="openAllocatePanel(order)">分配批次</button>
    <button @click="completeDelivery(order)">确认出货</button>
  </div>
  
  <!-- 分配面板（弹出） -->
  <AllocatePanel v-if="order.allocateOpen" :order="order" :inventory="myInventory" />
</article>
```

#### 备注流程交互
- 点击"备注流程"→ 弹出输入框（如"正在调货""运输中"）
- 提交后实时刷新到本订单的 timeline
- **买卖双方登录后都能看到**（买方在自己的"向上游下单"栏看到进度）

#### 分配批次交互
- 点击"分配批次"→ 弹出面板，显示当前组织库存（带可用量）
- 勾选批次 + 填分配数量 → 提交
- 可多次分配，直到满足订单总量
- 分配完成后点"确认出货"→ 交付

### 3.3 消费者溯源页改树状图

`ConsumerTraceView.vue` 重构：

```vue
<TraceTreeView :tree="traceData.tree" />
```

新建 `TraceTreeView.vue` 组件：
- 递归渲染树节点
- 每个节点显示：环节名、组织名、分配数量、批次号（脱敏）、生产日期
- 节点下展开该批次的 timeline（复用 `TraceTimeline`）
- 多分支时横向并列展开

样式：
- 竖向时间线为主干
- 分支处横向展开（CSS grid 或 flex）
- 每个分支独立颜色标识

### 3.4 API 层扩展

`web/src/api/orders.ts` 新增：
```typescript
export async function addOrderNote(orderType: 'PURCHASE'|'SALES', orderId: number, note: {noteText: string; statusAt: string})
export async function listOrderNotes(orderType: 'PURCHASE'|'SALES', orderId: number)
export async function allocateBatch(orderId: number, payload: {batchId: number; allocatedQuantity: number})
export async function listAllocations(orderId: number)
export async function completeDelivery(orderId: number)
```

`web/src/api/inventory.ts` 新建：
```typescript
export async function listMyInventory(): Promise<InventoryBatch[]>
```

`web/src/api/trace.ts` 类型扩展：
```typescript
export interface TraceTreeNode {
  stage: string
  orgName: string
  allocatedQuantity: number
  batch: { batchNo: string; productionDate: string; timeline: TimelineItem[] }
  children: TraceTreeNode[]
}
export interface PublicTrace {
  // ...既有字段
  tree: { orderId: number; orderNo: string; nodes: TraceTreeNode[] }
}
```

---

## 4. 开发顺序与依赖

### Phase 1 — 数据模型 + 后端基础接口（必须先做）

1. **V14 迁移**：建两表 + 改 purchase_order
2. **实体 + Mapper**：`OrderProgressNote`、`OrderBatchAllocation`
3. **OrderProgressNoteApplicationService** + Controller
4. **OrderBatchAllocationApplicationService** + Controller
5. **InventoryApplicationService** + Controller
6. **重构 `schedulePurchaseOrder`**：改为推进 handling_status
7. **新增 `completePurchaseOrderDelivery`**
8. **重构 `createSalesOrder` / `updateSalesStatus`**：码生成挪到 DELIVERED

### Phase 2 — 溯源树状聚合

9. **PublicTraceTreeBuilder**：递归构建树
10. **重构 `getPublicTrace`**：返回 tree 结构
11. **单元测试**：多分支树构建

### Phase 3 — 前端三栏目工作台

12. **WorkbenchView 重构**：栏目布局
13. **OrderNoteTimeline 组件**
14. **AllocatePanel 组件**
15. **API 层扩展**

### Phase 4 — 消费者树状图

16. **TraceTreeView 组件**：递归树渲染
17. **ConsumerTraceView 集成**

### Phase 5 — 端到端验证

18. **种子数据补充**：V15 补一条多批次分配的演示链
19. **演示流程跑通**

---

## 5. 关键技术决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 批次可用量计算 | 实时聚合（已分配量 = sum(order_batch_allocation.allocated_quantity where batch_id=X and is_deleted=0)） | 避免冗余字段同步问题 |
| 树状图构建 | 后端递归构建，前端只渲染 | 前端逻辑简单，后端可控 |
| 备注可见性 | 所有备注买卖双方都可见，用 `is_terminal_visible` 控制终端可见性 | 用户明确要求"上下游实时可见" |
| 码生成时机 | 销售单 DELIVERED 时 | 用户确认"终端收货入库后生成" |
| 分配粒度 | 可多次分配，每次一批 | 灵活支持"多批合成一单" |
| 树节点 children 方向 | 向上溯源（子节点是上游环节） | 符合"溯源=向上查"的直觉 |

---

## 6. 风险与回退

- **数据兼容**：V14 只加表不改旧字段语义，存量数据不受影响
- **回退方案**：若树状聚合太复杂，可先返回平铺 segments（兼容旧前端），再迭代为树
- **性能**：树状递归最多 4 层（捕捞→加工→批发→终端），无性能风险

---

_本文档为开发基线，实施时若发现遗漏或不可行，更新本文档并标注日期。_

_2026-09-22 初稿_
