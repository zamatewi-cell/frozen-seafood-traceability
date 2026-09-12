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
  timeline: [
    {
      event: '原料采收/出塘',
      occurredAt: '2026-09-01T08:00:00Z',
      dataSourceLabel: '教学演练与仿真模拟数据（SIMULATED）'
    },
    {
      event: '冷库入库',
      occurredAt: '2026-09-01T12:00:00Z',
      dataSourceLabel: '企业系统导入'
    }
  ],
  temperatureSummary: {
    result: 'INSUFFICIENT_DATA',
    ruleNote: '当前切片尚未接入冷链实时温控时序采集流，暂无有效温控监测记录，不构成本项目温控合规依据。'
  },
  batchStatus: 'ACTIVE',
  recallNotice: null,
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

  it('renders FROZEN badge when batchStatus is FROZEN', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      batchStatus: 'FROZEN'
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('业务冻结状态')
  })

  it('renders CLOSED badge when batchStatus is CLOSED', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      batchStatus: 'CLOSED'
    })

    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const wrapper = mount(ConsumerTraceView, {
      global: { plugins: [router] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('流转已关闭')
  })

  it('renders prominent simulated drill alert banner when batchStatus is RECALLED', async () => {
    const customNotice = '此批次海产品已启动系统模拟召回演练，流通环节已暂停'
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue({
      ...sampleTrace,
      batchStatus: 'RECALLED',
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
})
