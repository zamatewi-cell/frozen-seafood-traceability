import type { CsrfToken } from '@/types/enterprise'
import type { PagedResult, PageMeta, ProblemDetails, SuccessEnvelope } from '@/types/api'

/**
 * 统一 API 客户端。
 *
 * - 统一拼接 API 根路径，并始终携带 Cookie（服务端 Session）；
 * - 统一 JSON 序列化与 SuccessEnvelope 解包，分页请求返回 meta.page；
 * - 非 GET 的 /api/v1 请求自动附带会话绑定的 CSRF 凭据（来自 GET /api/v1/auth/csrf）；
 *   写请求返回 403 时不自动重发，只丢弃缓存凭据，下一次写请求会重新获取；
 * - 401 统一回调已注册的未认证处理器（清理会话并回到登录页）；
 * - 解析 RFC 9457 Problem Details、网络错误与超时；
 * - 不在任何浏览器持久化存储中保存凭据或业务数据。
 */

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly requestId?: string
  readonly problem?: ProblemDetails

  constructor(message: string, status: number, code?: string, requestId?: string, problem?: ProblemDetails) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
    this.problem = problem
  }

  get fieldErrors() {
    return this.problem?.fieldErrors ?? []
  }
}

export class NotFoundError extends ApiError {
  constructor(code = 'PUBLIC_TRACE_NOT_FOUND', message = '未找到该追溯码或该码已失效，请核对后重试', requestId?: string, problem?: ProblemDetails) {
    super(message, 404, code, requestId, problem)
    this.name = 'NotFoundError'
  }
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

export interface RequestOptions {
  method?: HttpMethod
  query?: Record<string, string | number | undefined | null>
  body?: unknown
  timeoutMs?: number
  headers?: Record<string, string>
  signal?: AbortSignal
  /** 为 true 时 401 不触发全局未认证处理器（用于会话恢复与登录本身）。 */
  skipUnauthorizedHandler?: boolean
}

const DEFAULT_TIMEOUT_MS = 10000
const CSRF_ENDPOINT = '/api/v1/auth/csrf'
const SAFE_METHODS: ReadonlySet<string> = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

let unauthorizedHandler: ((error: ApiError) => void) | null = null
let csrfTokenPromise: Promise<CsrfToken> | null = null

export function getBaseApiUrl(): string {
  const envUrl = import.meta.env.VITE_API_BASE_URL
  if (typeof envUrl === 'string' && envUrl.trim().length > 0) {
    return envUrl.trim().replace(/\/+$/, '')
  }
  return ''
}

/** 注册全局 401 处理器；传入 null 取消注册。 */
export function setUnauthorizedHandler(handler: ((error: ApiError) => void) | null): void {
  unauthorizedHandler = handler
}

/** 丢弃内存中的 CSRF 凭据（登出或会话失效后必须调用）。 */
export function clearCsrfToken(): void {
  csrfTokenPromise = null
}

function buildUrl(endpoint: string, query?: RequestOptions['query']): string {
  const normalizedEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
  const params = new URLSearchParams()
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== null && String(value) !== '') {
        params.append(key, String(value))
      }
    }
  }
  const search = params.toString()
  return `${getBaseApiUrl()}${normalizedEndpoint}${search ? `?${search}` : ''}`
}

function requiresCsrf(endpoint: string, method: string): boolean {
  return !SAFE_METHODS.has(method) && endpoint.startsWith('/api/v1/')
}

interface RawResult {
  status: number
  body: unknown
  requestId?: string
}

