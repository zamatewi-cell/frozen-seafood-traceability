import { apiRequest } from './client'
import type { ShipmentTemperatureRecord, TemperatureDataSource } from '@/types/enterprise'

/**
 * Shipment 在途温度记录接口（Phase B PB2）：
 * - GET  /api/v1/shipments/{shipmentId}/temperature-records（发货方、承运方、接收方与平台只读；按测量时间、主键排序）
 * - POST /api/v1/shipments/{shipmentId}/temperature-records（运输任务指定承运组织的 OPERATOR，仅 IN_TRANSIT）
 * 写请求需要 CSRF 与 Idempotency-Key；环节、温标、单点判定与登记时间全部由服务端决定。服务端是最终权限边界。
 */

const path = (shipmentId: number) => `/api/v1/shipments/${encodeURIComponent(String(shipmentId))}/temperature-records`

export interface RecordShipmentTemperaturePayload {
  measuredAt: string
  temperature: number
  dataSource: TemperatureDataSource
  deviceNo?: string
}

export function listShipmentTemperatures(shipmentId: number, signal?: AbortSignal): Promise<ShipmentTemperatureRecord[]> {
  return apiRequest<ShipmentTemperatureRecord[]>(path(shipmentId), { signal })
}

export function recordShipmentTemperature(
  shipmentId: number,
  payload: RecordShipmentTemperaturePayload,
  idempotencyKey: string
): Promise<ShipmentTemperatureRecord> {
  return apiRequest<ShipmentTemperatureRecord>(path(shipmentId), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}
