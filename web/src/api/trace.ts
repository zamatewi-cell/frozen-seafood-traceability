import { fetchJson, NotFoundError } from './client'
import type { PublicTrace } from '@/types/trace'
import { isValidPublicTraceId, normalizePublicTraceId } from '@/utils/validation'

/**
 * 消费者查询公开批次追溯投影。
 * 严格调用 GET /api/public/v1/public/traces/{publicTraceId}
 */
export async function fetchPublicTrace(rawTraceId: string, signal?: AbortSignal): Promise<PublicTrace> {
  const cleanId = normalizePublicTraceId(rawTraceId || '')

  // 客户端先行正则校验，格式不符与 404 表现完全一致
  if (!isValidPublicTraceId(cleanId)) {
    throw new NotFoundError('PUBLIC_TRACE_NOT_FOUND', '未找到该追溯码或该码已失效，请核对后重试')
  }

  return fetchJson<PublicTrace>(`/api/public/v1/public/traces/${encodeURIComponent(cleanId)}`, { signal })
}
