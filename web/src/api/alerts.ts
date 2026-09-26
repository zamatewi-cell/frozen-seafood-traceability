import { apiRequest } from './client'
import type { Alert, AlertStatus } from '@/types/enterprise'

/**
 * 告警接口（Phase B PB3 起）：
 * - GET  /api/v1/alerts?status=&shipmentId=（告警归属组织、运输任务接收方 / 承运方与平台只读；按主键倒序）
 * - GET  /api/v1/alerts/{alertId}（详情：受影响批次快照与处置历史）
 * - POST /api/v1/alerts/{alertId}/acknowledge（告警归属组织 QUALITY_MANAGER；OPEN → ACKNOWLEDGED）
 * 告警只由系统在温度登记时按持续超温规则创建，前端没有创建入口。写请求需要 CSRF 与 Idempotency-Key。服务端是最终权限边界。
 */

const base = '/api/v1/alerts'
const one = (alertId: number) => `${base}/${encodeURIComponent(String(alertId))}`

export interface AlertListQuery {
  status?: AlertStatus
  shipmentId?: number
}

export function listAlerts(query: AlertListQuery = {}, signal?: AbortSignal): Promise<Alert[]> {
  return apiRequest<Alert[]>(base, { query: { status: query.status, shipmentId: query.shipmentId }, signal })
}

export function getAlert(alertId: number, signal?: AbortSignal): Promise<Alert> {
  return apiRequest<Alert>(one(alertId), { signal })
}

export function acknowledgeAlert(alertId: number, note: string | undefined, idempotencyKey: string): Promise<Alert> {
  return apiRequest<Alert>(`${one(alertId)}/acknowledge`, {
    method: 'POST',
    body: note ? { note } : {},
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}