async function send(endpoint: string, options: RequestOptions, extraHeaders: Record<string, string>): Promise<RawResult> {
  const method = options.method ?? 'GET'
  const controller = new AbortController()
  let timedOut = false
  const forwardAbort = () => controller.abort(options.signal?.reason)
  if (options.signal?.aborted) {
    forwardAbort()
  } else {
    options.signal?.addEventListener('abort', forwardAbort, { once: true })
  }
  const timeoutId = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, options.timeoutMs || DEFAULT_TIMEOUT_MS)

  const headers: Record<string, string> = {
    Accept: 'application/json, application/problem+json',
    ...extraHeaders,
    ...(options.headers || {})
  }
  const hasBody = options.body !== undefined
  if (hasBody) headers['Content-Type'] = 'application/json'

  try {
    const res = await fetch(buildUrl(endpoint, options.query), {
      method,
      headers,
      credentials: 'include',
      body: hasBody ? JSON.stringify(options.body) : undefined,
      signal: controller.signal
    })

    const contentType = res.headers.get('content-type') || ''
    const isJson = contentType.includes('application/json') || contentType.includes('application/problem+json')
    const body = isJson ? await res.json().catch(() => null) : null
    return { status: res.status, body, requestId: res.headers.get('x-request-id') || undefined }
  } catch (err: unknown) {
    if (err instanceof DOMException && err.name === 'AbortError') {
      if (!timedOut && options.signal?.aborted) throw err
      throw new ApiError('网络请求超时，请检查网络连接后重试', 408, 'REQUEST_TIMEOUT')
    }
    throw new ApiError('网络连接失败，请稍后重试', 0, 'NETWORK_ERROR')
  } finally {
    clearTimeout(timeoutId)
    options.signal?.removeEventListener('abort', forwardAbort)
  }
}

function toError(result: RawResult): ApiError {
  const problem = (result.body && typeof result.body === 'object' ? result.body : null) as ProblemDetails | null
  const requestId = problem?.requestId || result.requestId
  if (result.status === 404) {
    return new NotFoundError(
      problem?.code || 'RESOURCE_NOT_FOUND',
      problem?.detail || '请求的资源不存在或已失效',
      requestId,
      problem || undefined
    )
  }
  const title = problem?.title || `请求失败 (${result.status})`
  return new ApiError(problem?.detail || title, result.status, problem?.code, requestId, problem || undefined)
}

async function loadCsrfToken(signal?: AbortSignal): Promise<CsrfToken> {
  if (!csrfTokenPromise) {
    csrfTokenPromise = (async () => {
      const result = await send(CSRF_ENDPOINT, { method: 'GET', signal }, {})
      if (result.status < 200 || result.status >= 300) throw toError(result)
      return (result.body as SuccessEnvelope<CsrfToken>).data
    })()
    csrfTokenPromise.catch(() => {
      csrfTokenPromise = null
    })
  }
  return csrfTokenPromise
}

async function request(endpoint: string, options: RequestOptions): Promise<RawResult> {
  const method = options.method ?? 'GET'
  const normalizedEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
  const needsCsrf = requiresCsrf(normalizedEndpoint, method)

  let result: RawResult
  if (needsCsrf) {
    const token = await loadCsrfToken(options.signal)
    result = await send(normalizedEndpoint, options, { [token.headerName]: token.token })
    if (result.status === 403) {
      // 后端对 CSRF 失效与权限不足统一返回 403 ACCESS_DENIED，无法区分：
      // 绝不自动重发写请求，只丢弃可能过期的凭据，由用户显式重试时重新获取
      clearCsrfToken()
    }
  } else {
    result = await send(normalizedEndpoint, options, {})
  }

  if (result.status >= 200 && result.status < 300) return result

  const error = toError(result)
  if (error.status === 401) {
    clearCsrfToken()
    if (!options.skipUnauthorizedHandler) unauthorizedHandler?.(error)
  }
  throw error
}

/** 发送请求并返回 SuccessEnvelope.data（204 返回 undefined）。 */
export async function apiRequest<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const result = await request(endpoint, options)
  const body = result.body
  if (body && typeof body === 'object' && 'data' in body) {
    return (body as SuccessEnvelope<T>).data
  }
  return body as T
}

/** 发送分页请求，返回数据与 meta.page。 */
export async function apiRequestPage<T>(endpoint: string, options: RequestOptions = {}): Promise<PagedResult<T>> {
  const result = await request(endpoint, options)
  const envelope = result.body as SuccessEnvelope<T[]> | null
  const page: PageMeta | undefined = envelope?.meta?.page
  if (!envelope || !Array.isArray(envelope.data) || !page) {
    throw new ApiError('服务端分页响应格式不符合契约', result.status, 'INVALID_RESPONSE', result.requestId)
  }
  return { items: envelope.data, page }
}

/** 兼容消费者查询：GET 并解包 SuccessEnvelope。 */
export async function fetchJson<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  return apiRequest<T>(endpoint, { ...options, method: 'GET' })
}
