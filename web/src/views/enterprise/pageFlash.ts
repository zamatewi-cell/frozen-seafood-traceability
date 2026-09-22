/**
 * 跨页面的一次性提示（仅内存），按页面键区分，例如 `shipment:12`、`inbound`。
 * 业务操作成功后在目标页面展示一次结果与下一步提示，随后清除。
 */

export interface PageFlash {
  tone: 'success' | 'warning'
  message: string
}

const flashes = new Map<string, PageFlash>()

export function setPageFlash(key: string, flash: PageFlash): void {
  flashes.set(key, flash)
}

export function takePageFlash(key: string): PageFlash | null {
  const flash = flashes.get(key) ?? null
  flashes.delete(key)
  return flash
}
