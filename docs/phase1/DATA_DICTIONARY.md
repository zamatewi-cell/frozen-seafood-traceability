# 核心数据字典

## 1. 通用字段

除纯关联表外，业务表统一包含：

| 字段 | 类型 | 规则 |
|---|---|---|
| `id` | `BIGINT UNSIGNED` | 内部主键，不对消费者公开 |
| `version` | `INT UNSIGNED` | 乐观锁，默认 0 |
| `created_at` | `DATETIME(6)` | UTC，服务端写入 |
| `created_by` | `BIGINT UNSIGNED` | 可空仅限系统任务 |
| `updated_at` | `DATETIME(6)` | UTC |
| `updated_by` | `BIGINT UNSIGNED` | 可空仅限系统任务 |

业务状态使用 `VARCHAR(32)` 并由 Java 枚举和 `CHECK` 双重约束，不使用数据库原生 `ENUM`，便于迁移。

## 2. 身份与基础数据

### `organization`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `org_no` | `VARCHAR(32)` | 是 | 平台唯一业务编号 |
| `name` | `VARCHAR(128)` | 是 | 演示组织名 |
| `org_type` | `VARCHAR(32)` | 是 | `SOURCE/PROCESSOR/WAREHOUSE/CARRIER/DISTRIBUTOR/RETAILER` |
| `credit_code` | `VARCHAR(32)` | 否 | 仅演示/脱敏值，不公开 |
| `status` | `VARCHAR(16)` | 是 | `ACTIVE/INACTIVE` |

### `site`

`org_id`、`site_no`、`name`、`site_type`、`address_text`、`timezone`、`status`。`(org_id,site_no)` 唯一。

### `app_user` / `role` / `user_role`

- `app_user`：`org_id`、`username`、`display_name`、`password_hash`、`job_type`、`status`、`last_login_at`。
- `role`：`role_code`、`name`、`scope_type`、`status`。
- `user_role`：`user_id`、`role_id`，联合主键。
- 不保存明文密码；消费者不使用该用户表。

### `product`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `product_code` | `VARCHAR(32)` | 是 | 平台唯一 |
| `public_name` | `VARCHAR(128)` | 是 | 消费者可见名称 |
| `scientific_name` | `VARCHAR(128)` | 否 | 学名 |
| `category` | `VARCHAR(32)` | 是 | 鱼/虾蟹/贝/头足等 |
| `specification` | `VARCHAR(128)` | 是 | 必须含单位 |
| `source_type` | `VARCHAR(32)` | 是 | `DOMESTIC_CAPTURE/DOMESTIC_FARMED/IMPORT` |
| `base_unit_code` | `VARCHAR(16)` | 是 | MVP 质量基准 `kg` |
| `status` | `VARCHAR(16)` | 是 | `DRAFT/ACTIVE/INACTIVE` |

### `temperature_rule` / `temperature_rule_stage`

- `temperature_rule`：`product_id`、`version_no`、`name`、`effective_from`、`effective_to`、`status(DRAFT/ACTIVE/RETIRED)`、`basis_note`。
- `temperature_rule_stage`：`rule_id`、`stage_code`、`lower_limit DECIMAL(6,2)`、`upper_limit DECIMAL(6,2)`、`unit_code`、`allowed_duration_seconds`、`sequence_no`。
- 同一产品同一时刻最多一个 `ACTIVE` 规则；已被温度记录引用的规则不可修改。

## 3. 批次与谱系

### `batch`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `org_id` | `BIGINT UNSIGNED` | 是 | 当前持有/负责组织 |
| `product_id` | `BIGINT UNSIGNED` | 是 | 产品 |
| `batch_no` | `VARCHAR(64)` | 是 | 组织内唯一业务批号 |
| `batch_type` | `VARCHAR(24)` | 是 | `SOURCE/PROCESSING/DISTRIBUTION/SALE` |
| `quantity` | `DECIMAL(18,3)` | 是 | 批次初始/声明数量 |
| `unit_code` | `VARCHAR(16)` | 是 | 与数量同时出现 |
| `origin_type` | `VARCHAR(32)` | 是 | 国内捕捞/养殖/进口 |
| `origin_text` | `VARCHAR(255)` | 是 | 企业端来源；公开时另做脱敏投影 |
| `production_date` | `DATE` | 否 | 生产日期 |
| `capture_date` | `DATE` | 否 | 捕捞/出塘日期 |
| `freeze_date` | `DATE` | 否 | 冻结日期 |
| `shelf_life_days` | `INT UNSIGNED` | 否 | 保质期天数 |
| `status` | `VARCHAR(16)` | 是 | `DRAFT/ACTIVE/FROZEN/RECALLED/CLOSED` |

### `batch_operation`

`org_id`、`operation_no`、`operation_type(MERGE/SPLIT/PROCESS/REPACK)`、`occurred_at`、`recorded_at`、`status(DRAFT/SUBMITTED/CORRECTED)`、`idempotency_key`、`note`。

