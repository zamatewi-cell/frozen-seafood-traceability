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

/** 公开事件白名单（服务端显式持有，契约 v1.1 §11；PURCHASE 等未定义类型不会出现） */
export type PublicEventType =
  | 'SOURCE'
  | 'PROCESS'
  | 'FREEZE'
  | 'PACK'
  | 'WAREHOUSE_IN'
  | 'WAREHOUSE_OUT'
  | 'TRANSPORT'
  | 'ARRIVAL'
  | 'SALE'

export interface TimelineItem {
  /** 公开白名单内的事件类型代码 */
  eventType: PublicEventType
  /** 服务端固定公开标签 */
  event: string
  occurredAt: string
  dataSourceLabel: string
  /** 所属谱系节点的响应内局部键（N1、N2 …，不是内部批次 ID） */
  nodeKey: string
}

export type LineageRole = 'ORIGIN' | 'INTERMEDIATE' | 'TARGET'

export type LineageOperationType = 'PROCESS' | 'SPLIT' | 'MERGE' | 'REPACK'

/** 公开谱系节点：最上游为世代 0，扫码批次为 TARGET */
export interface LineageNode {
  nodeKey: string
  generation: number
  role: LineageRole
  productName: string
}

/** 公开谱系边：一次已提交的物料转换（不是追溯事件） */
export interface LineageEdge {
  fromNodeKey: string
  toNodeKey: string
  operationType: LineageOperationType
  occurredAt: string
}

export interface LineageProjection {
  nodes: LineageNode[]
  edges: LineageEdge[]
}

export interface TemperatureSummary {
  result: 'NO_BREACH_RECORDED' | 'BREACH_RECORDED' | 'INSUFFICIENT_DATA'
  ruleNote: string
}

export interface PublicTrace {
  publicTraceId: string
  product: ProductProjection
  batch: BatchProjection
  /** 目标批次及其祖先谱系（只含祖先，不含兄弟或下游批次） */
  lineage: LineageProjection
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
