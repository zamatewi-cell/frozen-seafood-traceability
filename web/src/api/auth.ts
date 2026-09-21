import { apiRequest, clearCsrfToken } from './client'
import type { CurrentUser } from '@/types/enterprise'

/**
 * 企业端认证接口，严格对应 Spring Boot AuthController / CurrentUserController：
 * - POST /api/v1/auth/login（需 CSRF）
 * - POST /api/v1/auth/logout（需 CSRF，204）
 * - GET  /api/v1/me
 */

export async function login(username: string, password: string): Promise<CurrentUser> {
  const user = await apiRequest<CurrentUser>('/api/v1/auth/login', {
    method: 'POST',
    body: { username, password },
    skipUnauthorizedHandler: true
  })
  return user
}

export async function logout(): Promise<void> {
  try {
    await apiRequest<void>('/api/v1/auth/logout', { method: 'POST', skipUnauthorizedHandler: true })
  } finally {
    // 服务端 Session 已失效（或本就无效），旧 CSRF 凭据随之作废
    clearCsrfToken()
  }
}

export async function fetchCurrentUser(signal?: AbortSignal): Promise<CurrentUser> {
  return apiRequest<CurrentUser>('/api/v1/me', { signal, skipUnauthorizedHandler: true })
}
