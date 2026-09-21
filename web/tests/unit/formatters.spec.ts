import { describe, it, expect } from 'vitest'
import {
  formatIsoDateTime,
  formatDate,
  formatProductCategory,
  formatOriginType,
  formatPublicTraceStatus,
  formatFlowStatus,
  formatRiskStatus,
  formatBatchType,
  formatOrgType,
  formatQuantity,
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

  it('derives the consumer status conclusion with risk taking precedence over flow', () => {
    expect(formatPublicTraceStatus('ACTIVE', 'NORMAL')).toMatchObject({ tone: 'success', label: '当前记录正常' })
    expect(formatPublicTraceStatus('ACTIVE', 'FROZEN')).toMatchObject({ tone: 'warning', label: '业务冻结状态' })
    expect(formatPublicTraceStatus('ACTIVE', 'RECALLED')).toMatchObject({ tone: 'danger', label: '模拟召回提示' })
    expect(formatPublicTraceStatus('CLOSED', 'NORMAL')).toMatchObject({ tone: 'neutral', label: '流转已关闭' })
    expect(formatPublicTraceStatus('CLOSED', 'RECALLED')).toMatchObject({ tone: 'danger', label: '模拟召回提示' })
  })

  it('maps flowStatus and riskStatus independently', () => {
    expect(formatFlowStatus('DRAFT').label).toBe('草稿')
    expect(formatFlowStatus('ACTIVE')).toMatchObject({ label: '可流转', tone: 'success' })
    expect(formatFlowStatus('CLOSED').label).toBe('已关闭')
    expect(formatRiskStatus('NORMAL')).toMatchObject({ label: '正常', tone: 'success' })
    expect(formatRiskStatus('FROZEN')).toMatchObject({ label: '冻结', tone: 'warning' })
    expect(formatRiskStatus('RECALLED')).toMatchObject({ label: '模拟召回', tone: 'danger' })
    expect(formatRiskStatus('QUARANTINED').label).toBe('QUARANTINED')
  })

  it('formats batch type, org type and declared quantity', () => {
    expect(formatBatchType('SOURCE')).toBe('来源批次')
    expect(formatOrgType('PROCESSOR')).toBe('加工企业')
    expect(formatQuantity(1000, 'kg')).toBe('1,000 kg')
    expect(formatQuantity(12.5, 'kg')).toBe('12.5 kg')
    expect(formatQuantity(null, 'kg')).toBe('未标明')
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