### `batch_operation_item`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `operation_id` | `BIGINT UNSIGNED` | 是 | 所属操作 |
| `batch_id` | `BIGINT UNSIGNED` | 否 | `LOSS/WASTE/SAMPLE` 可无批次 |
| `role` | `VARCHAR(16)` | 是 | `INPUT/OUTPUT/LOSS/WASTE/SAMPLE` |
| `quantity` | `DECIMAL(18,3)` | 是 | 原始数量，非负 |
| `unit_code` | `VARCHAR(16)` | 是 | 原始单位 |
| `normalized_quantity` | `DECIMAL(18,3)` | 是 | 基准单位数量 |
| `conversion_rule_id` | `BIGINT UNSIGNED` | 否 | 非基准单位时必填 |

### `batch_relation`

`operation_id`、`parent_batch_id`、`child_batch_id`、`relation_type`、`created_at`。不重复保存无法无歧义分摊的边级产量；数量以操作项目为准。

### `public_trace_code`

`batch_id`、`public_id VARCHAR(40)`、`token_hash CHAR(64)`、`status(ACTIVE/DISABLED/RECALLED)`、`activated_at`、`disabled_at`。二维码只包含 `public_id`，绝不包含 `batch.id`。

## 4. 追溯、交接与温控

### `trace_event`

`batch_id`、`org_id`、`site_id`、`event_type`、`occurred_at`、`recorded_at`、`operator_id`、`data_source`、`status(SUBMITTED/CORRECTED)`、`corrects_event_id`、`summary`、`details_json`。`details_json` 只承载非关键扩展字段，查询/约束字段必须结构化。

### `shipment`

`shipment_no`、`carrier_org_id`、`vehicle_or_container_no`、`origin_site_id`、`destination_site_id`、`loaded_at`、`unloaded_at`、`required_rule_id`、`status`。

### `transfer`

`transfer_no`、`batch_id`、`shipment_id`、`sender_org_id`、`receiver_org_id`、`quantity`、`unit_code`、`shipped_at`、`received_at`、`recorded_at`、`received_quantity`、`difference_reason`、`status(DRAFT/PENDING/ACCEPTED/REJECTED)`、`rejection_reason`、`idempotency_key`。

### `temperature_record`

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `batch_id` | `BIGINT UNSIGNED` | 条件 | 与 `shipment_id` 至少一个存在 |
| `shipment_id` | `BIGINT UNSIGNED` | 条件 | 运输记录绑定任务 |
| `stage_code` | `VARCHAR(32)` | 是 | 业务环节 |
| `measured_at` | `DATETIME(6)` | 是 | 测量时间 |
| `recorded_at` | `DATETIME(6)` | 是 | 系统入库时间 |
| `temperature` | `DECIMAL(6,2)` | 是 | 温度值 |
| `unit_code` | `VARCHAR(8)` | 是 | MVP 仅 `CELSIUS` |
| `data_source` | `VARCHAR(16)` | 是 | `MANUAL/IMPORT/SIMULATED/DEVICE` |
| `device_no` | `VARCHAR(64)` | 否 | 仅真实设备来源使用；MVP 多为空 |
| `rule_stage_id` | `BIGINT UNSIGNED` | 是 | 判定时规则阶段 |
| `evaluation` | `VARCHAR(16)` | 是 | `NORMAL/HIGH/LOW/MISSING_CONTEXT` |

## 5. 质量、附件与审计

### `inspection_report`

`batch_id`、`org_id`、`report_no`、`institution_name`、`inspected_at`、`recorded_at`、`items_summary`、`conclusion(PASS/FAIL/PENDING)`、`institution_verified BOOLEAN DEFAULT FALSE`、`data_source`。

### `alert`

`org_id`、`batch_id`、`shipment_id`、`alert_type`、`severity(LOW/MEDIUM/HIGH/CRITICAL)`、`trigger_rule_id`、`triggered_at`、`status(OPEN/ACKNOWLEDGED/PROCESSING/RESOLVED/DISMISSED)`、`assignee_id`、`resolution`。

### `recall` / `recall_batch`

- `recall`：`recall_no`、`owner_org_id`、`reason`、`severity`、`policy_version`、`started_at`、`closed_at`、`notification_summary`、`result_summary`、`status(DRAFT/IN_PROGRESS/CLOSED/CANCELLED)`。
- `recall_batch`：`recall_id`、`batch_id`、`scope_type(SOURCE/INVENTORY/IN_TRANSIT/SOLD)`、`affected_quantity`、`notified_quantity`、`returned_quantity`、`disposed_quantity`、`unit_code`、`scope_confirmed_at`。

### `attachment`

`owner_org_id`、`object_type`、`object_id`、`original_name`、`storage_key`、`mime_type`、`size_bytes`、`sha256`、`access_level(PRIVATE/RELATED_CHAIN/PUBLIC)`、`status(ACTIVE/VOID)`、`void_reason`。

### `audit_log`

`request_id`、`actor_user_id`、`actor_org_id`、`action`、`object_type`、`object_id`、`occurred_at`、`result(SUCCESS/DENIED/FAILED)`、`change_summary_json`、`client_ip_hash`。不保存密码、会话值或附件正文。

## 6. 消费者公开投影

公开响应只从查询投影生成：`publicTraceId`、公开产品名、规格、公开批号、脱敏来源、关键事件摘要、温控摘要、检测结论摘要、当前状态、模拟召回提示、查询时间和数据来源声明。其余字段默认不公开。

