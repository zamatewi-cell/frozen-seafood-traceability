/**
 * 企业端业务模型。
 * 严格对应后端真实响应（docs/api/openapi.yaml），服务端采用 non_null 序列化，可选字段缺省即为未填写。
 */

export interface CsrfToken {
  headerName: string
  parameterName: string
  token: string
}

export interface CurrentUser {
  userId: number
  username: string
  displayName: string
  orgId: number
  orgNo: string
  orgName: string
  orgType: string
  roles: string[]
  scopes: string[]
}

export type BatchFlowStatus = 'DRAFT' | 'ACTIVE' | 'CLOSED'
export type BatchRiskStatus = 'NORMAL' | 'FROZEN' | 'RECALLED'

export const BATCH_FLOW_STATUSES: readonly BatchFlowStatus[] = ['DRAFT', 'ACTIVE', 'CLOSED']
export const BATCH_RISK_STATUSES: readonly BatchRiskStatus[] = ['NORMAL', 'FROZEN', 'RECALLED']

export interface Batch {
  id: number
  /** 当前责任组织；只有 Transfer ACCEPTED 才会改变 */
  orgId: number
  productId: number
  /** 服务端生成的全局唯一追溯批次号 */
  traceBatchNo: string
  /** 企业可选外部批号，可重复 */
  externalBatchNo?: string
  batchType: string
  /** 声明数量（创建或产出时声明，提交后不原地改写） */
  quantity: number
  /** 派生剩余量 = 声明数量 - 已提交批次操作 INPUT 消耗量 - 已提交终端销售数量；CLOSED 为 0（服务端派生，前端只展示） */
  remainingQuantity?: number
  unitCode: string
  originType: string
  originText: string
  productionDate?: string
  captureDate?: string
  freezeDate?: string
  shelfLifeDays?: number
  flowStatus: BatchFlowStatus
  riskStatus: BatchRiskStatus
  /** 产出该批次的批次操作（仅操作输出批次） */
  producedByOperationId?: number
  /** 全量消耗该批次的已提交批次操作（非空时批次必为 CLOSED） */
  consumedByOperationId?: number
  /** 第一次有效终端销售（写一次）；非空后禁止交接与批次操作，只能继续终端销售 */
  firstSaleId?: number
  version: number
  createdAt?: string
  createdBy?: number
  updatedAt?: string
  updatedBy?: number
}

export type OriginType = 'DOMESTIC_CAPTURE' | 'DOMESTIC_FARMED' | 'IMPORT'

export const ORIGIN_TYPES: readonly OriginType[] = ['DOMESTIC_CAPTURE', 'DOMESTIC_FARMED', 'IMPORT']

/**
 * 来源批次创建请求（POST /api/v1/batches）。
 * 只包含企业可填写的字段；batchType、traceBatchNo、orgId、flowStatus、riskStatus 等由服务端决定，绝不发送。
 */
export interface CreateSourceBatchRequest {
  externalBatchNo?: string
  productId: number
  quantity: number
  /** Demo MVP 固定为 kg */
  unitCode: 'kg'
  originType: OriginType
  originText: string
  productionDate?: string
  captureDate?: string
  freezeDate?: string
  shelfLifeDays?: number
}

export type TraceEventDetailValue = string | number | boolean | null

export interface TraceEvent {
  id: number
  batchId: number
  orgId: number
  siteId?: number
  eventType: string
  occurredAt: string
  recordedAt: string
  operatorId?: number
  dataSource: string
  status: 'SUBMITTED' | 'CORRECTED' | string
  summary: string
  detailsJson?: Record<string, TraceEventDetailValue>
  correctsEventId?: number
  correctionReason?: string
}

export interface BatchListQuery {
  page: number
  size: number
  flowStatus?: BatchFlowStatus
  riskStatus?: BatchRiskStatus
}

export interface OrganizationSummary {
  id: number
  orgNo: string
  name: string
  orgType: string
  status: string
}

export interface Product {
  id: number
  productCode: string
  publicName: string
  scientificName?: string
  category: string
  specification: string
  sourceType: string
  baseUnitCode: string
  status: string
  version: number
}

/** 自有冷库出入库（受控人工追溯事件）创建载荷：dataSource 固定 MANUAL，不发送 detailsJson。 */
export type WarehouseEventType = 'WAREHOUSE_IN' | 'WAREHOUSE_OUT'

export interface CreateWarehouseEventPayload {
  eventType: WarehouseEventType
  siteId: number
  occurredAt: string
  dataSource: 'MANUAL'
  summary: string
}

/** 终端销售创建载荷（POST /api/v1/batches/{batchId}/sales）：只发送门店、数量与业务时间，剩余量与单位由服务端决定。 */
export interface CreateSalePayload {
  siteId: number
  quantity: number
  occurredAt: string
}

/** 终端销售记录（零售企业在本组织门店面向消费者的数量出库；不含任何消费者或支付信息）。 */
export interface Sale {
  id: number
  batchId: number
  orgId: number
  siteId: number
  siteName?: string
  quantity: number
  unitCode: string
  occurredAt: string
  status: 'SUBMITTED' | string
  createdBy?: number
  createdAt?: string
}

/**
 * 批次公开追溯码（企业端视图）。一批一码、跨交接不换码；DISABLED 为终态（消费者查询与未知码一致返回未找到）。
 * RECALLED 码状态属于 Phase B，当前切片不会写入。
 */
/** 风险状态转换来源类型：PB1 只有人工（MANUAL）；ALERT / RECALL 随后续阶段的数据库约束同时加入。 */
export type BatchRiskSourceType = 'MANUAL'

