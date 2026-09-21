import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import {
  BATCH_FLOW_STATUSES,
  BATCH_RISK_STATUSES,
  type BatchFlowStatus,
  type BatchListQuery,
  type BatchRiskStatus
} from '@/types/enterprise'

export const BATCH_PAGE_SIZE = 20

function first(value: LocationQuery[string]): string | undefined {
  const raw = Array.isArray(value) ? value[0] : value
  return typeof raw === 'string' ? raw : undefined
}

/** 从路由 query 解析批次列表条件；非法值一律忽略，保证刷新与分享链接可复现。 */
export function parseBatchListQuery(query: LocationQuery): BatchListQuery {
  const pageRaw = Number(first(query.page))
  const page = Number.isInteger(pageRaw) && pageRaw >= 1 ? pageRaw : 1
  const flow = first(query.flowStatus)
  const risk = first(query.riskStatus)
  return {
    page,
    size: BATCH_PAGE_SIZE,
    flowStatus: BATCH_FLOW_STATUSES.includes(flow as BatchFlowStatus) ? (flow as BatchFlowStatus) : undefined,
    riskStatus: BATCH_RISK_STATUSES.includes(risk as BatchRiskStatus) ? (risk as BatchRiskStatus) : undefined
  }
}

export function toRouteQuery(query: Omit<BatchListQuery, 'size'>): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  if (query.page > 1) result.page = String(query.page)
  if (query.flowStatus) result.flowStatus = query.flowStatus
  if (query.riskStatus) result.riskStatus = query.riskStatus
  return result
}

/** 最近一次批次列表条件，供详情页“返回列表”保留筛选与页码（仅内存）。 */
let lastListQuery: LocationQueryRaw = {}

export function rememberBatchListQuery(query: LocationQueryRaw): void {
  lastListQuery = { ...query }
}

export function recalledBatchListQuery(): LocationQueryRaw {
  return { ...lastListQuery }
}
