import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ConsumerTraceView from '@/views/ConsumerTraceView.vue'
import * as traceApi from '@/api/trace'
import { NotFoundError, ApiError } from '@/api/client'
import type { PublicTrace } from '@/types/trace'

const sampleTrace: PublicTrace = {
  publicTraceId: 'WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A',
  product: {
    name: '野生东海大黄鱼',
    category: 'FISH',
    specification: '500g-600g/条'
  },
  batch: {
    publicBatchNo: 'BAT****001',
    originType: 'DOMESTIC_CAPTURE',
    maskedOrigin: '东海近海舟山渔场',
    productionDate: '2026-09-01'
  },
  lineage: {
    nodes: [
      { nodeKey: 'N1', generation: 0, role: 'ORIGIN', productName: '东海大黄鱼原料' },
      { nodeKey: 'N2', generation: 1, role: 'INTERMEDIATE', productName: '野生东海大黄鱼' },
      { nodeKey: 'N3', generation: 2, role: 'TARGET', productName: '野生东海大黄鱼' }
    ],
    edges: [
      { fromNodeKey: 'N1', toNodeKey: 'N2', operationType: 'PROCESS', occurredAt: '2026-09-01T09:00:00Z' },
      { fromNodeKey: 'N2', toNodeKey: 'N3', operationType: 'SPLIT', occurredAt: '2026-09-01T10:00:00Z' }
    ]
  },
  timeline: [
    {
      eventType: 'SOURCE',
      event: '原料采收/出塘',
      occurredAt: '2026-09-01T08:00:00Z',
      dataSourceLabel: '教学演练与仿真模拟数据（SIMULATED）',
      nodeKey: 'N1'
    },
    {
      eventType: 'PROCESS',
      event: '粗加工/精加工',
      occurredAt: '2026-09-01T09:00:00Z',
      dataSourceLabel: '企业人工填报',
      nodeKey: 'N2'
    },
    {
      eventType: 'WAREHOUSE_IN',
      event: '冷库入库',
      occurredAt: '2026-09-01T12:00:00Z',
      dataSourceLabel: '企业系统导入',
      nodeKey: 'N3'
    }
  ],
  temperatureSummary: {
    result: 'INSUFFICIENT_DATA',
    ruleNote: '公开页面不展示冷链温度测量明细；本项目未接入实时温控采集，不构成本项目温控合规依据。'
  },
  flowStatus: 'ACTIVE',
  riskStatus: 'NORMAL',
  queriedAt: '2026-09-10T08:00:00Z',
  disclosure: '本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。'
}

