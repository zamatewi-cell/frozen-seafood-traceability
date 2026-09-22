<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { getBatch } from '@/api/batches'
import { createBatchOperation, submitBatchOperation } from '@/api/batchOperations'
import { ApiError } from '@/api/client'
import { listActiveProducts } from '@/api/directory'
import { listTransfers } from '@/api/transfers'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { Batch, BatchOperation, BatchOperationItemRequest, Product, SupportedOperationType, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatBatchType, formatFlowStatus, formatOperationType, formatQuantity, formatRiskStatus, formatTransferStatus } from '@/utils/formatters'
import { canOperateBatch } from '@/utils/permissions'
import { setBatchFlash } from './batchFlash'
import { setPageFlash } from './pageFlash'

const props = defineProps<{ id: string; type: SupportedOperationType }>()

const router = useRouter()
const { user } = useSession()
const directory = useDirectoryLabels()
const createWriter = useIdempotentWrite()
const submitWriter = useIdempotentWrite()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const batch = ref<Batch | null>(null)
const products = ref<Product[]>([])
const openTransfer = ref<Transfer | null>(null)

interface OutputRow {
  quantity: string
  productId: string
  externalBatchNo: string
}

const form = reactive({
  outputs: [] as OutputRow[],
  loss: '',
  waste: '',
  sample: '',
  note: ''
})
const formError = ref('')
const submitting = ref(false)
const submitError = ref('')
/** 已创建但提交失败的草稿：展示入口，便于在详情页重试提交或删除 */
const pendingDraft = ref<BatchOperation | null>(null)

const isSplit = computed(() => props.type === 'SPLIT')
const minOutputs = computed(() => (isSplit.value ? 2 : 1))
const allowed = computed(() => canOperateBatch(user.value, batch.value) && !openTransfer.value)
const inputQuantity = computed(() => {
  const b = batch.value
  if (!b) return 0
  return Number(b.remainingQuantity ?? b.quantity)
})

/** 以 0.001 kg 为单位做整数运算，避免浮点误差；无效输入返回 null。 */
function toMilli(value: string | number): number | null {
  const text = String(value).trim()
  if (text === '') return 0
  if (!/^\d+(\.\d{1,3})?$/.test(text)) return null
  return Math.round(Number(text) * 1000)
}

function fromMilli(milli: number): string {
  return (milli / 1000).toFixed(3).replace(/\.?0+$/, '')
}

const balance = computed(() => {
  const input = toMilli(inputQuantity.value)
  const parts = [...form.outputs.map((o) => o.quantity), form.loss, form.waste, form.sample].map(toMilli)
  if (input === null || parts.some((p) => p === null)) return { valid: false, input: input ?? 0, right: 0, diff: 0 }
  const right = (parts as number[]).reduce((sum, p) => sum + p, 0)
  return { valid: true, input, right, diff: input - right }
})
const balanced = computed(() => balance.value.valid && balance.value.diff === 0)

function resetForm() {
  form.outputs = Array.from({ length: minOutputs.value }, () => ({ quantity: '', productId: '', externalBatchNo: '' }))
  form.loss = ''
  form.waste = ''
  form.sample = ''
  form.note = ''
  formError.value = ''
  submitError.value = ''
  pendingDraft.value = null
}

function addOutput() {
  form.outputs.push({ quantity: '', productId: '', externalBatchNo: '' })
}

function removeOutput(index: number) {
  if (form.outputs.length > minOutputs.value) form.outputs.splice(index, 1)
}

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  loadError.value = ''
  const batchId = Number(props.id)
  if (!Number.isInteger(batchId) || batchId <= 0) {
    loadState.value = 'not-found'
    return
  }
  try {
    const [loadedBatch, transfers, activeProducts] = await Promise.all([
      getBatch(batchId, current.signal),
      listTransfers({ batchId, page: 1, size: 20 }, current.signal),
      listActiveProducts(current.signal)
    ])
    if (current.signal.aborted) return
    batch.value = loadedBatch
    products.value = activeProducts
    openTransfer.value = transfers.items.find((t) => t.status === 'DRAFT' || t.status === 'PENDING') ?? null
    directory.resolveProducts([loadedBatch.productId])
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) loadState.value = 'not-found'
    else if (err instanceof ApiError && err.status === 403) loadState.value = 'forbidden'
    else {
      loadState.value = 'error'
      loadError.value = err instanceof ApiError ? err.message : '页面加载失败，请稍后重试'
    }
  }
}

