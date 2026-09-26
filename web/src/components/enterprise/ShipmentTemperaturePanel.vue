<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { listShipmentTemperatures, recordShipmentTemperature } from '@/api/shipmentTemperatures'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { CurrentUser, Shipment, ShipmentTemperatureRecord, TemperatureDataSource } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { toLocalDateTimeInput } from '@/utils/datetime'
import { formatDataSource, formatIsoDateTime, formatTemperature, formatTemperatureEvaluation } from '@/utils/formatters'
import { canRecordShipmentTemperature } from '@/utils/permissions'

/**
 * Shipment 在途温度记录（Phase B PB2；统一业务契约 v1.1 §2.9 / §10.1 / §14）：运输任务指定承运组织的操作员在运输途中
 * 逐条登记温度测量，记录绑定运输任务；服务端按测量时适用的运输温控规则版本给出<b>单点</b>判定并固定判定依据。
 * 单点越界不等于持续超温：本面板不展示也不暗示告警、风险冻结或温控合规结论。发货方与接收方只读。服务端是最终权限边界。
 */
const props = defineProps<{
  shipment: Shipment
  user: CurrentUser | null
}>()

const emit = defineEmits<{
  recorded: [record: ShipmentTemperatureRecord]
  /** 409：运输任务状态已变化（例如已确认到达）或请求冲突；父页面刷新运输任务并以提示条保留原因。 */
  conflict: [message: string]
}>()

const TEMPERATURE_MIN = -80
const TEMPERATURE_MAX = 60
const DEVICE_NO_PATTERN = /^[A-Za-z0-9._:/#-]{1,64}$/
const SOURCES: { value: TemperatureDataSource; label: string }[] = [
  { value: 'MANUAL', label: '人工登记（MANUAL）' },
  { value: 'SIMULATED', label: '教学模拟数据（SIMULATED）' }
]

type LoadState = 'loading' | 'loaded' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const records = ref<ShipmentTemperatureRecord[]>([])
const formOpen = ref(false)
const form = reactive({ measuredAt: '', temperature: '', dataSource: 'MANUAL' as TemperatureDataSource, deviceNo: '' })
const fieldErrors = reactive({ measuredAt: '', temperature: '', deviceNo: '' })
const maxMeasuredAt = ref(toLocalDateTimeInput(new Date()))
const submitting = ref(false)
const submitError = ref('')
const writer = useIdempotentWrite()
let controller: AbortController | null = null

const canRecord = computed(() => canRecordShipmentTemperature(props.user, props.shipment))
const outOfRangeCount = computed(() => records.value.filter((r) => r.evaluation === 'HIGH' || r.evaluation === 'LOW').length)
const latestRule = computed(() => [...records.value].reverse().find((r) => r.rule)?.rule ?? null)

function sortRecords(list: ShipmentTemperatureRecord[]): ShipmentTemperatureRecord[] {
  return [...list].sort((a, b) => {
    const t = new Date(a.measuredAt).getTime() - new Date(b.measuredAt).getTime()
    return t !== 0 ? t : a.id - b.id
  })
}

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await listShipmentTemperatures(props.shipment.id, current.signal)
    if (current.signal.aborted) return
    records.value = sortRecords(result)
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    records.value = []
    loadState.value = err instanceof ApiError && err.status === 403 ? 'forbidden' : 'error'
  }
}

function open() {
  formOpen.value = true
  submitError.value = ''
  fieldErrors.measuredAt = ''
  fieldErrors.temperature = ''
  fieldErrors.deviceNo = ''
  maxMeasuredAt.value = toLocalDateTimeInput(new Date())
  form.measuredAt = maxMeasuredAt.value
  form.temperature = ''
  form.deviceNo = ''
}

function close() {
  formOpen.value = false
  submitError.value = ''
}

