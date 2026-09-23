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
 * RECALLED / FROZEN 属于风险维度，与 ACTIVE / CLOSED 流转维度相互独立（两者另在双维状态行中分别展示）。
 */
export function formatPublicTraceStatus(
  flowStatus: string | null | undefined,
  riskStatus: string | null | undefined
): StatusBadgeInfo {
  if (riskStatus === 'RECALLED') {
    // 教学演练系统：同一条可见信息内明确“模拟”，不给出脱离演练语境的现实处置指令
    return {
      label: '模拟召回演练',
      tone: 'danger',
      description: '此批次当前处于系统模拟召回状态。本提示仅用于教学实训，不代表真实产品召回、安全鉴定或监管结论。'
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
 * 企业间交接（Transfer）状态标签。
 */
export function formatTransferStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'DRAFT':
      return { label: '草稿', tone: 'neutral', description: '尚未提交；提交前必须绑定计划中的运输任务' }
    case 'PENDING':
      return { label: '待接收', tone: 'warning', description: '已提交，等待运输任务到达后由接收方接受或拒收；责任组织仍为发送方' }
    case 'ACCEPTED':
      return { label: '已接受', tone: 'success', description: '接收方已接受，批次当前责任组织已转为接收方' }
    case 'REJECTED':
      return { label: '已拒收', tone: 'danger', description: '接收方已拒收，批次责任组织未改变' }
    default:
      return { label: status || '未知', tone: 'neutral', description: '未识别的交接状态' }
  }
}

/**
 * 冷链运输任务（Shipment）状态标签。
 */
export function formatShipmentStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'PLANNED':
      return { label: '计划中', tone: 'neutral', description: '可增删交接；等待发货方提交交接与承运商确认装载' }
    case 'IN_TRANSIT':
      return { label: '运输中', tone: 'warning', description: '承运商已确认装载发运，装载清单已冻结；责任组织仍为发送方' }
    case 'DELIVERED':
      return { label: '已到达', tone: 'success', description: '承运商已确认物理到达；是否接受由接收方决定' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'neutral', description: '运输任务已在发运前取消' }
    default:
      return { label: status || '未知', tone: 'neutral', description: '未识别的运输任务状态' }
  }
}

/**
 * 批次操作类型字典。
 */
export function formatOperationType(type: string | null | undefined): string {
  if (!type) return '未标明'
  const map: Record<string, string> = {
    PROCESS: '加工',
    SPLIT: '拆分',
    MERGE: '合并',
    REPACK: '分装'
  }
  return map[type] || type
}

/**
 * 批次操作状态标签。
 */
export function formatOperationStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'DRAFT':
      return { label: '草稿', tone: 'neutral', description: '输出批次为草稿，输入批次尚未消耗；提交后原子生效' }
    case 'SUBMITTED':
      return { label: '已提交', tone: 'success', description: '输入批次已全量消耗并关闭，输出批次已激活，谱系已固化' }
    case 'CORRECTED':
      return { label: '已更正', tone: 'neutral', description: '该操作已被更正' }
    default:
      return { label: status || '未知', tone: 'neutral', description: '未识别的操作状态' }
  }
}

/**
 * 批次操作明细角色字典。
 */
export function formatItemRole(role: string | null | undefined): string {
  if (!role) return '未标明'
  const map: Record<string, string> = {
    INPUT: '投入',
    OUTPUT: '产出',
    LOSS: '损耗',
    WASTE: '废弃',
    SAMPLE: '留样'
  }
  return map[role] || role
}

/**
 * 场所类型字典。
 */
export function formatSiteType(siteType: string | null | undefined): string {
  if (!siteType) return '未标明'
  const map: Record<string, string> = {
    PORT: '港口码头',
    FARM: '养殖场',
    FACTORY: '加工厂',
    COLD_STORE: '冷库',
    LOGISTICS_HUB: '物流中心',
    STORE: '门店'
  }
  return map[siteType] || siteType
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

/**
 * 消费者谱系节点标签：最上游为来源批次，扫码批次为本批次，中间批次按产生它的物料转换命名。
 */
export function formatLineageNodeLabel(role: string | null | undefined, derivedBy?: string | null): string {
  if (role === 'TARGET') return '本批次'
  if (role === 'ORIGIN') return '来源批次'
  const map: Record<string, string> = {
    PROCESS: '加工批次',
    SPLIT: '拆分批次',
    MERGE: '合并批次',
    REPACK: '分装批次'
  }
  return (derivedBy && map[derivedBy]) || '中间批次'
}

/**
 * 企业端公开追溯码状态标签。
 */
export function formatPublicTraceCodeStatus(status: string | null | undefined): StatusBadgeInfo {
  switch (status) {
    case 'ACTIVE':
      return { label: '已激活', tone: 'success', description: '消费者可通过公开追溯码查询该批次的公开信息' }
    case 'DISABLED':
      return { label: '已停用', tone: 'neutral', description: '停用为终态：消费者查询与未知码一样显示未找到，不能重新激活或更换' }
    case 'RECALLED':
      return { label: '召回标记', tone: 'danger', description: '召回相关状态（模拟演练），消费者仍可查询' }
    default:
      return { label: status || '未激活', tone: 'neutral', description: '尚未激活公开追溯码' }
  }
}
