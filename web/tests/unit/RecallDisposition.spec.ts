import { describe, it, expect, afterEach, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ConsumerTraceView from '@/views/ConsumerTraceView.vue'
import TraceRecallAlert from '@/components/TraceRecallAlert.vue'
import * as traceApi from '@/api/trace'
import type { PublicTrace } from '@/types/trace'

/**
 * Phase B PB6：消费者模拟召回处置进展。只展示受控状态、固定教学演练文案与完成日期，不暗示真实法定召回或监管结论。
 */

afterEach(() => vi.restoreAllMocks())

describe('consumer simulated recall disposition (PB6)', () => {
  const sample: PublicTrace = {
    publicTraceId: 'WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A',
    product: { name: '冷冻大黄鱼', category: 'FISH', specification: '500g' },
    batch: { publicBatchNo: 'SRC****001', originType: 'DOMESTIC_CAPTURE', maskedOrigin: '东海****渔场', productionDate: '2026-09-20' },
    lineage: { nodes: [{ nodeKey: 'N1', generation: 0, role: 'TARGET', productName: '冷冻大黄鱼' }], edges: [] },
    timeline: [],
    temperatureSummary: { result: 'INSUFFICIENT_DATA', ruleNote: '公开页面不展示冷链温度测量明细' },
    flowStatus: 'CLOSED',
    riskStatus: 'RECALLED',
    recallNotice: '此批次已结束正常流转，并已进入系统模拟召回演练（本提示为系统教学演练模拟信息）',
    recallDisposition: { status: 'CLOSED', label: '模拟召回处置已完成：涉及产品已按教学演练流程完成销毁处置（系统教学演练模拟信息，非真实召回结论）。', closedDate: '2026-09-26' },
    queriedAt: '2026-09-26T08:00:00Z',
    disclosure: '教学演练'
  } as unknown as PublicTrace

  it('shows the controlled disposition inside the simulated recall banner', async () => {
    vi.spyOn(traceApi, 'fetchPublicTrace').mockResolvedValue(sample)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/trace/:publicTraceId', component: ConsumerTraceView, props: true }] })
    await router.push('/trace/WVKJ5Y2C4P4Q6T7X8Z2M9K3B1A')
    const view = mount(ConsumerTraceView, { global: { plugins: [router] } })
    await flushPromises()
    const disposition = view.get('[data-testid="public-recall-disposition"]')
    expect(disposition.attributes('data-status')).toBe('CLOSED')
    expect(disposition.text()).toContain('处置已完成')
    expect(disposition.text()).toContain('非真实召回结论')
    expect(disposition.text()).toContain('2026-09-26')
    expect(view.text()).not.toMatch(/法定召回已完成|监管部门|官方认证/)
  })

  it('renders an in-progress disposition without a date', () => {
    const view = mount(TraceRecallAlert, { props: { notice: 'x', disposition: { status: 'IN_PROGRESS', label: '模拟召回处置进行中', closedDate: null } } })
    expect(view.get('[data-testid="public-recall-disposition"]').text()).toContain('处置进行中')
    expect(view.get('[data-testid="public-recall-disposition"]').text()).not.toContain('（')
  })
})
