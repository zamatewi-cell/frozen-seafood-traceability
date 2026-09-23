<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { createSale } from '@/api/sales'
import { ApiError } from '@/api/client'
import { listSites } from '@/api/directory'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { Batch, Sale, SiteSummary } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatQuantity } from '@/utils/formatters'

/**
 * 终端销售（统一业务契约 v1.1 §9.1）：零售企业在本组织启用的 STORE 门店面向消费者登记数量出库。
 * 门店选项来自本组织场所目录；剩余量只展示服务端派生值，提交后以服务端最新批次为准。
 * 第一次销售后批次不能再交接或加工 / 拆分，但仍可继续部分销售直至售罄关闭；服务端是最终权限边界。
 */
const props = defineProps<{
  batch: Batch
  orgId: number
}>()

const emit = defineEmits<{
  recorded: [sale: Sale]
  conflict: []
}>()

type SiteState = 'idle' | 'loading' | 'loaded' | 'error'
const siteState = ref<SiteState>('idle')
const stores = ref<SiteSummary[]>([])
const open = ref(false)
const form = reactive({ siteId: '', quantity: '', occurredAt: '' })
const fieldErrors = reactive<{ siteId: string; quantity: string; occurredAt: string }>({ siteId: '', quantity: '', occurredAt: '' })
const submitting = ref(false)
const submitError = ref('')
const writer = useIdempotentWrite()
let controller: AbortController | null = null

const remaining = computed(() => Number(props.batch.remainingQuantity ?? 0))
const selectedStore = computed(() => stores.value.find((s) => String(s.id) === form.siteId) ?? null)
const maxOccurredAt = ref(toLocalInput(new Date()))

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function toLocalInput(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

async function loadStores() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  siteState.value = 'loading'
  try {
    const sites = await listSites(props.orgId, current.signal)
    if (current.signal.aborted) return
    stores.value = sites.filter((s) => s.siteType === 'STORE' && s.status === 'ACTIVE' && s.orgId === props.orgId)
    siteState.value = 'loaded'
  } catch {
    if (current.signal.aborted) return
    siteState.value = 'error'
  }
}

async function start() {
  open.value = true
  submitError.value = ''
  fieldErrors.siteId = ''
  fieldErrors.quantity = ''
  fieldErrors.occurredAt = ''
  form.quantity = ''
  maxOccurredAt.value = toLocalInput(new Date())
  form.occurredAt = maxOccurredAt.value
  if (siteState.value !== 'loaded') await loadStores()
  if (!form.siteId && stores.value.length === 1) form.siteId = String(stores.value[0].id)
}

function close() {
  open.value = false
  submitError.value = ''
}

function sellAll() {
  form.quantity = String(remaining.value)
}

function validate(): { siteId: number; quantity: number; occurredAt: string } | null {
  fieldErrors.siteId = ''
  fieldErrors.quantity = ''
  fieldErrors.occurredAt = ''
  if (!form.siteId || !selectedStore.value) fieldErrors.siteId = '请选择本组织门店'
  const raw = String(form.quantity).trim()
  const quantity = Number(raw)
  if (!raw || !Number.isFinite(quantity) || quantity <= 0) fieldErrors.quantity = '销售数量必须大于 0'
  else if (!/^\d+(\.\d{1,3})?$/.test(raw)) fieldErrors.quantity = '销售数量最多 3 位小数'
  else if (quantity > remaining.value) fieldErrors.quantity = `不能超过当前剩余 ${formatQuantity(remaining.value, props.batch.unitCode)}`
  const occurred = form.occurredAt ? new Date(form.occurredAt) : null
  if (!occurred || Number.isNaN(occurred.getTime())) fieldErrors.occurredAt = '请填写销售时间'
  else if (occurred.getTime() > Date.now() + 60_000) fieldErrors.occurredAt = '销售时间不能晚于当前时间'
  if (fieldErrors.siteId || fieldErrors.quantity || fieldErrors.occurredAt || !occurred) return null
  return { siteId: Number(form.siteId), quantity, occurredAt: occurred.toISOString() }
}