function validate(): { measuredAt: string; temperature: number; dataSource: TemperatureDataSource; deviceNo?: string } | null {
  fieldErrors.measuredAt = ''
  fieldErrors.temperature = ''
  fieldErrors.deviceNo = ''
  const measured = form.measuredAt ? new Date(form.measuredAt) : null
  if (!measured || Number.isNaN(measured.getTime())) fieldErrors.measuredAt = '请填写测量时间'
  else if (measured.getTime() > Date.now() + 1000) fieldErrors.measuredAt = '测量时间不能晚于当前时间'
  else if (props.shipment.loadedAt && measured.getTime() < new Date(props.shipment.loadedAt).getTime()) {
    fieldErrors.measuredAt = '测量时间不能早于装载发运时间'
  }
  const text = String(form.temperature).trim()
  const value = Number(text)
  if (!text) fieldErrors.temperature = '请填写温度'
  else if (!/^-?\d{1,3}(\.\d{1,2})?$/.test(text) || !Number.isFinite(value)) fieldErrors.temperature = '温度最多保留两位小数'
  else if (value < TEMPERATURE_MIN || value > TEMPERATURE_MAX) fieldErrors.temperature = `温度必须在 ${TEMPERATURE_MIN} 到 ${TEMPERATURE_MAX} ℃ 之间`
  const deviceNo = form.deviceNo.trim()
  if (deviceNo && !DEVICE_NO_PATTERN.test(deviceNo)) fieldErrors.deviceNo = '设备编号最长 64 个字符，只能包含字母、数字与 . _ : / # -'
  if (fieldErrors.measuredAt || fieldErrors.temperature || fieldErrors.deviceNo || !measured) return null
  return { measuredAt: measured.toISOString(), temperature: value, dataSource: form.dataSource, ...(deviceNo ? { deviceNo } : {}) }
}

async function submit() {
  if (submitting.value) return
  submitError.value = ''
  const values = validate()
  if (!values) return
  submitting.value = true
  try {
    const record = await writer.run(
      `temperature:${props.shipment.id}:${values.measuredAt}:${values.temperature}:${values.dataSource}:${values.deviceNo ?? ''}`,
      () => values,
      (payload, key) => recordShipmentTemperature(props.shipment.id, payload, key)
    )
    records.value = sortRecords([...records.value.filter((r) => r.id !== record.id), record])
    formOpen.value = false
    emit('recorded', record)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '登记在途温度')
    if (err instanceof ApiError && err.status === 409) {
      close()
      emit('conflict', message)
    } else {
      submitError.value = message
    }
  } finally {
    submitting.value = false
  }
}

function ruleText(r: ShipmentTemperatureRecord): string {
  if (!r.rule) return '无适用规则'
  return `${r.rule.name} v${r.rule.versionNo}：${formatTemperature(r.rule.lowerLimit)} ~ ${formatTemperature(r.rule.upperLimit)}`
}

