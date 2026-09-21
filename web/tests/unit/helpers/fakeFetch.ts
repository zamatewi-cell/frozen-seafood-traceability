import { vi } from 'vitest'

/**
 * 单元测试用的最小 fetch 替身：按 “METHOD path” 匹配处理器并记录每次调用。
 * 仅用于单元测试；真实浏览器冒烟不使用任何拦截或替身。
 */

export interface RecordedCall {
  method: string
  url: string
  path: string
  search: URLSearchParams
  headers: Record<string, string>
  body: unknown
  credentials?: RequestCredentials
}

export interface FakeResponse {
  status: number
  body?: unknown
  contentType?: string
}

type Handler = (call: RecordedCall) => FakeResponse | Promise<FakeResponse>

export function envelope<T>(data: T, page?: { number: number; size: number; totalElements: number; totalPages: number }) {
  return { data, meta: { requestId: 'req-unit', timestamp: '2026-09-21T08:00:00Z', ...(page ? { page } : {}) } }
}

export function problem(status: number, code: string, detail = code) {
  return { status, body: { type: 'about:blank', title: code, status, code, detail, requestId: `req-${status}` }, contentType: 'application/problem+json' }
}

export function installFakeFetch(routes: Record<string, Handler>) {
  const calls: RecordedCall[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const parsed = new URL(url, 'http://localhost')
    const method = (init?.method || 'GET').toUpperCase()
    const headers = Object.fromEntries(Object.entries((init?.headers || {}) as Record<string, string>))
    const call: RecordedCall = {
      method,
      url,
      path: parsed.pathname,
      search: parsed.searchParams,
      headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      credentials: init?.credentials
    }
    calls.push(call)
    const handler = routes[`${method} ${parsed.pathname}`]
    const result: FakeResponse = handler ? await handler(call) : { status: 404, body: { status: 404, code: 'RESOURCE_NOT_FOUND' } }
    const contentType = result.contentType || (result.status >= 400 ? 'application/problem+json' : 'application/json')
    return {
      ok: result.status >= 200 && result.status < 300,
      status: result.status,
      headers: new Headers(result.body === undefined ? {} : { 'content-type': contentType }),
      json: async () => result.body
    } as Response
  })
  globalThis.fetch = fetchMock as unknown as typeof fetch
  return { calls, fetchMock }
}

export const CSRF_ROUTE = {
  'GET /api/v1/auth/csrf': () => ({ status: 200, body: envelope({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: 'csrf-token-1' }) })
}

export const sampleUser = {
  userId: 7,
  username: 'processor_op',
  displayName: '加工操作员',
  orgId: 30,
  orgNo: 'ORG_PROC_01',
  orgName: '东海水产加工有限公司',
  orgType: 'PROCESSOR',
  roles: ['OPERATOR'],
  scopes: ['ORG_ONLY']
}
