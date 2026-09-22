import { ApiError } from '@/api/client'

/**
 * 企业端写操作的错误提示：按 HTTP 状态给出可操作的中文说明，并附带服务端 detail 与请求编号。
 */
export function describeWriteError(err: unknown, action: string): string {
  if (!(err instanceof ApiError)) return `${action}失败，请稍后重试`
  const suffix = err.requestId ? `（请求编号 ${err.requestId}）` : ''
  switch (err.status) {
    case 0:
    case 408:
      return `${err.message}。${action}结果未知，可直接重试（相同内容不会重复创建）`
    case 400:
      return `${action}失败：提交内容不符合要求，${err.message}${suffix}`
    case 403:
      return `${action}失败：当前账号无权执行该操作，${err.message}${suffix}`
    case 404:
      return `${action}失败：相关数据不存在或已失效，${err.message}${suffix}`
    case 409:
      return `${action}失败：数据已被修改或请求冲突，请刷新后重试。${err.message}${suffix}`
    case 422:
      return `${action}失败：当前业务状态不允许该操作，${err.message}${suffix}`
    default:
      return `${action}失败：${err.message}${suffix}`
  }
}
