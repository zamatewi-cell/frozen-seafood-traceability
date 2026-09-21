import { describe, it, expect, afterEach, vi } from 'vitest'
import { apiRequest, apiRequestPage, ApiError, clearCsrfToken, setUnauthorizedHandler } from '@/api/client'
import { CSRF_ROUTE, envelope, installFakeFetch, problem } from './helpers/fakeFetch'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
  setUnauthorizedHandler(null)
  clearCsrfToken()
})

describe('enterprise API client', () => {
  it('always sends cookies and drops empty query parameters', async () => {
    const { calls } = installFakeFetch({
      'GET /api/v1/batches': () => ({ status: 200, body: envelope([], { number: 2, size: 20, totalElements: 21, totalPages: 2 }) })
    })

    const result = await apiRequestPage('/api/v1/batches', { query: { page: 2, size: 20, flowStatus: 'ACTIVE', riskStatus: undefined } })

    expect(result.page.totalElements).toBe(21)
    expect(calls).toHaveLength(1)
    expect(calls[0].credentials).toBe('include')
    expect(calls[0].search.get('page')).toBe('2')
    expect(calls[0].search.get('flowStatus')).toBe('ACTIVE')
    expect(calls[0].search.has('riskStatus')).toBe(false)
    expect(calls[0].headers['X-CSRF-TOKEN']).toBeUndefined()
  })

  it('attaches the session CSRF token to write requests and reuses it', async () => {
    const { calls } = installFakeFetch({
      ...CSRF_ROUTE,
      'POST /api/v1/auth/login': (call) => ({ status: 200, body: envelope({ echo: call.body }) }),
      'POST /api/v1/auth/logout': () => ({ status: 204 })
    })

    const login = await apiRequest<{ echo: unknown }>('/api/v1/auth/login', { method: 'POST', body: { username: 'u', password: 'p' } })
    const logout = await apiRequest('/api/v1/auth/logout', { method: 'POST' })

    expect(login.echo).toEqual({ username: 'u', password: 'p' })
    expect(logout).toBeNull()
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([
      'GET /api/v1/auth/csrf',
      'POST /api/v1/auth/login',
      'POST /api/v1/auth/logout'
    ])
    expect(calls[1].headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
    expect(calls[1].headers['Content-Type']).toBe('application/json')
    expect(calls[2].headers['X-CSRF-TOKEN']).toBe('csrf-token-1')
  })

  it('refreshes a rejected CSRF token once and retries the write request', async () => {
    let tokenVersion = 0
    let attempts = 0
    const { calls } = installFakeFetch({
      'GET /api/v1/auth/csrf': () => {
        tokenVersion += 1
        return { status: 200, body: envelope({ headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: `token-${tokenVersion}` }) }
      },
      'POST /api/v1/auth/logout': (call) => {
        attempts += 1
        return call.headers['X-CSRF-TOKEN'] === 'token-2' ? { status: 204 } : problem(403, 'ACCESS_DENIED')
      }
    })

    await apiRequest('/api/v1/auth/logout', { method: 'POST' })

    expect(attempts).toBe(2)
    expect(calls.filter((c) => c.path === '/api/v1/auth/csrf')).toHaveLength(2)
  })

  it('reports 401 to the registered handler and forgets the CSRF token', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    const { calls } = installFakeFetch({
      ...CSRF_ROUTE,
      'GET /api/v1/batches/1': () => problem(401, 'AUTH_REQUIRED'),
      'POST /api/v1/auth/logout': () => problem(401, 'AUTH_REQUIRED')
    })

    await expect(apiRequest('/api/v1/batches/1')).rejects.toMatchObject({ status: 401, code: 'AUTH_REQUIRED' })
    expect(handler).toHaveBeenCalledTimes(1)

    await expect(apiRequest('/api/v1/auth/logout', { method: 'POST', skipUnauthorizedHandler: true }))
      .rejects.toBeInstanceOf(ApiError)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(calls.filter((c) => c.path === '/api/v1/auth/csrf')).toHaveLength(1)
  })

  it('parses the standard problem structure including field errors', async () => {
    installFakeFetch({
      'GET /api/v1/batches': () => ({
        status: 400,
        body: {
          status: 400,
          code: 'INVALID_REQUEST',
          title: '参数校验失败',
          detail: '不支持的批次流转状态: FOO',
          requestId: 'req-400',
          fieldErrors: [{ field: 'flowStatus', code: 'INVALID', message: '不支持的批次流转状态' }]
        }
      })
    })

    const error = await apiRequest('/api/v1/batches').catch((e: unknown) => e) as ApiError
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('INVALID_REQUEST')
    expect(error.requestId).toBe('req-400')
    expect(error.message).toBe('不支持的批次流转状态: FOO')
    expect(error.fieldErrors[0].field).toBe('flowStatus')
  })

  it('maps network failures to a NETWORK_ERROR ApiError', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch')) as unknown as typeof fetch

    await expect(apiRequest('/api/v1/me')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('rejects paged responses that do not carry meta.page', async () => {
    installFakeFetch({ 'GET /api/v1/batches': () => ({ status: 200, body: envelope([]) }) })

    await expect(apiRequestPage('/api/v1/batches')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
})
