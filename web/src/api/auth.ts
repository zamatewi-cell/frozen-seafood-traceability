import type { ProblemDetails } from '@/types/trace'

/**
 * 企业工作台认证 API。
 * 走 /api/v1/auth/*，基于服务端会话 + CSRF，strictly 不持久化令牌。
 */

export interface CurrentUser {
  userId: number
  username: string
  displayName: string
  orgId: number
  orgNo: string
  orgName: string
  orgType: string
  roles: string[]
  scopes: string[]
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

const AUTH_STORAGE_KEY = 'fst_auth_user'

async function fetchCsrf(): Promise<string> {
  const res = await fetch('/api/v1/auth/csrf', { credentials: 'include' })
  const payload = (await res.json()) as Envelope<{ headerName: string; parameterName: string; token: string }>
  const token = payload.data?.token
  if (!token) throw new Error('CSRF 获取失败')
  return token
}

export async function login(username: string, password: string): Promise<CurrentUser> {
  const csrf = await fetchCsrf()
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-CSRF-TOKEN': csrf
    },
    body: JSON.stringify({ username, password })
  })
  const payload = (await res.json()) as Envelope<CurrentUser>
  if (!res.ok || !payload.data) {
    const problem = payload as unknown as ProblemDetails
    throw new Error(problem?.detail || payload.detail || `登录失败 (${res.status})`)
  }
  sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(payload.data))
  return payload.data
}

export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  const res = await fetch('/api/v1/auth/me', { credentials: 'include' })
  if (!res.ok) return null
  const payload = (await res.json()) as Envelope<CurrentUser>
  return payload.data ?? null
}

export function getCachedUser(): CurrentUser | null {
  try {
    const raw = sessionStorage.getItem(AUTH_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as CurrentUser) : null
  } catch {
    return null
  }
}

export function clearCachedUser(): void {
  sessionStorage.removeItem(AUTH_STORAGE_KEY)
}

export async function logout(): Promise<void> {
  try {
    const csrf = await fetchCsrf()
    await fetch('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'include',
      headers: { 'X-CSRF-TOKEN': csrf }
    })
  } catch {
    // 忽略登出网络异常，本地状态照常清理
  }
  clearCachedUser()
}