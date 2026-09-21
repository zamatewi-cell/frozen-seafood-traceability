import { apiRequest } from './client'
import type { OrganizationSummary, Product } from '@/types/enterprise'

/**
 * 最小只读目录：
 * - GET /api/v1/products/{productId}（产品为平台级主数据，任意登录用户可读）
 * - GET /api/v1/organizations/{orgId}（企业用户仅可读本组织，PLATFORM 可读任意组织）
 *
 * 同一会话内按 ID 做内存缓存，避免列表页重复请求；登出时必须调用 clearDirectoryCache。
 */

const productCache = new Map<number, Promise<Product>>()
const organizationCache = new Map<number, Promise<OrganizationSummary>>()

function cached<T>(cache: Map<number, Promise<T>>, id: number, load: () => Promise<T>): Promise<T> {
  const existing = cache.get(id)
  if (existing) return existing
  const pending = load()
  cache.set(id, pending)
  pending.catch(() => cache.delete(id))
  return pending
}

export function getProduct(productId: number): Promise<Product> {
  return cached(productCache, productId, () =>
    apiRequest<Product>(`/api/v1/products/${encodeURIComponent(String(productId))}`))
}

export function getOrganization(orgId: number): Promise<OrganizationSummary> {
  return cached(organizationCache, orgId, () =>
    apiRequest<OrganizationSummary>(`/api/v1/organizations/${encodeURIComponent(String(orgId))}`))
}

export function clearDirectoryCache(): void {
  productCache.clear()
  organizationCache.clear()
}
