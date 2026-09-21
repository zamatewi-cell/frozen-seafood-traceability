import { reactive } from 'vue'
import { getOrganization, getProduct } from '@/api/directory'
import type { OrganizationSummary, Product } from '@/types/enterprise'

export type DirectoryEntry<T> =
  | { state: 'loading' }
  | { state: 'loaded'; value: T }
  | { state: 'failed' }

/**
 * 按 ID 解析产品与组织目录信息，供批次列表与详情展示真实名称。
 * 单个目录读取失败只影响对应单元格，不影响批次数据本身的展示。
 */
export function useDirectoryLabels() {
  const products = reactive(new Map<number, DirectoryEntry<Product>>())
  const organizations = reactive(new Map<number, DirectoryEntry<OrganizationSummary>>())

  function resolveProducts(ids: Iterable<number>) {
    for (const id of new Set(ids)) {
      if (products.has(id)) continue
      products.set(id, { state: 'loading' })
      getProduct(id)
        .then((value) => products.set(id, { state: 'loaded', value }))
        .catch(() => products.set(id, { state: 'failed' }))
    }
  }

  function resolveOrganizations(ids: Iterable<number>) {
    for (const id of new Set(ids)) {
      if (organizations.has(id)) continue
      organizations.set(id, { state: 'loading' })
      getOrganization(id)
        .then((value) => organizations.set(id, { state: 'loaded', value }))
        .catch(() => organizations.set(id, { state: 'failed' }))
    }
  }

  function productLabel(id: number): string {
    const entry = products.get(id)
    if (!entry || entry.state === 'loading') return '加载中…'
    if (entry.state === 'failed') return `产品 #${id}（名称不可用）`
    return entry.value.publicName
  }

  function organizationLabel(id: number): string {
    const entry = organizations.get(id)
    if (!entry || entry.state === 'loading') return '加载中…'
    if (entry.state === 'failed') return `组织 #${id}（名称不可用）`
    return entry.value.name
  }

  return { products, organizations, resolveProducts, resolveOrganizations, productLabel, organizationLabel }
}
