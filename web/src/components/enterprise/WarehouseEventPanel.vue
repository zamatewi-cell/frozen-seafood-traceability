<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { createWarehouseEvent } from '@/api/batches'
import { ApiError } from '@/api/client'
import { listSites } from '@/api/directory'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { Batch, SiteSummary, TraceEvent, WarehouseEventType } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatQuantity } from '@/utils/formatters'

/**
 * 自有冷库入库 / 出库（统一业务契约 v1.1 §8）：当前责任组织在本组织启用的 COLD_STORE 场所记录受控人工事件。
 * 冷库选项来自本组织场所目录；页面不强制 IN / OUT 配对或顺序（契约未定义仓储状态机），出库时仅预选最近一次入库场所。
 * 仓储不改变批次数量、责任组织与状态；服务端是最终权限边界。
 */
const props = defineProps<{
  batch: Batch
  orgId: number
  events: TraceEvent[]
}>()

const emit = defineEmits<{
  recorded: [event: TraceEvent]
  conflict: []
}>()

const LABELS: Record<WarehouseEventType, string> = { WAREHOUSE_IN: '冷库入库', WAREHOUSE_OUT: '冷库出库' }

type SiteState = 'idle' | 'loading' | 'loaded' | 'error'
const siteState = ref<SiteState>('idle')
const coldStores = ref<SiteSummary[]>([])
const mode = ref<WarehouseEventType | null>(null)
const form = reactive({ siteId: '', occurredAt: '', summary: '' })
const summaryTouched = ref(false)
const fieldErrors = reactive<{ siteId: string; occurredAt: string; summary: string }>({ siteId: '', occurredAt: '', summary: '' })
const submitting = ref(false)
const submitError = ref('')
const writer = useIdempotentWrite()
let controller: AbortController | null = null

const selectedSite = computed(() => coldStores.value.find((s) => String(s.id) === form.siteId) ?? null)
const maxOccurredAt = ref(toLocalInput(new Date()))

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function toLocalInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function defaultSummary(type: WarehouseEventType, site: SiteSummary | null): string {
  return site ? `${LABELS[type]}：${site.name}` : LABELS[type]
}

/** 出库默认预选最近一次有效入库的冷库（仅便利，非业务规则）。 */
function lastInboundSiteId(): string {
  const inbound = props.events
    .filter((e) => e.eventType === 'WAREHOUSE_IN' && e.status === 'SUBMITTED' && e.siteId)
    .slice(-1)[0]
  return inbound && coldStores.value.some((s) => s.id === inbound.siteId) ? String(inbound.siteId) : ''
}

async function loadColdStores() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  siteState.value = 'loading'
  try {
    const sites = await listSites(props.orgId, current.signal)
    if (current.signal.aborted) return
    coldStores.value = sites.filter((s) => s.siteType === 'COLD_STORE' && s.status === 'ACTIVE' && s.orgId === props.orgId)
    siteState.value = 'loaded'
  } catch {
    if (current.signal.aborted) return
    siteState.value = 'error'
  }
}

async function open(type: WarehouseEventType) {
  mode.value = type
  submitError.value = ''
  fieldErrors.siteId = ''
  fieldErrors.occurredAt = ''
  fieldErrors.summary = ''
  summaryTouched.value = false
  maxOccurredAt.value = toLocalInput(new Date())
  form.occurredAt = maxOccurredAt.value
  if (siteState.value !== 'loaded') await loadColdStores()
  form.siteId = type === 'WAREHOUSE_OUT' ? lastInboundSiteId() : ''
  if (!form.siteId && coldStores.value.length === 1) form.siteId = String(coldStores.value[0].id)
  form.summary = defaultSummary(type, selectedSite.value)
}

function close() {
  mode.value = null
  submitError.value = ''
}

function onSiteChange() {
  if (mode.value && !summaryTouched.value) form.summary = defaultSummary(mode.value, selectedSite.value)
}

function validate(): { siteId: number; occurredAt: string; summary: string } | null {
  fieldErrors.siteId = ''
  fieldErrors.occurredAt = ''
  fieldErrors.summary = ''
  const siteId = Number(form.siteId)
  if (!form.siteId || !selectedSite.value) fieldErrors.siteId = '请选择本组织冷库'
  const occurred = form.occurredAt ? new Date(form.occurredAt) : null
  if (!occurred || Number.isNaN(occurred.getTime())) fieldErrors.occurredAt = '请填写发生时间'
  else if (occurred.getTime() > Date.now() + 60_000) fieldErrors.occurredAt = '发生时间不能晚于当前时间'
  const summary = form.summary.trim()
  if (!summary) fieldErrors.summary = '请填写说明'
  else if (summary.length > 500) fieldErrors.summary = '说明不能超过 500 个字符'
  if (fieldErrors.siteId || fieldErrors.occurredAt || fieldErrors.summary || !occurred) return null
  return { siteId, occurredAt: occurred.toISOString(), summary }
}

