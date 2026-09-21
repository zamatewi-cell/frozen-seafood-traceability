/**
 * 企业端与公共 API 共享的封套、分页与错误结构。
 * 字段与 docs/api/openapi.yaml 中的 SuccessEnvelope / PageMeta / Problem 保持一致。
 */

export interface PageMeta {
  number: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ResponseMeta {
  requestId: string
  timestamp: string
  page?: PageMeta
}

export interface SuccessEnvelope<T> {
  data: T
  meta: ResponseMeta
}

export interface FieldError {
  field: string
  code: string
  message: string
}

export interface ProblemDetails {
  type?: string
  title?: string
  status: number
  code?: string
  detail?: string
  instance?: string
  requestId?: string
  fieldErrors?: FieldError[]
}

export interface PagedResult<T> {
  items: T[]
  page: PageMeta
}