describe('ConsumerTraceView Component States', () => {
  let router: ReturnType<typeof createRouter>

  beforeEach(async () => {
    vi.restoreAllMocks()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/trace', component: ConsumerTraceView },
        { path: '/trace/:publicTraceId', component: ConsumerTraceView }
      ]
    })
    await router.push('/trace')
    await router.isReady()
  })

  it('renders initial welcome state when no traceId is provided', async () => {
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })

    expect(wrapper.find('.initial-guidance-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('欢迎使用冷冻海产品溯源服务')
    expect(wrapper.find('input[id="trace-code-input"]').exists()).toBe(true)
  })

  it('renders skeleton loading while fetch is in progress', async () => {
    let resolvePromise: (data: PublicTrace) => void
    const pendingPromise = new Promise<PublicTrace>((resolve) => {
      resolvePromise = resolve
    })
    vi.spyOn(traceApi, 'fetchPublicTrace').mockReturnValue(pendingPromise)

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })

    expect(wrapper.find('.skeleton-container').exists()).toBe(true)
    resolvePromise!(sampleTrace)
    await flushPromises()
    expect(wrapper.find('.skeleton-container').exists()).toBe(false)
  })

  it('renders success state with ACTIVE badge and allowlisted fields', async () => {
    const fetchSpy = vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue(sampleTrace)

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('野生东海大黄鱼')
    expect(wrapper.text()).toContain('500g-600g/条')
    expect(wrapper.text()).toContain('BAT****001')
    expect(wrapper.text()).toContain('当前记录正常')
    expect(wrapper.text()).toContain('原料采收/出塘')
    expect(wrapper.text()).toContain('查询时间')
    expect(wrapper.text()).toContain(sampleTrace.disclosure)
    expect(wrapper.find('.recall-alert-card').exists()).toBe(false)
    expect(fetchSpy).toHaveBeenCalledTimes(1)
  })

  it('renders FROZEN badge when riskStatus is FROZEN while flowStatus stays ACTIVE', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      riskStatus: 'FROZEN'
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('模拟风险冻结')
    expect(wrapper.find('[data-testid="public-status-note"]').text()).toContain('本教学实训系统中')
    expect(wrapper.find('[data-testid="public-status-note"]').text()).toContain('不代表真实的产品安全判定、监管措施或产品扣留')
    expect(wrapper.text()).not.toContain('业务冻结状态')
    expect(wrapper.text()).not.toContain('质量管理部门已暂停')
    expect(wrapper.find('[data-testid="public-flow-status"]').text()).toBe('可流转')
    expect(wrapper.find('[data-testid="public-risk-status"]').text()).toBe('模拟冻结')
    expect(wrapper.find('.recall-alert-card').exists()).toBe(false)
  })

  it('renders CLOSED badge when flowStatus is CLOSED and riskStatus is NORMAL', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      flowStatus: 'CLOSED'
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('流转已关闭')
  })

  it('renders prominent simulated drill alert banner when a CLOSED batch is RECALLED', async () => {
    const customNotice = '此批次海产品已启动系统模拟召回演练，流通环节已暂停'
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      flowStatus: 'CLOSED',
      riskStatus: 'RECALLED',
      recallNotice: customNotice
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    const alert = wrapper.find('.recall-alert-card')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('系统模拟召回演练声明')
    expect(alert.text()).toContain(customNotice)
    expect(alert.text()).toContain('教学演练推演')
    expect(wrapper.find('.consumer-hero-card .status-badge').text()).toContain('模拟召回演练')
    expect(wrapper.find('[data-testid="public-status-note"]').text()).toContain('此批次当前处于系统模拟召回状态')
    expect(wrapper.find('[data-testid="public-status-note"]').text()).toContain('本提示仅用于教学实训，不代表真实产品召回、安全鉴定或监管结论')
    expect(wrapper.text()).not.toContain('请勿继续食用或销售')
    expect(wrapper.find('[data-testid="public-flow-status"]').text()).toBe('已关闭')
    expect(wrapper.find('[data-testid="public-risk-status"]').text()).toBe('模拟召回')
  })

  it('renders empty timeline notice when timeline is empty array', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      timeline: []
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.find('.empty-timeline').exists()).toBe(true)
    expect(wrapper.text()).toContain('该批次当前尚未记录生效流转事件')
  })

  it('renders identical neutral not-found card for NotFoundError', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockRejectedValue(
      new NotFoundError('PUBLIC_TRACE_NOT_FOUND', '未找到该追溯码或该码已失效')
    )

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.find('.neutral-not-found-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('未找到追溯信息')
    expect(wrapper.text()).toContain('未找到该追溯码或该码已失效')
  })

  it('renders safe error panel with requestId on ApiError', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockRejectedValue(
      new ApiError('java.sql.SQLException: private-host:3306', 503, 'SERVICE_UNAVAILABLE', 'req-safe-888')
    )

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    const errorCard = wrapper.find('.error-panel-card')
    expect(errorCard.exists()).toBe(true)
    expect(errorCard.text()).toContain('查询服务暂时不可用，请稍后重试')
    expect(errorCard.text()).not.toContain('private-host')
    expect(errorCard.text()).toContain('req-safe-888')
  })

  it('renders a safe network failure message', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockRejectedValue(
      new ApiError('Failed to fetch internal-gateway.local', 0, 'NETWORK_ERROR')
    )

    await router.push('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('网络连接失败，请稍后重试')
    expect(wrapper.text()).not.toContain('internal-gateway.local')
  })

  it('renders allowlisted fields only when the response contains forbidden sentinels', async () => {
    const responseWithSentinels = {
      ...sampleTrace,
      internalId: 'FORBIDDEN-INTERNAL-ID',
      orgId: 'FORBIDDEN-ORG-ID',
      product: {
        ...sampleTrace.product,
        costPrice: 'FORBIDDEN-COST-PRICE'
      },
      batch: {
        ...sampleTrace.batch,
        rawBatchNo: 'FORBIDDEN-RAW-BATCH'
      }
    } as PublicTrace
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue(responseWithSentinels)

    await router.push('/trace/WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('FORBIDDEN-')
  })
  it('renders upstream lineage B0 → B1 → B2 with transformation edges and node tags on timeline items', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue(sampleTrace)

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, { global: { plugins: [router] } })
    await flushPromises()

    const nodes = wrapper.findAll('[data-testid="lineage-node"]')
    expect(nodes.map((n) => n.attributes('data-role'))).toEqual(['ORIGIN', 'INTERMEDIATE', 'TARGET'])
    expect(nodes.map((n) => n.find('.lineage-node-label').text())).toEqual(['来源批次', '加工批次', '本批次'])
    expect(nodes[0].text()).toContain('东海大黄鱼原料')
    const edges = wrapper.findAll('[data-testid="lineage-edge"]')
    expect(edges.map((e) => e.attributes('data-operation-type'))).toEqual(['PROCESS', 'SPLIT'])
    expect(edges[1].text()).toContain('拆分')

    const items = wrapper.findAll('[data-testid="public-timeline-item"]')
    expect(items.map((i) => i.attributes('data-event-type'))).toEqual(['SOURCE', 'PROCESS', 'WAREHOUSE_IN'])
    expect(items.map((i) => i.find('[data-testid="public-timeline-node"]').text())).toEqual(['来源批次', '加工批次', '本批次'])
    // 谱系键只作为局部关联键，不作为可见的内部标识
    expect(wrapper.text()).not.toContain('N1')
  })

  it('shows CLOSED and RECALLED together with the simulated recall notice, keeping temperature INSUFFICIENT_DATA and the disclaimer', async () => {
    const notice = '此批次海产品已结束正常流转（已售罄或处置完毕），并已进入系统模拟召回演练（本提示为系统教学演练模拟信息）。'
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      flowStatus: 'CLOSED',
      riskStatus: 'RECALLED',
      recallNotice: notice
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.recall-alert-card').text()).toContain(notice)
    expect(wrapper.find('.recall-alert-card').text()).toContain('不替代企业真实法定公告')
    expect(wrapper.find('[data-testid="public-status-badge"]').text()).toContain('模拟召回演练')
    expect(wrapper.find('[data-testid="public-status-note"]').text()).toContain('本提示仅用于教学实训，不代表真实产品召回、安全鉴定或监管结论')
    expect(wrapper.text()).not.toContain('请勿')
    expect(wrapper.find('[data-testid="public-flow-status"]').text()).toBe('已关闭')
    expect(wrapper.find('[data-testid="public-risk-status"]').text()).toBe('模拟召回')
    expect(wrapper.find('.temp-summary-card').text()).toContain('暂无实时时序采集')
    expect(wrapper.text()).toContain(sampleTrace.disclosure)
    expect(wrapper.findAll('[data-testid="lineage-node"]')).toHaveLength(3)
  })

  it('labels the code as a public trace code and never as a certificate, and never claims full cold-chain compliance', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({ ...sampleTrace, flowStatus: 'CLOSED' })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="public-trace-id"]').text()).toBe(sampleTrace.publicTraceId)
    expect(wrapper.text()).toContain('公开追溯码')
    for (const forbidden of ['证书', '全程温控正常', '正品', '官方认证', '防伪认证']) {
      expect(wrapper.text()).not.toContain(forbidden)
    }
  })

  it('still renders the page when an older response carries no lineage', async () => {
    const legacy = { ...sampleTrace } as Partial<PublicTrace>
    delete legacy.lineage
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue(legacy as PublicTrace)

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="trace-lineage"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="public-timeline-item"]')).toHaveLength(3)
    expect(wrapper.find('[data-testid="public-timeline-node"]').exists()).toBe(false)
  })
})