watch(() => [props.shipment.id, props.shipment.status], () => {
  if (!canRecord.value) close()
  load()
}, { immediate: true })

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="ent-card" aria-labelledby="temperature-title" data-testid="temperature-panel">
    <h2 id="temperature-title" class="ent-card-title">在途温度记录</h2>
    <p class="section-note" data-testid="temperature-disclaimer">
      温度记录绑定本运输任务，由承运商在运输途中逐条登记。单点判定只说明这一次测量是否落在测量时适用的运输温控规则范围内（含上下限），
      不等于持续超温；本阶段不产生告警，也不改变批次风险状态。温度记录不对消费者公开。
      “教学模拟数据”为实训演练数据，本系统未接入真实温度设备。
    </p>

    <p v-if="latestRule" class="ent-muted" data-testid="temperature-rule-band">
      适用规则：{{ latestRule.name }} v{{ latestRule.versionNo }}（运输环节 {{ formatTemperature(latestRule.lowerLimit) }} ~
      {{ formatTemperature(latestRule.upperLimit) }}）
    </p>

    <div v-if="canRecord && !formOpen" class="ent-actions">
      <button type="button" class="ent-button ent-primary" data-testid="temperature-open" @click="open">登记温度</button>
    </div>

    <form v-if="formOpen" novalidate data-testid="temperature-form" @submit.prevent="submit">
      <div class="ent-form-grid">
        <label class="ent-field">
          <span>测量时间 <em>*</em></span>
          <input
            v-model="form.measuredAt"
            type="datetime-local"
            step="1"
            :max="maxMeasuredAt"
            :aria-invalid="Boolean(fieldErrors.measuredAt)"
            data-testid="field-temperature-measured-at"
          />
          <small v-if="fieldErrors.measuredAt" class="field-error" data-testid="error-temperature-measured-at">{{ fieldErrors.measuredAt }}</small>
        </label>
        <label class="ent-field">
          <span>温度（℃）<em>*</em></span>
          <input
            v-model="form.temperature"
            type="text"
            inputmode="decimal"
            placeholder="例如 -18.50"
            :aria-invalid="Boolean(fieldErrors.temperature)"
            data-testid="field-temperature-value"
          />
          <small v-if="fieldErrors.temperature" class="field-error" data-testid="error-temperature-value">{{ fieldErrors.temperature }}</small>
        </label>
        <label class="ent-field">
          <span>数据来源 <em>*</em></span>
          <select v-model="form.dataSource" data-testid="field-temperature-source">
            <option v-for="s in SOURCES" :key="s.value" :value="s.value">{{ s.label }}</option>
          </select>
        </label>
        <label class="ent-field">
          <span>设备编号（可选）</span>
          <input
            v-model="form.deviceNo"
            type="text"
            maxlength="64"
            :aria-invalid="Boolean(fieldErrors.deviceNo)"
            data-testid="field-temperature-device"
          />
          <small v-if="fieldErrors.deviceNo" class="field-error" data-testid="error-temperature-device">{{ fieldErrors.deviceNo }}</small>
        </label>
      </div>
      <p v-if="submitError" class="ent-flash error" role="alert" data-testid="temperature-error">{{ submitError }}</p>
      <div class="ent-actions">
        <button type="submit" class="ent-button ent-primary" :disabled="submitting" data-testid="temperature-submit">
          {{ submitting ? '提交中…' : '确认登记' }}
        </button>
        <button type="button" class="ent-button" :disabled="submitting" data-testid="temperature-cancel" @click="close">取消</button>
      </div>
    </form>

    <div v-if="loadState === 'loading'" class="ent-state" data-testid="temperature-loading">正在加载在途温度记录…</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-muted" data-testid="temperature-forbidden">无权查看该运输任务的在途温度记录。</div>
    <div v-else-if="loadState === 'error'" class="ent-state error" data-testid="temperature-load-error">
      在途温度记录加载失败
      <button type="button" class="ent-button" data-testid="temperature-retry" @click="load">重试</button>
    </div>
    <p v-else-if="records.length === 0" class="ent-muted" data-testid="temperature-empty">尚未登记在途温度。</p>
    <template v-else>
      <p class="ent-muted" data-testid="temperature-summary">
        共 {{ records.length }} 条；单点越界 {{ outOfRangeCount }} 条（单点判定，不等于持续超温）。
      </p>
      <div class="ent-table-scroll">
        <table class="ent-table" data-testid="temperature-table">
          <thead>
            <tr>
              <th>测量时间</th>
              <th>温度</th>
              <th>单点判定</th>
              <th>判定依据</th>
              <th>数据来源</th>
              <th>设备编号</th>
              <th>登记时间</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="r in records"
              :key="r.id"
              data-testid="temperature-row"
              :data-record-id="r.id"
              :data-evaluation="r.evaluation"
              :data-source="r.dataSource"
            >
              <td>{{ formatIsoDateTime(r.measuredAt) }}</td>
              <td class="mono" data-testid="temperature-value">{{ formatTemperature(r.temperature) }}</td>
              <td>
                <StatusBadge :info="formatTemperatureEvaluation(r.evaluation)" dimension="单点判定" data-testid="temperature-evaluation" :data-status="r.evaluation" />
              </td>
              <td class="ent-muted">{{ ruleText(r) }}</td>
              <td>{{ formatDataSource(r.dataSource) }}</td>
              <td class="mono">{{ r.deviceNo || '—' }}</td>
              <td class="ent-muted">{{ formatIsoDateTime(r.recordedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
