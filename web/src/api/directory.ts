import { apiRequest, apiRequestPage } from './client'
import type { OrganizationSummary, Product, SiteSummary } from '@/types/enterprise'

/**
 * 最小只读目录：
 * - GET /api/v1/products/{productId}（产品为平台级主数据，任意登录用户可读）
 * - GET /api/v1/products?status=ACTIVE（建批表单的产品下拉，不缓存）
 * - GET /api/v1/organizations/{orgId}（已认证用户可读任意组织的白名单摘要）
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

/**
 * 读取可用于建批的 ACTIVE 产品（GET /api/v1/products?status=ACTIVE）。
 * 服务端已按状态过滤，这里再做一次防御性过滤，保证下拉框只出现 ACTIVE 产品。
 */
export async function listActiveProducts(signal?: AbortSignal): Promise<Product[]> {
  const result = await apiRequestPage<Product>('/api/v1/products', {
    query: { status: 'ACTIVE', page: 1, size: 100 },
    signal
  })
  return result.items.filter((product) => product.status === 'ACTIVE')
}

export function getOrganization(orgId: number): Promise<OrganizationSummary> {
  return cached(organizationCache, orgId, () =>
    apiRequest<OrganizationSummary>(`/api/v1/organizations/${encodeURIComponent(String(orgId))}`))
}

/**
 * 启用组织目录（GET /api/v1/organizations?orgType=）：交接接收方与承运方下拉。不缓存，保证看到最新启用状态。
 */
export function listOrganizations(orgType?: string, signal?: AbortSignal): Promise<OrganizationSummary[]> {
  return apiRequest<OrganizationSummary[]>('/api/v1/organizations', { query: { orgType }, signal })
}

/**
 * 组织启用场所目录（GET /api/v1/organizations/{orgId}/sites）：运输任务起止场所下拉。
 */
export function listSites(orgId: number, signal?: AbortSignal): Promise<SiteSummary[]> {
  return apiRequest<SiteSummary[]>(`/api/v1/organizations/${encodeURIComponent(String(orgId))}/sites`, { signal })
}

export function clearDirectoryCache(): void {
  productCache.clear()
  organizationCache.clear()
}
