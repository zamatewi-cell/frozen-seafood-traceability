import type { PublicTrace } from '../../src/types/trace'

export const mockSuccessTrace: PublicTrace = {
  publicTraceId: 'WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC',
  product: {
    name: '舟山野生大黄鱼',
    category: 'FISH',
    specification: '500g-600g/条'
  },
  batch: {
    publicBatchNo: 'BAT****001',
    originType: 'DOMESTIC_CAPTURE',
    maskedOrigin: '东海近海舟山渔场',
    productionDate: '2026-09-01'
  },
  timeline: [
    {
      event: '原料采收/出塘',
      occurredAt: '2026-09-01T08:00:00Z',
      dataSourceLabel: '教学演练与仿真模拟数据（SIMULATED）'
    },
    {
      event: '冷库入库',
      occurredAt: '2026-09-01T14:30:00Z',
      dataSourceLabel: '企业系统导入'
    }
  ],
  segments: [],
  temperatureSummary: {
    result: 'INSUFFICIENT_DATA',
    ruleNote: '当前切片尚未接入冷链实时温控时序采集流，暂无有效温控监测记录，不构成本项目温控合规依据。'
  },
  batchStatus: 'ACTIVE',
  recallNotice: null,
  queriedAt: '2026-09-10T08:00:00Z',
  disclosure: '本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。'
}

export const mockRecalledTrace: PublicTrace = {
  ...mockSuccessTrace,
  publicTraceId: 'RECALL2C4P4Q6T7XZ2M7K3B2AC',
  batchStatus: 'RECALLED',
  recallNotice: '此批次海产品已启动系统模拟召回演练，流通环节已暂停，请联系销售商或质量管理部门处理（本提示为系统教学演练模拟信息）。'
}

export const mockEmptyTimelineTrace: PublicTrace = {
  ...mockSuccessTrace,
  publicTraceId: 'EMPTY23C4P4Q6T7XZ2M7K3B2AC',
  timeline: []
}
