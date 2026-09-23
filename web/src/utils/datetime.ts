/**
 * 业务时间输入辅助（`<input type="datetime-local" step="1">`）。
 *
 * 业务时间精确到秒：分钟精度会把同一分钟内晚于运输到达的销售 / 仓储登记成更早的时间，
 * 使公开时间线看起来“先销售、后到达”。这里只保留到秒，不在界面上编造毫秒。
 */

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** 本地时间 → `YYYY-MM-DDTHH:mm:ss`（datetime-local 的秒精度取值）。 */
export function toLocalDateTimeInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
