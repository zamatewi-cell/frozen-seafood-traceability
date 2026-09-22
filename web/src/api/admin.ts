import type { ProblemDetails } from '@/types/trace'

/**
 * 系统管理 API（仅系统管理员）。
 */

export interface AdminOrg {
  id: number
  orgNo: string
  name: string
  orgType: string
  status: string
}

export interface AdminUser {
  id: number
  orgId: number
  username: string
  displayName: string
  jobType: string
  status: string
}

export interface AdminRole {
  id: number
  roleCode: string
  name: string
  scopeType: string
  status: string
}

export interface AdminOverview {
  orgCount: number
  userCount: number
  roleCount: number
  orgs: AdminOrg[]
  users: AdminUser[]
  roles: AdminRole[]
}

interface Envelope<T> {
  data?: T
  detail?: string
}

export async function fetchAdminOverview(): Promise<AdminOverview> {
  const res = await fetch('/api/v1/admin/overview', { credentials: 'include' })
  const payload = (await res.json()) as Envelope<AdminOverview>
  if (!res.ok || payload.data === undefined) {
    const problem = payload as unknown as ProblemDetails
    throw new Error(problem?.detail || payload.detail || `请求失败 (${res.status})`)
  }
  return payload.data
}