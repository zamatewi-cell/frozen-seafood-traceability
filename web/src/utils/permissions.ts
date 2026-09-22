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

/** 当前责任组织的操作员可以对 ACTIVE + NORMAL 批次发起交接。 */
export function canInitiateTransfer(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(canShip(user) && batch
    && batch.orgId === user?.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL')
}

/**
 * 加工企业（PROCESSOR）的操作员可以对本组织负责、ACTIVE + NORMAL 且仍有剩余量的批次执行加工 / 拆分；
 * 是否存在未结束交接由页面结合交接列表判断，服务端仍独立校验全部前提。
 */
export function canOperateBatch(user: CurrentUser | null | undefined, batch: Batch | null | undefined): boolean {
  return Boolean(isOperator(user) && user.orgType === 'PROCESSOR' && batch
    && batch.orgId === user.orgId
    && batch.flowStatus === 'ACTIVE'
    && batch.riskStatus === 'NORMAL'
    && !batch.consumedByOperationId
    && (batch.remainingQuantity === undefined || Number(batch.remainingQuantity) > 0))
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