function buildItems(current: Batch): BatchOperationItemRequest[] {
  const items: BatchOperationItemRequest[] = [
    { role: 'INPUT', batchId: current.id, quantity: inputQuantity.value, unitCode: 'kg' }
  ]
  for (const row of form.outputs) {
    const item: BatchOperationItemRequest = { role: 'OUTPUT', quantity: Number(row.quantity), unitCode: 'kg' }
    if (!isSplit.value && row.productId) item.productId = Number(row.productId)
    if (row.externalBatchNo.trim()) item.externalBatchNo = row.externalBatchNo.trim()
    items.push(item)
  }
  for (const [role, value] of [['LOSS', form.loss], ['WASTE', form.waste], ['SAMPLE', form.sample]] as const) {
    if (value.trim() !== '' && Number(value) > 0) items.push({ role, quantity: Number(value), unitCode: 'kg' })
  }
  return items
}

async function submit() {
  const current = batch.value
  if (!current || submitting.value) return
  formError.value = ''
  submitError.value = ''
  if (form.outputs.length < minOutputs.value) {
    formError.value = `${formatOperationType(props.type)}至少需要 ${minOutputs.value} 个产出批次`
    return
  }
  if (form.outputs.some((o) => !o.quantity.trim() || (toMilli(o.quantity) ?? 0) <= 0)) {
    formError.value = '每个产出批次的数量必须大于 0（最多 3 位小数）'
    return
  }
  if (!balanced.value) {
    formError.value = '物料不平衡：投入必须等于产出 + 损耗 + 废弃 + 留样'
    return
  }
  submitting.value = true
  let draft: BatchOperation | null = pendingDraft.value
  try {
    if (!draft) {
      const signature = JSON.stringify(buildItems(current))
      draft = await createWriter.run(
        `create-op:${current.id}:${props.type}:${signature}:${form.note.trim()}`,
        () => ({
          operationType: props.type,
          occurredAt: new Date().toISOString(),
          ...(form.note.trim() ? { note: form.note.trim() } : {}),
          items: buildItems(current)
        }),
        (payload, key) => createBatchOperation(payload, key)
      )
      pendingDraft.value = draft
    }
    const createdDraft = draft
    const submitted = await submitWriter.run(
      `submit-op:${createdDraft.id}:${createdDraft.version}`,
      () => createdDraft.version,
      (version, key) => submitBatchOperation(createdDraft.id, version, key)
    )
    pendingDraft.value = null
    const outputs = submitted.items.filter((i) => i.role === 'OUTPUT' && i.batchId)
    const summary = outputs.map((o) => `${o.traceBatchNo ?? o.batchId}（${formatQuantity(o.quantity, o.unitCode)}）`).join('、')
    const message = props.type === 'PROCESS'
      ? `加工单 ${submitted.operationNo} 已提交：输入批次已全量消耗并关闭，产出 ${summary} 已激活，系统已自动生成 PROCESS 追溯事件。`
      : `拆分单 ${submitted.operationNo} 已提交：输入批次已关闭，产出 ${summary} 已激活（拆分只生成谱系，不生成包装事件）。`
    if (outputs.length === 1 && outputs[0].batchId) {
      setBatchFlash(outputs[0].batchId, { tone: 'success', message })
      await router.push(`/app/batches/${outputs[0].batchId}`)
    } else {
      setPageFlash(`operation:${submitted.id}`, { tone: 'success', message })
      await router.push(`/app/batch-operations/${submitted.id}`)
    }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    submitError.value = describeWriteError(err, draft ? '提交批次操作' : '创建批次操作')
    // 草稿已创建但提交被明确拒绝：保留草稿入口，由操作详情页重试或删除
    if (draft && !(err instanceof ApiError && (err.status === 0 || err.status === 408))) pendingDraft.value = draft
  } finally {
    submitting.value = false
  }
}

