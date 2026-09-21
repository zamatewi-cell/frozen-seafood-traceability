import { computed, reactive, readonly } from 'vue'
import * as authApi from '@/api/auth'
import { ApiError, clearCsrfToken } from '@/api/client'
import { clearDirectoryCache } from '@/api/directory'
import type { CurrentUser } from '@/types/enterprise'

/**
 * 企业端会话状态（仅内存，不做任何浏览器持久化）。
 *
 * 会话的唯一可信来源是服务端 Session Cookie：页面刷新后通过 GET /api/v1/me 恢复，
 * 前端不保存、不伪造用户、角色或组织信息。
 */

export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous'

interface SessionState {
  status: SessionStatus
  user: CurrentUser | null
}

const state = reactive<SessionState>({
  status: 'unknown',
  user: null
})

let restorePromise: Promise<void> | null = null

function applyUser(user: CurrentUser) {
  state.user = user
  state.status = 'authenticated'
}

/** 清空前端会话相关的全部内存状态。 */
export function clearSession(): void {
  state.user = null
  state.status = 'anonymous'
  restorePromise = null
  clearCsrfToken()
  clearDirectoryCache()
}

/**
 * 首次进入受保护或访客路由时调用，确认服务端会话是否仍有效。
 * 同一时刻只发起一次 /me 请求；401 视为未登录，其他错误向上抛出。
 */
export function ensureSessionRestored(): Promise<void> {
  if (state.status !== 'unknown') return Promise.resolve()
  if (!restorePromise) {
    restorePromise = authApi.fetchCurrentUser()
      .then(applyUser)
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.status === 401) {
          clearSession()
          return
        }
        restorePromise = null
        throw err
      })
  }
  return restorePromise
}

export async function login(username: string, password: string): Promise<CurrentUser> {
  // 登录前丢弃旧目录缓存，避免不同账号之间串用
  clearDirectoryCache()
  const user = await authApi.login(username, password)
  applyUser(user)
  return user
}

/**
 * 注销服务端会话。
 * 只有服务端确认注销成功，或明确返回 401（会话本已失效）时才清理本地会话；
 * 网络错误、403、5xx 等无法确认服务端 Session 已失效的情况保留本地状态并抛出，由界面提示并允许重试。
 */
export async function logout(): Promise<void> {
  try {
    await authApi.logout()
  } catch (err: unknown) {
    if (!(err instanceof ApiError && err.status === 401)) throw err
  }
  clearSession()
}

export function useSession() {
  return {
    state: readonly(state),
    isAuthenticated: computed(() => state.status === 'authenticated'),
    user: computed(() => state.user)
  }
}

/** 仅供测试重置模块状态。 */
export function resetSessionForTests(): void {
  state.user = null
  state.status = 'unknown'
  restorePromise = null
  clearCsrfToken()
  clearDirectoryCache()
}
