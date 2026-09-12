/**
 * 展示格式化与标签转换工具。
 */

/**
 * 格式化 ISO 日期时间为可读时间。
 */
export function formatIsoDateTime(isoStr: string | null | undefined): string {
  if (!isoStr) return '暂无记录'
  try {
    const d = new Date(isoStr)
    if (isNaN(d.getTime())) return isoStr
    const pad = (n: number) => String(n).padStart(2, '0')
    const year = d.getFullYear()
    const month = pad(d.getMonth() + 1)
    const day = pad(d.getDate())
    const hours = pad(d.getHours())
    const minutes = pad(d.getMinutes())
    const seconds = pad(d.getSeconds())
    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
  } catch {
    return String(isoStr)
  }
}

/**
 * 格式化生产日期 (yyyy-MM-dd)。
 */
export function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return '未标明'
  return String(dateStr)
}

/**
 * 水产大类字典转换。
 */
export function formatProductCategory(category: string | null | undefined): string {
  if (!category) return '水产品'
  const map: Record<string, string> = {
    FISH: '海水鱼类',
    CRUSTACEAN: '虾蟹甲壳类',
    SHELLFISH: '双壳贝类',
    CEPHALOPOD: '头足软体类',
    OTHER: '其他海产'
  }
  return map[category] || category
}

/**
 * 水产来源类型字典转换。
 */
export function formatOriginType(originType: string | null | undefined): string {
  if (!originType) return '产地信息'
  const map: Record<string, string> = {
    DOMESTIC_CAPTURE: '国内捕捞',
    DOMESTIC_FARMED: '国内养殖',
    IMPORT: '进口海产'
  }
  return map[originType] || originType
}

export interface StatusBadgeInfo {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
  description: string
}

/**
 * 批次流转状态样式与文本。
 */
export function formatBatchStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'ACTIVE':
      return {
        label: '当前记录正常',
        tone: 'success',
        description: '该批次处于正常供应链流通状态'
      }
    case 'FROZEN':
      return {
        label: '业务冻结状态',
        tone: 'warning',
        description: '质量管理部门已暂停该批次的出库与交接'
      }
    case 'RECALLED':
      return {
        label: '模拟召回提示',
        tone: 'danger',
        description: '该批次已触发模拟召回演练，流通已暂停'
      }
    case 'CLOSED':
      return {
        label: '流转已关闭',
        tone: 'neutral',
        description: '该批次全流程追溯档案已归档关闭'
      }
    default:
      return {
        label: status || '未知状态',
        tone: 'neutral',
        description: '状态信息以企业备案为准'
      }
  }
}

/**
 * 温度摘要判定样式与文本。
 */
export function formatTemperatureResult(result: string | null | undefined): {
  label: string
  tone: 'success' | 'warning' | 'danger'
} {
  switch (result) {
    case 'NO_BREACH_RECORDED':
      return { label: '当前记录中未发现超温事件', tone: 'success' }
    case 'BREACH_RECORDED':
      return { label: '当前记录中存在超温事件', tone: 'danger' }
    case 'INSUFFICIENT_DATA':
    default:
      return { label: '暂无实时时序采集（教学模拟）', tone: 'warning' }
  }
}