watch(() => [props.id, props.type], () => {
  resetForm()
  load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="operation-wizard-page">
    <RouterLink :to="`/app/batches/${id}`" class="ent-button ent-back-link" data-testid="back-to-batch">← 返回批次详情</RouterLink>

    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title" data-testid="wizard-title">{{ isSplit ? '拆分批次' : '加工批次' }}</h1>
        <p class="ent-page-subtitle">
          <template v-if="isSplit">
            拆分把一个批次全量分成至少两个新批次，只建立谱系、不改变产品，也不声明包装或加工。
          </template>
          <template v-else>
            加工全量消耗输入批次，产出新的加工批次，并自动生成 PROCESS 追溯事件；加工不代表速冻。
          </template>
          输入必须全量消耗剩余数量；如只需部分加工，请先拆分。提交成功后输入批次关闭、产出批次激活。
        </p>
      </div>
    </div>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="wizard-loading">正在加载…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert">未找到该批次。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="wizard-forbidden">
      该批次当前不由本组织负责，不能加工或拆分。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="batch">
      <section class="ent-card" aria-labelledby="op-input-title" data-testid="wizard-input">
        <h2 id="op-input-title" class="ent-card-title">投入（全量消耗）</h2>
        <dl class="ent-dl">
          <dt>追溯批次号</dt>
          <dd class="mono" data-testid="wizard-input-batch">{{ batch.traceBatchNo }}</dd>
          <dt>批次类型</dt>
          <dd>{{ formatBatchType(batch.batchType) }}</dd>
          <dt>产品</dt>
          <dd>{{ directory.productLabel(batch.productId) }}</dd>
          <dt>状态</dt>
          <dd>
            <StatusBadge :info="formatFlowStatus(batch.flowStatus)" dimension="流转" />
            <StatusBadge :info="formatRiskStatus(batch.riskStatus)" dimension="风险" />
          </dd>
          <dt>投入数量</dt>
          <dd data-testid="wizard-input-quantity">{{ formatQuantity(inputQuantity, 'kg') }}（固定为当前全部剩余量，不可部分投入）</dd>
        </dl>
      </section>

      <div v-if="openTransfer" class="ent-card ent-state error" role="alert" data-testid="wizard-open-transfer">
        该批次存在未结束交接 <span class="mono">{{ openTransfer.transferNo }}</span>
        <StatusBadge :info="formatTransferStatus(openTransfer.status)" dimension="交接" />，不能加工或拆分。请先删除交接草稿或等待交接结束。
      </div>
      <div v-else-if="!allowed" class="ent-card ent-state error" role="alert" data-testid="wizard-not-allowed">
        只有加工企业的操作员可以对本组织负责、ACTIVE / NORMAL 且仍有剩余量的批次执行加工或拆分。
      </div>

      <form v-else class="ent-card" novalidate data-testid="wizard-form" @submit.prevent="submit">
        <h2 class="ent-card-title">产出批次（服务端生成，提交后激活）</h2>
        <div v-for="(row, index) in form.outputs" :key="index" class="ent-form-grid output-row" data-testid="wizard-output-row">
          <label class="ent-field">
            <span>产出 {{ index + 1 }} 数量（kg）<em>*</em></span>
            <input v-model="row.quantity" inputmode="decimal" :data-testid="`output-quantity-${index}`" placeholder="例如 960" />
          </label>
          <label v-if="!isSplit" class="ent-field">
            <span>产出产品</span>
            <select v-model="row.productId" :data-testid="`output-product-${index}`">
              <option value="">沿用投入产品（{{ directory.productLabel(batch.productId) }}）</option>
              <option v-for="p in products" :key="p.id" :value="String(p.id)">{{ p.publicName }} · {{ p.specification }}</option>
            </select>
          </label>
          <label class="ent-field">
            <span>外部批号（可选）</span>
            <input v-model="row.externalBatchNo" maxlength="64" :data-testid="`output-external-${index}`" />
          </label>
          <div class="ent-actions row-actions">
            <button v-if="form.outputs.length > minOutputs" type="button" class="ent-button ent-danger" :data-testid="`remove-output-${index}`" @click="removeOutput(index)">移除</button>
          </div>
        </div>
        <div class="ent-actions">
          <button type="button" class="ent-button" data-testid="add-output" @click="addOutput">+ 增加产出批次</button>
          <small v-if="isSplit" class="ent-muted">拆分产出沿用投入批次的产品与批次类型（{{ formatBatchType(batch.batchType) }}）。</small>
          <small v-else class="ent-muted">加工产出批次类型为“加工批次”，不声明速冻日期。</small>
        </div>

        <h2 class="ent-card-title section-gap">损耗、废弃与留样（kg，可选）</h2>
        <div class="ent-form-grid">
          <label class="ent-field">
            <span>损耗 LOSS</span>
            <input v-model="form.loss" inputmode="decimal" data-testid="loss-quantity" />
          </label>
          <label class="ent-field">
            <span>废弃 WASTE</span>
            <input v-model="form.waste" inputmode="decimal" data-testid="waste-quantity" />
          </label>
          <label class="ent-field">
            <span>留样 SAMPLE</span>
            <input v-model="form.sample" inputmode="decimal" data-testid="sample-quantity" />
          </label>
          <label class="ent-field wide">
            <span>备注（可选）</span>
            <textarea v-model="form.note" maxlength="500" rows="2" data-testid="operation-note"></textarea>
          </label>
        </div>

        <p
          class="balance"
          :class="balanced ? 'ok' : 'bad'"
          role="status"
          data-testid="balance-indicator"
          :data-balanced="balanced ? 'true' : 'false'"
        >
          <template v-if="!balance.valid">数量格式不正确：必须为非负数，最多 3 位小数。</template>
          <template v-else>
            投入 {{ fromMilli(balance.input) }} kg ＝ 产出 + 损耗 + 废弃 + 留样 {{ fromMilli(balance.right) }} kg
            <strong v-if="balanced">✔ 物料平衡</strong>
            <strong v-else>（差 {{ fromMilli(Math.abs(balance.diff)) }} kg）</strong>
          </template>
        </p>
        <p class="ent-muted balance-note">页面计算仅供参考，服务端会重新精确校验物料平衡。</p>

        <p v-if="formError" class="ent-flash error" role="alert" data-testid="wizard-form-error">{{ formError }}</p>
        <div v-if="submitError" class="ent-flash error" role="alert" data-testid="wizard-submit-error">
          <p>{{ submitError }}</p>
          <p v-if="pendingDraft">
            草稿 <RouterLink :to="`/app/batch-operations/${pendingDraft.id}`" class="mono" data-testid="wizard-draft-link">{{ pendingDraft.operationNo }}</RouterLink>
            已创建但未提交，可在操作详情中重试提交或删除草稿。
          </p>
        </div>

        <div class="ent-actions">
          <button type="submit" class="ent-button ent-primary" :disabled="submitting || !balanced" data-testid="wizard-submit">
            {{ submitting ? '提交中…' : (pendingDraft ? '重新提交草稿' : `创建并提交${formatOperationType(type)}`) }}
          </button>
        </div>
      </form>
    </template>
  </div>
</template>

<style scoped>
.output-row {
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px dashed var(--color-border);
}
.row-actions {
  align-self: end;
  margin-top: 0;
}
.section-gap {
  margin-top: 18px;
}
.balance {
  margin: 16px 0 4px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  font-size: 14px;
}
.balance.ok {
  color: var(--color-success-text);
  background-color: var(--color-success-bg);
  border: 1px solid var(--color-success-border);
}
.balance.bad {
  color: var(--color-warning-text);
  background-color: var(--color-warning-bg);
  border: 1px solid var(--color-warning-border);
}
.balance-note {
  margin: 0 0 12px;
  font-size: 12px;
}
</style>
