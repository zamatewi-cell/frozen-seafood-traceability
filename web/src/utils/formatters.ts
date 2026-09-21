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
 * 消费者页面的综合状态结论：风险状态优先于流转状态。
 * RECALLED / FROZEN 属于风险维度，与 ACTIVE / CLOSED 流转维度相互独立。
 */
export function formatPublicTraceStatus(
  flowStatus: string | null | undefined,
  riskStatus: string | null | undefined
): StatusBadgeInfo {
  if (riskStatus === 'RECALLED') {
    return {
      label: '模拟召回提示',
      tone: 'danger',
      description: '该批次已进入模拟召回演练，请勿继续食用或销售'
    }
  }
  if (riskStatus === 'FROZEN') {
    return {
      label: '业务冻结状态',
      tone: 'warning',
      description: '质量管理部门已暂停该批次的正常流转，等待调查结论'
    }
  }
  switch (flowStatus) {
    case 'ACTIVE':
      return {
        label: '当前记录正常',
        tone: 'success',
        description: '该批次处于正常供应链流通状态'
      }
    case 'CLOSED':
      return {
        label: '流转已关闭',
        tone: 'neutral',
        description: '该批次正常流转已结束，追溯档案继续可查'
      }
    default:
      return {
        label: flowStatus || '未知状态',
        tone: 'neutral',
        description: '状态信息以企业备案为准'
      }
  }
}

/**
 * 批次流转状态（flowStatus）标签。
 */
export function formatFlowStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'DRAFT':
      return { label: '草稿', tone: 'neutral', description: '尚未生效，不能正式流转' }
    case 'ACTIVE':
      return { label: '可流转', tone: 'success', description: '存在可管理物料，可参与符合条件的正常业务' }
    case 'CLOSED':
      return { label: '已关闭', tone: 'neutral', description: '正常数量流转已经结束' }
    default:
      return { label: status || '未知', tone: 'neutral', description: '未识别的流转状态' }
  }
}

/**
 * 批次风险状态（riskStatus）标签。
 */
export function formatRiskStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'NORMAL':
      return { label: '正常', tone: 'success', description: '没有阻断正常业务的风险状态' }
    case 'FROZEN':
      return { label: '冻结', tone: 'warning', description: '暂停正常流转，等待调查或质量结论' }
    case 'RECALLED':
      return { label: '模拟召回', tone: 'danger', description: '已进入模拟召回，作为历史风险终态保留' }
    default:
      return { label: status || '未知', tone: 'neutral', description: '未识别的风险状态' }
  }
}

/**
 * 批次环节类型字典。
 */
export function formatBatchType(batchType: string | null | undefined): string {
  if (!batchType) return '未标明'
  const map: Record<string, string> = {
    SOURCE: '来源批次',
    PROCESSING: '加工批次',
    DISTRIBUTION: '分销仓储批次',
    SALE: '终端销售批次'
  }
  return map[batchType] || batchType
}

/**
 * 企业端追溯事件类型字典。
 */
export function formatTraceEventType(eventType: string | null | undefined): string {
  if (!eventType) return '未标明'
  const map: Record<string, string> = {
    SOURCE: '来源（批次激活自动生成）',
    PURCHASE: '采购收购',
    PROCESS: '加工',
    FREEZE: '速冻',
    PACK: '包装',
    WAREHOUSE_IN: '冷库入库',
    WAREHOUSE_OUT: '冷库出库',
    TRANSPORT: '冷链运输',
    ARRIVAL: '运输到达',
    SALE: '终端销售'
  }
  return map[eventType] || eventType
}

/**
 * 追溯事件数据来源字典。
 */
export function formatDataSource(dataSource: string | null | undefined): string {
  if (!dataSource) return '未标明'
  const map: Record<string, string> = {
    MANUAL: '企业人工登记',
    IMPORT: '批量导入',
    SIMULATED: '教学模拟数据',
    DEVICE: '设备申报（非真实设备接入证明）'
  }
  return map[dataSource] || dataSource
}

/**
 * 组织类型字典。
 */
export function formatOrgType(orgType: string | null | undefined): string {
  if (!orgType) return '未标明'
  const map: Record<string, string> = {
    SOURCE: '来源企业',
    PROCESSOR: '加工企业',
    CARRIER: '承运企业',
    WAREHOUSE: '仓储企业',
    DISTRIBUTOR: '分销企业',
    RETAILER: '零售企业',
    PLATFORM: '平台监管方'
  }
  return map[orgType] || orgType
}

/**
 * 声明数量与计量单位（最多保留 3 位小数，去除多余的 0）。
 */
export function formatQuantity(quantity: number | string | null | undefined, unitCode: string | null | undefined): string {
  if (quantity === null || quantity === undefined || quantity === '') return '未标明'
  const numeric = Number(quantity)
  const text = Number.isFinite(numeric)
    ? numeric.toLocaleString('zh-CN', { maximumFractionDigits: 3 })
    : String(quantity)
  return unitCode ? `${text} ${unitCode}` : text
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
