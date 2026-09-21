import type { ProblemDetails } from '@/types/trace'

/**
 * 多角色订单 API（采购进货单 / 销售订单）。
 * 依赖服务端会话，写操作需带 CSRF。
 */

export interface OrderItem {
  id: number
  productId: number
  productName: string
  quantity: number
  unitCode: string
  unitPrice: number
  rowAmount: number
}

export interface PurchaseOrder {
  id: number
  orderNo: string
  buyerOrgId: number
  sellerOrgId: number
  orderType: string
  status: string
  orderedAt: string
  expectedDeliveryAt: string | null
  note: string | null
  amountTotal: number
  currencyCode: string
  items: OrderItem[]
}

export interface SalesOrder {
  id: number
  orderNo: string
  sellerOrgId: number
  customerName: string
  customerPhone: string | null
  deliveryAddress: string
  status: string
  traceCodeId: number | null
  packedPackageNo: string | null
  placedAt: string
  deliveredAt: string | null
  note: string | null
  amountTotal: number
  currencyCode: string
  items: OrderItem[]
}

interface Envelope<T> {
  code?: string
  data?: T
  detail?: string
  requestId?: string
  status?: number
  title?: string
  fieldErrors?: unknown[]
}

async function fetchCsrf(): Promise<string> {
  const res = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  const payload = (await res.json()) as Envelope<{ token: string }>
  const token = payload.data?.token
  if (!token) throw new Error('CSRF 获取失败')
  return token
}

async function parseEnvelope<T>(res: Response): Promise<T> {
  const payload = (await res.json()) as Envelope<T>
  if (!res.ok || payload.data === undefined) {
    const problem = payload as unknown as ProblemDetails
    throw new Error(problem?.detail || payload.detail || `请求失败 (${res.status})`)
  }
  return payload.data
}

export async function listPurchaseOrders(): Promise<PurchaseOrder[]> {
  const res = await fetch('/api/v1/orders/purchase', { credentials: 'include' })
  return parseEnvelope(res)
}

export async function listSalesOrders(): Promise<SalesOrder[]> {
  const res = await fetch('/api/v1/orders/sales', { credentials: 'include' })
  return parseEnvelope(res)
}

export interface PurchaseCreatePayload {
  sellerOrgId: number
  orderType: string
  note?: string
  items: { productId: number; quantity: number; unitPrice: number }[]
}

export async function createPurchaseOrder(payload: PurchaseCreatePayload): Promise<PurchaseOrder> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/orders/purchase', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<PurchaseOrder>(res)
}

export interface SalesCreatePayload {
  customerName: string
  customerPhone?: string
  deliveryAddress: string
  note?: string
  items: { productId: number; quantity: number; unitPrice: number }[]
}

export async function createSalesOrder(payload: SalesCreatePayload): Promise<SalesOrder> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/orders/sales', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify(payload)
  })
  return parseEnvelope<SalesOrder>(res)
}