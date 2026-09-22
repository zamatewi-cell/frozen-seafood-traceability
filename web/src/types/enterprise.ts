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
  quantity: number
  unitCode: string
  originType: string
  originText: string
  productionDate?: string
  captureDate?: string
  freezeDate?: string
  shelfLifeDays?: number
  flowStatus: BatchFlowStatus
  riskStatus: BatchRiskStatus
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
