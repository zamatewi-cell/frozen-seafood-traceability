import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { fetchJson, ApiError, NotFoundError } from '@/api/client'

describe('API Client', () => {
  const originalFetch = global.fetch

  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    global.fetch = originalFetch
  })

  it('unwraps SuccessEnvelope data correctly', async () => {
    const mockEnvelope = {
      data: { publicTraceId: 'WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A', batchStatus: 'ACTIVE' },
      meta: { requestId: 'req-123', timestamp: '2026-09-10T08:00:00Z' }
    }

    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ 'content-type': 'application/json', 'x-request-id': 'req-123' }),
      json: async () => mockEnvelope
    })

    const result = await fetchJson<typeof mockEnvelope.data>('/api/test')
    expect(result).toEqual(mockEnvelope.data)
  })

  it('throws NotFoundError on 404 response with code and safe message', async () => {
    const mockProblem = {
      status: 404,
      code: 'PUBLIC_TRACE_NOT_FOUND',
      title: '未找到公开追溯信息',
      detail: '未找到该追溯码或该码已失效',
      requestId: 'req-404'
    }

    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      headers: new Headers({ 'content-type': 'application/problem+json' }),
      json: async () => mockProblem
    })

    await expect(fetchJson('/api/test')).rejects.toThrow(NotFoundError)
  })

  it('throws ApiError with status and requestId on server failure', async () => {
    const mockProblem = {
      status: 500,
      code: 'INTERNAL_SERVER_ERROR',
      title: '服务器内部错误',
      detail: '系统繁忙，请稍后重试',
      requestId: 'req-500-safe'
    }

    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      headers: new Headers({ 'content-type': 'application/problem+json' }),
      json: async () => mockProblem
    })

    try {
      await fetchJson('/api/test')
      expect.unreachable('Should have thrown')
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      const apiErr = err as ApiError
      expect(apiErr.status).toBe(500)
      expect(apiErr.requestId).toBe('req-500-safe')
      expect(apiErr.message).toBe('系统繁忙，请稍后重试')
    }
  })

  it('forwards caller cancellation instead of misreporting it as a timeout', async () => {
    const abortController = new AbortController()
    global.fetch = vi.fn((_url, init) => new Promise((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('cancelled', 'AbortError'))
      })
    })) as typeof fetch

    const request = fetchJson('/api/test', { signal: abortController.signal })
    abortController.abort()

    await expect(request).rejects.toMatchObject({ name: 'AbortError' })
  })
})
