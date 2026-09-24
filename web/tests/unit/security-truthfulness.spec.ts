import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { mount } from '@vue/test-utils'
import TraceTimeline from '@/components/TraceTimeline.vue'
import TraceTemperatureCard from '@/components/TraceTemperatureCard.vue'
import { formatPublicTraceStatus } from '@/utils/formatters'

describe('Security & Truthfulness Audits', () => {
  const srcDir = path.resolve(__dirname, '../../src')

  it('STRICT SECURITY: never uses v-html anywhere in Vue components', () => {
    function scanDir(dir: string): string[] {
      const files: string[] = []
      const list = fs.readdirSync(dir)
      for (const file of list) {
        const full = path.join(dir, file)
        const stat = fs.statSync(full)
        if (stat.isDirectory()) {
          files.push(...scanDir(full))
        } else if (file.endsWith('.vue')) {
          files.push(full)
        }
      }
      return files
    }

    const vueFiles = scanDir(srcDir)
    expect(vueFiles.length).toBeGreaterThan(5)

    for (const file of vueFiles) {
      const content = fs.readFileSync(file, 'utf8')
      expect(content).not.toContain('v-html')
    }
  })

  it('STRICT PRIVACY: never stores trace data or IDs in localStorage or sessionStorage', () => {
    function scanTsAndVue(dir: string): string[] {
      const files: string[] = []
      const list = fs.readdirSync(dir)
      for (const file of list) {
        const full = path.join(dir, file)
        const stat = fs.statSync(full)
        if (stat.isDirectory()) {
          files.push(...scanTsAndVue(full))
        } else if (file.endsWith('.vue') || file.endsWith('.ts')) {
          files.push(full)
        }
      }
      return files
    }

    const allFiles = scanTsAndVue(srcDir)
    for (const file of allFiles) {
      const content = fs.readFileSync(file, 'utf8')
      expect(content).not.toContain('localStorage')
      expect(content).not.toContain('sessionStorage')
    }
  })

  it('TRUTHFULNESS: SIMULATED data source visibly renders simulation callout', () => {
    const wrapper = mount(TraceTimeline, {
      props: {
        timeline: [
          {
            eventType: 'SOURCE',
            event: '原料采收/出塘',
            occurredAt: '2026-09-01T08:00:00Z',
            dataSourceLabel: '教学演练与仿真模拟数据（SIMULATED）',
            nodeKey: 'N1'
          }
        ]
      }
    })

    expect(wrapper.text()).toContain('教学演练与仿真模拟数据（SIMULATED）')
    expect(wrapper.text()).toContain('仿真推演')
  })

  it('TRUTHFULNESS: DEVICE data source clearly states reserved identifier without claiming real IoT hardware', () => {
    const wrapper = mount(TraceTimeline, {
      props: {
        timeline: [
          {
            eventType: 'TRANSPORT',
            event: '冷链干线运输',
            occurredAt: '2026-09-02T10:00:00Z',
            dataSourceLabel: '标准预留设备标识（DEVICE，未接入真实硬件）',
            nodeKey: 'N1'
          }
        ]
      }
    })

    expect(wrapper.text()).toContain('未接入真实硬件')
    expect(wrapper.text()).toContain('预留标识')
  })

  it('TRUTHFULNESS: INSUFFICIENT_DATA temperature summary does not fabricate compliance or fake chart', () => {
    const wrapper = mount(TraceTemperatureCard, {
      props: {
        temperatureSummary: {
          result: 'INSUFFICIENT_DATA',
          ruleNote: '当前切片尚未接入冷链实时温控时序采集流，暂无有效温控监测记录，不构成本项目温控合规依据。'
        }
      }
    })

    expect(wrapper.text()).toContain('暂无实时时序采集（教学模拟）')
    expect(wrapper.text()).toContain('不构成本项目温控合规依据')
    expect(wrapper.text()).toContain('不伪造温控合规结论')
  })
  it('TRUTHFULNESS: consumer-facing sources never label the public code as a certificate or claim authenticity / full cold-chain compliance', () => {
    const consumerFiles = [
      'views/ConsumerTraceView.vue',
      'layouts/PublicLayout.vue',
      'components/TraceHero.vue',
      'components/TraceLineage.vue',
      'components/TraceTimeline.vue',
      'components/TraceProductCard.vue',
      'components/TraceTemperatureCard.vue',
      'components/TraceRecallAlert.vue',
      'components/TraceDisclosure.vue',
      'components/TraceNotFound.vue',
      'components/TraceSearchForm.vue',
      'components/enterprise/PublicTraceCodePanel.vue'
    ]
    for (const file of consumerFiles) {
      const content = fs.readFileSync(path.join(srcDir, file), 'utf8')
      for (const forbidden of ['证书', '全程温控正常', '正品保证', '官方认证', '防伪认证', '已通过认证']) {
        expect(content, `${file} contains ${forbidden}`).not.toContain(forbidden)
      }
    }
  })
  it('TRUTHFULNESS: the consumer FROZEN conclusion is a training simulation, never a real authority action or product hold (PB1)', () => {
    const formatters = fs.readFileSync(path.join(srcDir, 'utils/formatters.ts'), 'utf8')
    expect(formatters).not.toContain('业务冻结状态')
    expect(formatters).not.toContain('质量管理部门已暂停')
    for (const flow of ['ACTIVE', 'CLOSED']) {
      const info = formatPublicTraceStatus(flow, 'FROZEN')
      expect(`${info.label}${info.description}`).toContain('模拟')
      expect(info.description).toContain('不代表')
    }
    const panel = fs.readFileSync(path.join(srcDir, 'components/enterprise/BatchRiskPanel.vue'), 'utf8')
    expect(panel).toContain('教学实训中的模拟质量处置')
    expect(panel).toContain('不代表真实的产品扣留、安全判定或监管措施')
  })
})
