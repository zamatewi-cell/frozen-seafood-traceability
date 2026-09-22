import type { CurrentUser } from '@/types/enterprise'

/**
 * 前端入口可见性判断，只用于隐藏不可执行的按钮；服务端仍独立校验全部权限。
 */

/** 只有来源组织（orgType=SOURCE）的企业操作员（OPERATOR）可以建立并激活来源批次。 */
export function canManageSourceBatches(user: CurrentUser | null | undefined): boolean {
  return Boolean(user && user.orgType === 'SOURCE' && user.roles.includes('OPERATOR'))
}
