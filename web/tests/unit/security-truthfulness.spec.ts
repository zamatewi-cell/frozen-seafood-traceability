import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { mount } from '@vue/test-utils'
import TraceTimeline from '@/components/TraceTimeline.vue'
import TraceTemperatureCard from '@/components/TraceTemperatureCard.vue'

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
})
