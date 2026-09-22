import type { ProblemDetails } from '@/types/trace'

export interface Product {
  id: number
  productCode: string
  publicName: string
  scientificName: string
  category: string
  productType: string
  specification: string
  sourceType: string
  baseUnitCode: string
  status: string
}

interface Envelope<T> {
  data?: T
  detail?: string
}

async function parseEnvelope<T>(res: Response): Promise<T> {
  const payload = (await res.json()) as Envelope<T>
  if (!res.ok || payload.data === undefined) {
    const problem = payload as unknown as ProblemDetails
    throw new Error(problem?.detail || payload.detail || `请求失败 (${res.status})`)
  }
  return payload.data
}

export async function listProducts(productType?: string): Promise<Product[]> {
  const params = new URLSearchParams()
  params.set('page', '1')
  params.set('size', '100')
  if (productType) params.set('keyword', productType)
  const res = await fetch(`/api/v1/products?${params.toString()}`, { credentials: 'include' })
  const list = await parseEnvelope<Product[]>(res)
  if (productType) {
    return list.filter((p) => p.productType === productType)
  }
  return list
}
