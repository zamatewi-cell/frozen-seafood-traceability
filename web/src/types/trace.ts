/**
 * 消费者公开追溯模型与契约定义。
 * 遵循严格白名单机制，绝不包含敏感内部字段。
 */

export interface ProductProjection {
  name: string
  category: string
  specification: string
}

export interface BatchProjection {
  publicBatchNo: string
  originType: string
  maskedOrigin: string
  productionDate: string | null
}

export interface TimelineItem {
  event: string
  occurredAt: string
  dataSourceLabel: string
}

export interface TemperatureSummary {
  result: 'NO_BREACH_RECORDED' | 'BREACH_RECORDED' | 'INSUFFICIENT_DATA'
  ruleNote: string
}

export type BatchStatusType = 'ACTIVE' | 'FROZEN' | 'RECALLED' | 'CLOSED'

export interface PublicTrace {
  publicTraceId: string
  product: ProductProjection
  batch: BatchProjection
  timeline: TimelineItem[]
  temperatureSummary: TemperatureSummary
  batchStatus: BatchStatusType
  recallNotice: string | null
  queriedAt: string
  disclosure: string
}

export interface ResponseMeta {
  requestId: string
  timestamp: string
}

export interface SuccessEnvelope<T> {
  data: T
  meta: ResponseMeta
}

export interface FieldError {
  field: string
  code: string
  message: string
}

export interface ProblemDetails {
  type?: string
  title?: string
  status: number
  code?: string
  detail?: string
  instance?: string
  requestId?: string
  fieldErrors?: FieldError[]
}

export type TraceViewState =
  | 'INITIAL'
  | 'LOADING'
  | 'SUCCESS'
  | 'NOT_FOUND'
  | 'ERROR'
