import { apiRequest, apiRequestPage } from './client'
import type { PagedResult } from '@/types/api'
import type { Shipment, ShipmentListQuery } from '@/types/enterprise'

/**
 * 冷链运输任务接口（/api/v1/shipments）。发货方创建 / 绑定 / 解绑 / 取消，承运商发运 / 到达；
 * 服务端独立校验三方组织边界与状态机，页面只做入口可见性控制。
 */

function path(shipmentId: number, suffix = ''): string {
  return `/api/v1/shipments/${encodeURIComponent(String(shipmentId))}${suffix}`
}

export function listShipments(query: ShipmentListQuery, signal?: AbortSignal): Promise<PagedResult<Shipment>> {
  return apiRequestPage<Shipment>('/api/v1/shipments', {
    query: { role: query.role, status: query.status, page: query.page, size: query.size },
    signal
  })
}

export function getShipment(shipmentId: number, signal?: AbortSignal): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId), { signal })
}

export interface CreateShipmentPayload {
  carrierOrgId: number
  originSiteId: number
  destinationSiteId: number
  vehicleOrContainerNo: string
}

export function createShipment(payload: CreateShipmentPayload, idempotencyKey: string): Promise<Shipment> {
  return apiRequest<Shipment>('/api/v1/shipments', {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function bindTransfer(
  shipmentId: number,
  payload: { transferId: number; expectedTransferVersion: number },
  idempotencyKey: string
): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId, '/transfers'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function unbindTransfer(shipmentId: number, transferId: number, expectedTransferVersion: number): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId, `/transfers/${encodeURIComponent(String(transferId))}`), {
    method: 'DELETE',
    query: { expectedTransferVersion }
  })
}

export function dispatchShipment(
  shipmentId: number,
  payload: { loadedAt: string; expectedVersion: number },
  idempotencyKey: string
): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId, '/dispatch'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function arriveShipment(
  shipmentId: number,
  payload: { unloadedAt: string; expectedVersion: number },
  idempotencyKey: string
): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId, '/arrive'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

export function cancelShipment(
  shipmentId: number,
  payload: { reason: string; expectedVersion: number },
  idempotencyKey: string
): Promise<Shipment> {
  return apiRequest<Shipment>(path(shipmentId, '/cancel'), {
    method: 'POST',
    body: payload,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}
