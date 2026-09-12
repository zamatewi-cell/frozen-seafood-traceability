import type { ProblemDetails, SuccessEnvelope } from '@/types/trace'

/**
 * 统一 API 客户端。
 * 严格只调用白名单接口，解析 RFC 9457 错误与 SuccessEnvelope，不持久化敏感状态。
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
}

export class NotFoundError extends ApiError {
  constructor(code = 'PUBLIC_TRACE_NOT_FOUND', message = '未找到该追溯码或该码已失效，请核对后重试', requestId?: string) {
    super(message, 404, code, requestId)
    this.name = 'NotFoundError'
  }
}

export interface RequestOptions {
  timeoutMs?: number
  headers?: Record<string, string>
  signal?: AbortSignal
}

const DEFAULT_TIMEOUT_MS = 10000

export function getBaseApiUrl(): string {
  const envUrl = import.meta.env.VITE_API_BASE_URL
  if (typeof envUrl === 'string' && envUrl.trim().length > 0) {
    return envUrl.trim().replace(/\/+$/, '')
  }
  return ''
}

export async function fetchJson<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const baseUrl = getBaseApiUrl()
  const normalizedEndpoint = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
  const url = `${baseUrl}${normalizedEndpoint}`

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

  try {
    const res = await fetch(url, {
      method: 'GET',
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(options.headers || {})
      },
      signal: controller.signal
    })

    const contentType = res.headers.get('content-type') || ''
    const isJson = contentType.includes('application/json') || contentType.includes('application/problem+json')
    const rawData = isJson ? await res.json().catch(() => null) : null

    // 提取响应中的 requestId (来自 header 或 body)
    const headerRequestId = res.headers.get('x-request-id') || undefined

    if (res.ok) {
      if (rawData && typeof rawData === 'object' && 'data' in rawData) {
        const envelope = rawData as SuccessEnvelope<T>
        return envelope.data
      }
      return rawData as T
    }

    // 统一处理 404
    if (res.status === 404) {
      const code = rawData?.code || 'PUBLIC_TRACE_NOT_FOUND'
      const reqId = rawData?.requestId || headerRequestId
      throw new NotFoundError(code, rawData?.detail || '未找到该追溯码或该码已失效，请核对后重试', reqId)
    }

    // 处理 RFC 9457 Problem Details
    const problem = rawData as ProblemDetails | null
    const title = problem?.title || `请求失败 (${res.status})`
    const detail = problem?.detail || res.statusText || title
    const code = problem?.code
    const reqId = problem?.requestId || headerRequestId

    throw new ApiError(detail, res.status, code, reqId, problem || undefined)
  } catch (err: unknown) {
    if (err instanceof ApiError) {
      throw err
    }
    if (err instanceof DOMException && err.name === 'AbortError') {
      if (!timedOut && options.signal?.aborted) throw err
      throw new ApiError('网络请求超时，请检查网络连接后重试', 408, 'REQUEST_TIMEOUT')
    }
    throw new ApiError(
      err instanceof Error ? err.message : '网络连接失败，请稍后重试',
      0,
      'NETWORK_ERROR'
    )
  } finally {
    clearTimeout(timeoutId)
    options.signal?.removeEventListener('abort', forwardAbort)
  }
}
