# API 设计规范

## 1. 基本约定

- 基础路径：`/api/v1`；公开查询：`/api/public/v1`。
- 媒体类型：`application/json; charset=utf-8`；附件上传使用 `multipart/form-data`。
- 时间：ISO 8601 且带偏移量，例如 `2026-09-01T14:20:00+08:00`；服务端转 UTC 保存。
- 日期：`YYYY-MM-DD`。
- 数量、温度在 JSON 中使用十进制数，并始终携带单位字段。
- 企业端会话使用安全 Cookie；所有写接口校验 CSRF。
- 需要幂等的命令接受 `Idempotency-Key` 请求头。
- 版本冲突通过请求体/头中的资源版本检查，冲突返回 HTTP 409。

## 2. 统一响应

成功：

```json
{
  "data": {},
  "meta": {
    "requestId": "01J...",
    "timestamp": "2026-09-03T10:30:00+08:00"
  }
}
```

失败采用 RFC 9457 风格问题详情，并增加稳定业务码：

```json
{
  "type": "https://example.invalid/problems/batch-mass-balance",
  "title": "批次数量不平衡",
  "status": 422,
  "code": "BATCH_MASS_BALANCE_VIOLATION",
  "detail": "投入 1020.000 kg，产出及损耗合计 1010.000 kg",
  "instance": "/api/v1/batch-operations",
  "requestId": "01J...",
  "fieldErrors": []
}
```

## 3. 分页与排序

- 请求：`page` 从 1 开始，默认 1；`size` 默认 20、最大 100。
- 排序：`sort=recordedAt,desc`；只允许接口声明的字段，禁止直接拼 SQL。
- 响应 `meta.page`：`number/size/totalElements/totalPages`。
- 温度时序可使用游标分页：`cursor` + `limit`，避免大偏移扫描。

## 4. HTTP 与错误码

| HTTP | 典型错误码 | 含义 |
|---:|---|---|
| 400 | `INVALID_REQUEST` | JSON、参数或格式错误 |
| 401 | `AUTH_REQUIRED` | 未登录/会话失效 |
| 403 | `ACCESS_DENIED`、`ORG_SCOPE_DENIED` | 无角色或超出数据范围 |
| 404 | `RESOURCE_NOT_FOUND`、`PUBLIC_TRACE_NOT_FOUND` | 不存在；公开端不暴露内部原因 |
| 409 | `VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT`、`INVALID_STATE_TRANSITION` | 并发或状态冲突 |
| 422 | `BATCH_MASS_BALANCE_VIOLATION`、`BATCH_RELATION_CYCLE`、`UNIT_DIMENSION_MISMATCH`、`TEMPERATURE_RULE_NOT_APPLICABLE` | 业务校验失败 |
| 429 | `RATE_LIMITED` | 公开查询限流 |
| 500 | `INTERNAL_ERROR` | 未预期错误，不返回堆栈 |

## 5. 端点清单

### 身份与基础数据

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/login` | 匿名 | 建立会话 |
| POST | `/auth/logout` | 已登录 | 注销当前会话 |
| GET | `/me` | 已登录 | 当前用户、角色和数据范围 |
| GET/POST | `/organizations` | 系统管理员 | 组织列表/创建 |
| GET/POST | `/products` | 已登录/系统管理员 | 产品查询/创建 |
| POST | `/products/{id}/temperature-rules` | 系统管理员 | 新建规则草稿 |
| POST | `/temperature-rules/{id}/publish` | 系统管理员 | 发布不可变规则版本 |

### 批次、事件与谱系

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET/POST | `/batches` | 企业角色 | 按范围查询/创建草稿 |
| GET/PATCH | `/batches/{id}` | 企业角色 | 详情/仅草稿更新 |
| POST | `/batches/{id}/submit` | 企业操作员 | 提交批次 |
| POST | `/batch-operations` | 企业操作员 | 创建拆分/合并/加工操作草稿 |
| POST | `/batch-operations/{id}/submit` | 企业操作员 | 校验平衡、写入谱系 |
| GET | `/batches/{id}/genealogy` | 质量/审计/关联操作员 | `UPSTREAM/DOWNSTREAM/BOTH` |
| GET/POST | `/batches/{id}/events` | 关联角色 | 事件查询/提交 |
| POST | `/events/{id}/corrections` | 有权限的原组织 | 追加更正记录 |

### 交接、温控与质量

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET/POST | `/transfers` | 企业角色 | 查询/创建交接草稿 |
| POST | `/transfers/{id}/submit` | 发送方 | 发送交接 |
| POST | `/transfers/{id}/accept` | 接收方 | 验收并留痕 |
| POST | `/transfers/{id}/reject` | 接收方 | 拒收并填写原因 |
| GET/POST | `/temperature-records` | 企业角色 | 查询/录入温度 |
| POST | `/temperature-records/imports` | 企业角色 | CSV 预校验/导入（P1） |
| GET/POST | `/inspection-reports` | 操作/质量 | 查询/录入摘要 |
| GET | `/alerts` | 质量角色 | 告警列表 |
| POST | `/alerts/{id}/acknowledge` | 质量管理员 | 确认告警 |
| POST | `/batches/{id}/freeze` | 质量管理员 | 冻结批次 |
| POST | `/batches/{id}/unfreeze` | 质量管理员 | 复核后解冻 |
| GET/POST | `/recalls` | 质量管理员 | 查询/创建模拟召回 |
| POST | `/recalls/{id}/start` | 质量管理员 | 锁定范围并启动 |
| POST | `/recalls/{id}/close` | 质量管理员 | 完成数量与处置复核后关闭 |

### 附件、审计与公开查询

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/attachments` | 企业角色 | 上传受控附件 |
| GET | `/attachments/{id}/content` | 按访问级别 | 下载 |
| GET | `/audit-logs` | 管理/审计 | 只读查询 |
| GET | `/api/public/v1/traces/{publicTraceId}` | 匿名 | 消费者公开投影 |

## 6. 关键契约

### 批次操作提交

请求中的每个项目必须包含 `role/batchId/quantity/unitCode`；损耗等无批次项目通过 `role` 区分。服务端重新计算标准化数量，不信任客户端合计值。成功后返回操作、生成/关联批次以及谱系边。

### 谱系响应

返回 `rootBatchId/nodes/edges/truncated/visitedNodes/maxDepthReached`。节点字段依当前权限裁剪；边引用 `operationId`。图谱路径用于查询，不赋予附件权限。

### 公开追溯响应

只返回：公开追溯 ID、公开产品/批号、脱敏来源、关键时间线、温控摘要、检测结论摘要、当前状态、模拟召回提示、查询时间和真实性声明。禁止出现内部 ID、用户名、手机号、精确设备号、内部备注和非公开附件地址。

## 7. 契约演进

- `/v1` 内只做向后兼容扩展；删除/改义需新增版本或经过弃用周期。
- 枚举客户端必须容忍未知值并展示“未知状态”，服务端不得随意复用旧枚举含义。
- OpenAPI 文件是评审基线；接口改动必须同 PR 更新调用方和测试。

