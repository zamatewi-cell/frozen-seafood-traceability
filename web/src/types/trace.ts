/**
 * 消费者公开追溯模型与契约定义。
 * 遵循严格白名单机制，绝不包含敏感内部字段。
 * 服务端采用 non_null 序列化：recallNotice、productionDate 等可选字段在无值时被省略。
 */

import type { BatchFlowStatus, BatchRiskStatus } from './enterprise'

export type { ResponseMeta, SuccessEnvelope, FieldError, ProblemDetails } from './api'

export interface ProductProjection {
  name: string
  category: string
  specification: string
}

export interface BatchProjection {
  /** 掩码后的企业外部批号；未填写外部批号时为 **** */
  publicBatchNo: string
  originType: string
  maskedOrigin: string
  productionDate?: string | null
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

export interface PublicTrace {
  publicTraceId: string
  product: ProductProjection
  batch: BatchProjection
  timeline: TimelineItem[]
  temperatureSummary: TemperatureSummary
  flowStatus: BatchFlowStatus
  riskStatus: BatchRiskStatus
  recallNotice?: string | null
  queriedAt: string
  disclosure: string
}

export type TraceViewState =
  | 'INITIAL'
  | 'LOADING'
  | 'SUCCESS'
  | 'NOT_FOUND'
  | 'ERROR'