async function submit() {
  if (submitting.value) return
  submitError.value = ''
  const values = validate()
  if (!values) return
  submitting.value = true
  try {
    const sale = await writer.run(
      `sale:${props.batch.id}:${values.siteId}:${values.quantity}:${values.occurredAt}`,
      () => ({ siteId: values.siteId, quantity: values.quantity, occurredAt: values.occurredAt }),
      (payload, key) => createSale(props.batch.id, payload, key)
    )
    open.value = false
    emit('recorded', sale)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    submitError.value = describeWriteError(err, '终端销售')
    if (err instanceof ApiError && (err.status === 409 || err.status === 422)) emit('conflict')
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="ent-card" aria-labelledby="sale-title" data-testid="sale-panel">
    <h2 id="sale-title" class="ent-card-title">终端销售</h2>
    <p class="section-note">
      在本组织门店向消费者登记数量出库。当前剩余
      <strong data-testid="sale-remaining">{{ formatQuantity(remaining, batch.unitCode) }}</strong>；
      可多次部分销售，售完后批次自动关闭。第一次销售后该批次不能再交接、加工或拆分。
    </p>

    <div v-if="!open" class="ent-actions">
      <button type="button" class="ent-button ent-primary" data-testid="sale-start" @click="start">登记销售</button>
    </div>

    <template v-else>
      <div v-if="siteState === 'loading'" class="ent-state" data-testid="sale-stores-loading">正在加载本组织门店…</div>
      <div v-else-if="siteState === 'error'" class="ent-state error" role="alert" data-testid="sale-stores-error">
        门店列表加载失败
        <button type="button" class="ent-button" @click="loadStores">重试</button>
        <button type="button" class="ent-button" @click="close">取消</button>
      </div>
      <div v-else-if="stores.length === 0" class="ent-state" role="status" data-testid="sale-no-store">
        本组织暂无启用的门店场所，请联系平台管理员维护场所资料。
        <button type="button" class="ent-button" @click="close">返回</button>
      </div>
      <form v-else novalidate data-testid="sale-form" @submit.prevent="submit">
        <div class="ent-form-grid">
          <label class="ent-field">
            <span>门店 <em>*</em></span>
            <select v-model="form.siteId" data-testid="field-sale-site" :aria-invalid="Boolean(fieldErrors.siteId)">
              <option value="">请选择本组织门店</option>
              <option v-for="store in stores" :key="store.id" :value="String(store.id)">{{ store.name }}（{{ store.siteNo }}）</option>
            </select>
            <small v-if="fieldErrors.siteId" class="field-error" data-testid="error-sale-site">{{ fieldErrors.siteId }}</small>
          </label>
          <label class="ent-field">
            <span>销售数量（{{ batch.unitCode }}） <em>*</em></span>
            <input v-model="form.quantity" type="number" min="0" step="0.001" :max="remaining" inputmode="decimal" data-testid="field-sale-quantity" :aria-invalid="Boolean(fieldErrors.quantity)" />
            <small v-if="fieldErrors.quantity" class="field-error" data-testid="error-sale-quantity">{{ fieldErrors.quantity }}</small>
            <button type="button" class="ent-button link-button" data-testid="sale-all" @click="sellAll">全部剩余</button>
          </label>
          <label class="ent-field">
            <span>销售时间 <em>*</em></span>
            <input v-model="form.occurredAt" type="datetime-local" :max="maxOccurredAt" data-testid="field-sale-occurred-at" :aria-invalid="Boolean(fieldErrors.occurredAt)" />
            <small v-if="fieldErrors.occurredAt" class="field-error" data-testid="error-sale-occurred-at">{{ fieldErrors.occurredAt }}</small>
          </label>
        </div>
        <p v-if="submitError" class="ent-flash error" role="alert" data-testid="sale-error">{{ submitError }}</p>
        <div class="ent-actions">
          <button type="submit" class="ent-button ent-primary" :disabled="submitting" data-testid="sale-submit">
            {{ submitting ? '提交中…' : '确认销售' }}
          </button>
          <button type="button" class="ent-button" :disabled="submitting" data-testid="sale-cancel" @click="close">取消</button>
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
.link-button {
  align-self: flex-start;
  margin-top: 4px;
  padding: 2px 8px;
  font-size: 12px;
}
</style>
