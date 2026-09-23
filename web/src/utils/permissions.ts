import type { Batch, CurrentUser, Shipment, Transfer } from '@/types/enterprise'

/**
 * 前端入口可见性判断，只用于隐藏不可执行的按钮；服务端仍独立校验全部权限。
 */

function isOperator(user: CurrentUser | null | undefined): user is CurrentUser {
  return Boolean(user && user.roles.includes('OPERATOR') && !user.scopes.includes('PLATFORM'))
}

/** 只有来源组织（orgType=SOURCE）的企业操作员（OPERATOR）可以建立并激活来源批次。 */
export function canManageSourceBatches(user: CurrentUser | null | undefined): boolean {
  return Boolean(user && user.orgType === 'SOURCE' && user.roles.includes('OPERATOR'))
}

/** 承运组织的操作员：负责确认装载发运与到达，不能成为批次责任组织，也不创建交接或运输任务（Demo MVP Phase A）。 */
export function isCarrierOperator(user: CurrentUser | null | undefined): boolean {
  return isOperator(user) && user.orgType === 'CARRIER'
}

/** 非承运组织的操作员可以作为发货方创建交接与运输任务。 */
export function canShip(user: CurrentUser | null | undefined): boolean {
  return isOperator(user) && user.orgType !== 'CARRIER'
}

/** 当前责任组织的操作员可以对 ACTIVE + NORMAL、尚未开始终端销售的批次发起交接。 */
export function canInitiateTransfer(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(canShip(user) && batch
    && batch.orgId === user?.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL'
    && !batch.firstSaleId)
}

/**
 * 加工企业（PROCESSOR）的操作员可以对本组织负责、ACTIVE + NORMAL、未开始终端销售且仍有剩余量的批次执行加工 / 拆分；
 * 是否存在未结束交接由页面结合交接列表判断，服务端仍独立校验全部前提。
 */
export function canOperateBatch(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(isOperator(user) && user.orgType === 'PROCESSOR' && batch
    && batch.orgId === user.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL'
    && !batch.consumedByOperationId
    && !batch.firstSaleId
    && (batch.remainingQuantity === undefined || Number(batch.remainingQuantity) > 0))
}

/**
 * 终端销售：零售企业（RETAILER）的企业操作员（非平台角色）对本组织负责、ACTIVE + NORMAL 且仍有剩余量的批次提交。
 * 首次销售后仍可继续部分销售；服务端仍独立校验门店、剩余量、未结束交接与全部权限。
 */
export function canRecordSale(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(isOperator(user) && user.orgType === 'RETAILER' && batch
    && batch.orgId === user.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL'
    && batch.remainingQuantity !== undefined
    && Number(batch.remainingQuantity) > 0)
}

/**
 * 自有冷库入库 / 出库：批次当前责任组织的企业操作员（非平台角色）对 ACTIVE + NORMAL 批次记录；组织类型不受限。
 * 服务端仍独立校验场所归属、冷库类型与批次状态。
 */
export function canRecordWarehouseEvent(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(isOperator(user) && batch
    && batch.orgId === user.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL')
}

/** 交接接收方的操作员或质量管理员可以接受 / 拒收（运输任务必须已到达，服务端校验）。 */
export function canDecideTransfer(user: CurrentUser | null | undefined, transfer: Transfer | null | undefined): boolean {
  return Boolean(user && transfer
    && transfer.receiverOrgId === user.orgId
    && transfer.status === 'PENDING'
    && !user.scopes.includes('PLATFORM')
    && (user.roles.includes('OPERATOR') || user.roles.includes('QUALITY_MANAGER')))
}

export type ShipmentViewerRole = 'SENDER' | 'CARRIER' | 'RECEIVER' | 'NONE'

export function shipmentRoleOf(user: CurrentUser | null | undefined, shipment: Shipment | null | undefined): ShipmentViewerRole {
  if (!user || !shipment) return 'NONE'
  if (shipment.senderOrg.id === user.orgId) return 'SENDER'
  if (shipment.carrierOrg.id === user.orgId) return 'CARRIER'
  if (shipment.receiverOrg.id === user.orgId) return 'RECEIVER'
  return 'NONE'
}

/**
 * 公开追溯码管理（激活 / 停用）：批次当前责任组织的企业操作员（非平台角色）；组织类型不受限。
 * 历史参与组织在批次转出后不能再修改该批次的公开追溯码（服务端同样校验）。
 */
export function canManagePublicTraceCode(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(isOperator(user) && batch && batch.orgId === user.orgId)
}

/**
 * 首次激活公开追溯码：只对 ACTIVE + NORMAL 批次开放（契约正常流程在零售接受之后、终端销售之前激活）。
 * 已激活的码在批次关闭后继续可查询，不需要也不能在关闭后首次激活。
 */
export function canActivatePublicTraceCode(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(canManagePublicTraceCode(user, batch)
    && batch?.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL')
}