async function submit() {
  const type = mode.value
  if (!type || submitting.value) return
  submitError.value = ''
  const values = validate()
  if (!values) return
  submitting.value = true
  try {
    const recorded = await writer.run(
      `warehouse:${props.batch.id}:${type}:${values.siteId}:${values.occurredAt}:${values.summary}`,
      () => ({ eventType: type, siteId: values.siteId, occurredAt: values.occurredAt, dataSource: 'MANUAL' as const, summary: values.summary }),
      (payload, key) => createWarehouseEvent(props.batch.id, payload, key)
    )
    mode.value = null
    emit('recorded', recorded)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    submitError.value = describeWriteError(err, LABELS[type])
    if (err instanceof ApiError && err.status === 409) emit('conflict')
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="ent-card" aria-labelledby="warehouse-title" data-testid="warehouse-panel">
    <h2 id="warehouse-title" class="ent-card-title">自有冷库仓储</h2>
    <p class="section-note">
      在本组织自有冷库记录入库 / 出库事实。仓储不改变批次数量（{{ formatQuantity(batch.quantity, batch.unitCode) }}）、
      当前责任组织与状态；温度记录不在此登记。
    </p>

    <div v-if="!mode" class="ent-actions">
      <button type="button" class="ent-button ent-primary" data-testid="warehouse-in" @click="open('WAREHOUSE_IN')">冷库入库</button>
      <button type="button" class="ent-button" data-testid="warehouse-out" @click="open('WAREHOUSE_OUT')">冷库出库</button>
    </div>

    <template v-else>
      <div v-if="siteState === 'loading'" class="ent-state" data-testid="warehouse-sites-loading">正在加载本组织冷库…</div>
      <div v-else-if="siteState === 'error'" class="ent-state error" role="alert" data-testid="warehouse-sites-error">
        冷库列表加载失败
        <button type="button" class="ent-button" @click="loadColdStores">重试</button>
        <button type="button" class="ent-button" @click="close">取消</button>
      </div>
      <div v-else-if="coldStores.length === 0" class="ent-state" role="status" data-testid="warehouse-no-cold-store">
        本组织暂无启用的冷库场所，请联系平台管理员维护场所资料。
        <button type="button" class="ent-button" @click="close">返回</button>
      </div>
      <form v-else novalidate :data-mode="mode" data-testid="warehouse-form" @submit.prevent="submit">
        <h3 class="form-title" data-testid="warehouse-form-title">{{ LABELS[mode] }}</h3>
        <div class="ent-form-grid">
          <label class="ent-field">
            <span>冷库 <em>*</em></span>
            <select v-model="form.siteId" data-testid="field-warehouse-site" :aria-invalid="Boolean(fieldErrors.siteId)" @change="onSiteChange">
              <option value="">请选择本组织冷库</option>
              <option v-for="site in coldStores" :key="site.id" :value="String(site.id)">{{ site.name }}（{{ site.siteNo }}）</option>
            </select>
            <small v-if="fieldErrors.siteId" class="field-error" data-testid="error-warehouse-site">{{ fieldErrors.siteId }}</small>
          </label>
          <label class="ent-field">
            <span>发生时间 <em>*</em></span>
            <input v-model="form.occurredAt" type="datetime-local" :max="maxOccurredAt" data-testid="field-warehouse-occurred-at" :aria-invalid="Boolean(fieldErrors.occurredAt)" />
            <small v-if="fieldErrors.occurredAt" class="field-error" data-testid="error-warehouse-occurred-at">{{ fieldErrors.occurredAt }}</small>
          </label>
          <label class="ent-field wide">
            <span>说明 <em>*</em></span>
            <input v-model="form.summary" type="text" maxlength="500" data-testid="field-warehouse-summary" :aria-invalid="Boolean(fieldErrors.summary)" @input="summaryTouched = true" />
            <small v-if="fieldErrors.summary" class="field-error" data-testid="error-warehouse-summary">{{ fieldErrors.summary }}</small>
          </label>
        </div>
        <p v-if="submitError" class="ent-flash error" role="alert" data-testid="warehouse-error">{{ submitError }}</p>
        <div class="ent-actions">
          <button type="submit" class="ent-button ent-primary" :disabled="submitting" data-testid="warehouse-submit">
            {{ submitting ? '提交中…' : `确认${LABELS[mode]}` }}
          </button>
          <button type="button" class="ent-button" :disabled="submitting" data-testid="warehouse-cancel" @click="close">取消</button>
        </div>
      </form>
    </template>
  </section>
</template>

<style scoped>
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.form-title {
  margin: 0 0 12px;
  font-size: 15px;
}
</style>
