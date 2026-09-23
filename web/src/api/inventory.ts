/**
 * 库存聚合查询 API。
 * 返回当前登录组织名下所有可售批次，附带已分配量、可用量、溯源事件数。
 */

interface Envelope<T> {
  code?: string
  data?: T
  detail?: string
  requestId?: string
  status?: number
  title?: string
}

export interface InventoryBatch {
  batchId: number
  batchNo: string
  productId: number
  productName: string | null
  batchType: string
  totalQuantity: number
  allocatedQuantity: number
  availableQuantity: number
  unitCode: string
  status: string
  originType: string
  originText: string
  productionDate: string | null
  createdAt: string | null
  traceEventCount: number
}

async function parseEnvelope<T>(res: Response): Promise<T> {
  const payload = (await res.json()) as Envelope<T>
  if (!res.ok || payload.data === undefined) {
    throw new Error(payload?.detail || `请求失败 (${res.status})`)
  }
  return payload.data
}

export async function listMyInventory(): Promise<InventoryBatch[]> {
  const res = await fetch('/api/v1/inventory/my-batches', { credentials: 'include' })
  return parseEnvelope(res)
}
