/**
 * 跨页面的一次性批次提示（仅内存）：例如“草稿已保存，但激活失败”，在进入批次详情时展示一次后清除。
 */

export interface BatchFlash {
  tone: 'success' | 'warning'
  message: string
}

const flashes = new Map<number, BatchFlash>()

export function setBatchFlash(batchId: number, flash: BatchFlash): void {
  flashes.set(batchId, flash)
}

export function takeBatchFlash(batchId: number): BatchFlash | null {
  const flash = flashes.get(batchId) ?? null
  flashes.delete(batchId)
  return flash
}
