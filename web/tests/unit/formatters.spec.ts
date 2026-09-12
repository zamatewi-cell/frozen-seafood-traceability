import { describe, it, expect } from 'vitest'
import {
  formatIsoDateTime,
  formatDate,
  formatProductCategory,
  formatOriginType,
  formatBatchStatus,
  formatTemperatureResult
} from '@/utils/formatters'

describe('Display Formatters', () => {
  it('formats ISO datetime accurately or handles missing value', () => {
    expect(formatIsoDateTime(null)).toBe('暂无记录')
    expect(formatIsoDateTime('')).toBe('暂无记录')
    const formatted = formatIsoDateTime('2026-09-01T08:30:00Z')
    expect(formatted).toMatch(/^2026-09-01 \d{2}:30:00/)
  })

  it('formats date or defaults to fallback', () => {
    expect(formatDate(null)).toBe('未标明')
    expect(formatDate('2026-09-01')).toBe('2026-09-01')
  })

  it('formats product categories', () => {
    expect(formatProductCategory('FISH')).toBe('海水鱼类')
    expect(formatProductCategory('CRUSTACEAN')).toBe('虾蟹甲壳类')
    expect(formatProductCategory('SHELLFISH')).toBe('双壳贝类')
    expect(formatProductCategory('CEPHALOPOD')).toBe('头足软体类')
    expect(formatProductCategory('OTHER')).toBe('其他海产')
    expect(formatProductCategory('CUSTOM')).toBe('CUSTOM')
  })

  it('formats origin types', () => {
    expect(formatOriginType('DOMESTIC_CAPTURE')).toBe('国内捕捞')
    expect(formatOriginType('DOMESTIC_FARMED')).toBe('国内养殖')
    expect(formatOriginType('IMPORT')).toBe('进口海产')
  })

  it('maps batch statuses to labels and tone classes', () => {
    expect(formatBatchStatus('ACTIVE').tone).toBe('success')
    expect(formatBatchStatus('ACTIVE').label).toBe('当前记录正常')

    expect(formatBatchStatus('FROZEN').tone).toBe('warning')
    expect(formatBatchStatus('FROZEN').label).toBe('业务冻结状态')

    expect(formatBatchStatus('RECALLED').tone).toBe('danger')
    expect(formatBatchStatus('RECALLED').label).toBe('模拟召回提示')

    expect(formatBatchStatus('CLOSED').tone).toBe('neutral')
    expect(formatBatchStatus('CLOSED').label).toBe('流转已关闭')
  })

  it('formats temperature results truthfully', () => {
    expect(formatTemperatureResult('NO_BREACH_RECORDED').tone).toBe('success')
    expect(formatTemperatureResult('NO_BREACH_RECORDED').label).toBe('当前记录中未发现超温事件')
    expect(formatTemperatureResult('NO_BREACH_RECORDED').label).toBe('当前记录中未发现超温事件')
    expect(formatTemperatureResult('BREACH_RECORDED').tone).toBe('danger')
    expect(formatTemperatureResult('INSUFFICIENT_DATA').tone).toBe('warning')
    expect(formatTemperatureResult('INSUFFICIENT_DATA').label).toContain('暂无实时时序采集')
  })
})