/**
 * 批次风险状态转换（PB1：人工 NORMAL ⇄ FROZEN）。orgId 为转换时的责任组织，flowStatus 为转换时的流转状态快照（转换不改变流转状态）。
 */
export interface BatchRiskTransition {
  id: number
  batchId: number
  orgId: number
  flowStatus: 'ACTIVE' | 'CLOSED'
  fromStatus: BatchRiskStatus
  toStatus: BatchRiskStatus
  sourceType: BatchRiskSourceType
  reason: string
  actorUserId: number | null
  occurredAt: string
}

export interface PublicTraceCode {
  id: number
  batchId: number
  publicId: string
  status: 'ACTIVE' | 'DISABLED' | 'RECALLED'
  activatedAt: string
  disabledAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface SiteSummary {
  id: number
  orgId: number
  siteNo: string
  name: string
  siteType: string
  status: string
}

export type TransferStatus = 'DRAFT' | 'PENDING' | 'ACCEPTED' | 'REJECTED'
export type ShipmentStatus = 'PLANNED' | 'IN_TRANSIT' | 'DELIVERED' | 'CANCELLED'

export const SHIPMENT_STATUSES: readonly ShipmentStatus[] = ['PLANNED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED']

/**
 * 企业间整批交接（责任交接凭证）。只有 ACCEPTED 会改变批次当前责任组织；
 * 提交前必须绑定 PLANNED 运输任务，接受 / 拒收前运输任务必须已 DELIVERED。
 */
export interface Transfer {
  id: number
  transferNo: string
  batchId: number
  traceBatchNo?: string
  shipmentId?: number
  shipmentNo?: string
  shipmentStatus?: ShipmentStatus
  senderOrgId: number
  receiverOrgId: number
  quantity: number
  unitCode: string
  status: TransferStatus
  submittedRecordedAt?: string
  submittedBy?: number
  receivedAt?: string
  decisionRecordedAt?: string
  decidedBy?: number
  receivedQuantity?: number
  differenceReason?: string
  rejectionReason?: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface TransferListQuery {
  direction?: 'SENT' | 'RECEIVED'
  status?: TransferStatus
  batchId?: number
  page: number
  size: number
}

export interface ShipmentPartyRef {
  id: number
  orgNo?: string
  name?: string
  orgType?: string
}

export interface ShipmentTransferItem {
  transferId: number
  transferNo: string
  batchId: number
  traceBatchNo?: string
  quantity: number
  unitCode: string
  status: TransferStatus
  version: number
}

/** 一次物理冷链运输；Shipment 与承运商从不改变批次当前责任组织。 */
export interface Shipment {
  id: number
  shipmentNo: string
  status: ShipmentStatus
  senderOrg: ShipmentPartyRef
  receiverOrg: ShipmentPartyRef
  carrierOrg: ShipmentPartyRef
  vehicleOrContainerNo: string
  originSite?: SiteSummary
  destinationSite?: SiteSummary
  loadedAt?: string
  unloadedAt?: string
  dispatchedRecordedAt?: string
  dispatchedBy?: number
  deliveredRecordedAt?: string
  deliveredBy?: number
  cancelledRecordedAt?: string
  cancelledBy?: number
  cancelReason?: string
  transfers: ShipmentTransferItem[]
  version: number
  createdAt: string
  updatedAt: string
}

export type ShipmentRole = 'SENDER' | 'CARRIER' | 'RECEIVER'

export interface ShipmentListQuery {
  role?: ShipmentRole
  status?: ShipmentStatus
  page: number
  size: number
}

/** 批次操作类型；当前 Slice 仅执行 PROCESS 与 SPLIT（MERGE / REPACK 由服务端返回 OPERATION_TYPE_NOT_SUPPORTED）。 */
export type BatchOperationType = 'PROCESS' | 'SPLIT' | 'MERGE' | 'REPACK'
export type SupportedOperationType = 'PROCESS' | 'SPLIT'
export type BatchOperationStatus = 'DRAFT' | 'SUBMITTED' | 'CORRECTED'
export type BatchItemRole = 'INPUT' | 'OUTPUT' | 'LOSS' | 'WASTE' | 'SAMPLE'

/**
 * 批次操作明细请求：INPUT 必须带 batchId 且数量等于剩余量；OUTPUT 不带 batchId（服务端生成），
 * 可选 productId（仅 PROCESS）、externalBatchNo、shelfLifeDays；LOSS / WASTE / SAMPLE 只有数量。
 */
export interface BatchOperationItemRequest {
  role: BatchItemRole
  batchId?: number
  quantity: number
  unitCode: 'kg'
  productId?: number
  externalBatchNo?: string
  shelfLifeDays?: number
}

export interface BatchOperationItem {
  id: number
  operationId: number
  batchId?: number
  role: BatchItemRole
  quantity: number
  unitCode: string
  normalizedQuantity: number
  traceBatchNo?: string
  externalBatchNo?: string
  productId?: number
  batchType?: string
  batchFlowStatus?: BatchFlowStatus
  batchRiskStatus?: BatchRiskStatus
}

export interface BatchRelation {
  id: number
  operationId: number
  parentBatchId: number
  childBatchId: number
  relationType: 'TRANSFORM' | 'SPLIT' | 'MERGE'
  createdAt: string
}

export interface BatchOperation {
  id: number
  orgId: number
  operationNo: string
  operationType: BatchOperationType
  occurredAt: string
  recordedAt: string
  status: BatchOperationStatus
  note?: string
  balanced: boolean
  inputTotal?: number
  outputTotal?: number
  lossTotal?: number
  wasteTotal?: number
  sampleTotal?: number
  version: number
  createdAt: string
  createdBy?: number
  updatedAt: string
  updatedBy?: number
  items: BatchOperationItem[]
  relations: BatchRelation[]
}
