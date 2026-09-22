<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { getBatch, listBatchEvents, submitBatch } from '@/api/batches'
import { listBatchOperations } from '@/api/batchOperations'
import { listTransfers } from '@/api/transfers'
import { ApiError } from '@/api/client'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { Batch, BatchOperation, TraceEvent, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatBatchType,
  formatDataSource,
  formatDate,
  formatFlowStatus,
  formatIsoDateTime,
  formatOperationStatus,
  formatOperationType,
  formatOrgType,
  formatOriginType,
  formatProductCategory,
  formatQuantity,
  formatRiskStatus,
  formatShipmentStatus,
  formatTraceEventType,
  formatTransferStatus
} from '@/utils/formatters'
import { canInitiateTransfer, canManageSourceBatches, canOperateBatch } from '@/utils/permissions'
import { takeBatchFlash, type BatchFlash } from './batchFlash'
import { recalledBatchListQuery } from './batchQuery'

const props = defineProps<{
  id: string
}>()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
type EventLoadState = 'loading' | 'loaded' | 'forbidden' | 'error'

const directory = useDirectoryLabels()
const loadState = ref<LoadState>('loading')
const batch = ref<Batch | null>(null)
const errorMessage = ref('')
const backLink = computed(() => ({ path: '/app/batches', query: recalledBatchListQuery() }))
const { user } = useSession()

const eventState = ref<EventLoadState>('loading')
const events = ref<TraceEvent[]>([])
const eventError = ref('')

const flash = ref<BatchFlash | null>(null)

type TransferLoadState = 'loading' | 'loaded' | 'error'
const transferState = ref<TransferLoadState>('loading')
const transfers = ref<Transfer[]>([])

/** 批次已转出（403）时，本组织作为历史参与组织仍可只读查看自己参与的交接、本组织的批次操作与本组织记录的历史事件。 */
const history = ref<{ transfers: Transfer[]; events: TraceEvent[]; operations: BatchOperation[] } | null>(null)

type OperationLoadState = 'loading' | 'loaded' | 'error'
const operationState = ref<OperationLoadState>('loading')
/** 本组织创建的、引用该批次（INPUT 或 OUTPUT）的批次操作；服务端不返回其他企业的内部单据 */
const operations = ref<BatchOperation[]>([])
const producedBy = computed(() => operations.value.find((op) => op.items.some((i) => i.role === 'OUTPUT' && i.batchId === batch.value?.id)) ?? null)
const consumedBy = computed(() => operations.value.filter((op) => op.items.some((i) => i.role === 'INPUT' && i.batchId === batch.value?.id)))
const remaining = computed(() => {
  const b = batch.value
  if (!b) return null
  return b.remainingQuantity ?? (b.flowStatus === 'CLOSED' ? 0 : b.quantity)
})

const openTransfer = computed(() => transfers.value.find((t) => t.status === 'DRAFT' || t.status === 'PENDING') ?? null)
const showInitiateTransfer = computed(() => canInitiateTransfer(user.value, batch.value) && transferState.value === 'loaded' && !openTransfer.value)
/** 加工 / 拆分入口：PROCESSOR 操作员、本组织负责的 ACTIVE + NORMAL 批次、无未结束交接（服务端仍独立校验） */
const showOperate = computed(() => canOperateBatch(user.value, batch.value) && transferState.value === 'loaded' && !openTransfer.value)
const submitting = ref(false)
const submitError = ref('')

/** 只有来源组织 OPERATOR、本组织负责的 DRAFT/NORMAL 来源批次显示“提交激活”；服务端仍独立校验。 */
const canSubmit = computed(() => {
  const b = batch.value
  return Boolean(b && canManageSourceBatches(user.value)
    && b.orgId === user.value?.orgId
    && b.batchType === 'SOURCE'
    && !b.producedByOperationId
    && b.flowStatus === 'DRAFT'
    && b.riskStatus === 'NORMAL')
})

const SOURCE_DETAIL_LABELS: Record<string, string> = {
  traceBatchNo: '追溯批次号',
  originType: '来源类型',
  originText: '来源说明',
  quantity: '声明数量',
  productionDate: '生产日期',
  captureDate: '捕捞日期',
  freezeDate: '速冻日期',
  shelfLifeDays: '保质期（天）'
}

const SHIPMENT_DETAIL_LABELS: Record<string, string> = {
  shipmentNo: '运输单号',
  carrierOrgName: '承运企业',
  vehicleOrContainerNo: '车辆 / 容器',
  originSiteName: '启运场所',
  destinationSiteName: '目的场所'
}

/** TRANSPORT / ARRIVAL 由运输任务自动生成，展示可追溯到运输任务的结构化事实。 */
function shipmentFacts(event: TraceEvent): Array<{ label: string; value: string }> {
  const details = event.detailsJson ?? {}
  const facts: Array<{ label: string; value: string }> = []
  for (const [key, label] of Object.entries(SHIPMENT_DETAIL_LABELS)) {
    const raw = details[key]
    if (raw === undefined || raw === null || raw === '') continue
    facts.push({ label, value: String(raw) })
  }
  return facts
}

function eventShipmentId(event: TraceEvent): number | null {
  const raw = event.detailsJson?.shipmentId
  const id = typeof raw === 'number' ? raw : Number(raw)
  return Number.isInteger(id) && id > 0 ? id : null
}

/** PROCESS 由批次操作提交自动生成：展示可追溯到加工单的结构化事实。 */
function processFacts(event: TraceEvent): Array<{ label: string; value: string }> {
  const details = event.detailsJson ?? {}
  const facts: Array<{ label: string; value: string }> = []
  if (details.operationNo) facts.push({ label: '加工单号', value: String(details.operationNo) })
  for (const [key, label] of [['lossQuantity', '损耗'], ['wasteQuantity', '废弃'], ['sampleQuantity', '留样']] as const) {
    const raw = details[key]
    if (raw !== undefined && raw !== null && Number(raw) > 0) facts.push({ label, value: formatQuantity(String(raw), 'kg') })
  }
  return facts
}

function eventOperationId(event: TraceEvent): number | null {
  if (event.detailsJson?.sourceObjectType !== 'BATCH_OPERATION') return null
  const id = Number(event.detailsJson?.sourceObjectId)
  return Number.isInteger(id) && id > 0 ? id : null
}

function counterpartBatches(op: BatchOperation, role: 'INPUT' | 'OUTPUT') {
  return op.items.filter((i) => i.role === role && i.batchId)
}

function sourceFacts(event: TraceEvent): Array<{ label: string; value: string }> {
  const details = event.detailsJson ?? {}
  const facts: Array<{ label: string; value: string }> = []
  for (const [key, label] of Object.entries(SOURCE_DETAIL_LABELS)) {
    const raw = details[key]
    if (raw === undefined || raw === null || raw === '') continue
    let value = String(raw)
    if (key === 'originType') value = formatOriginType(value)
    if (key === 'quantity') value = formatQuantity(value, typeof details.unitCode === 'string' ? details.unitCode : null)
    facts.push({ label, value })
  }
  return facts
}

const product = computed(() => {
  if (!batch.value) return null
  const entry = directory.products.get(batch.value.productId)
  return entry && entry.state === 'loaded' ? entry.value : null
})

const organization = computed(() => {
  if (!batch.value) return null
  const entry = directory.organizations.get(batch.value.orgId)
  return entry && entry.state === 'loaded' ? entry.value : null
})

let activeRequest: AbortController | null = null
let eventRequest: AbortController | null = null
let transferRequest: AbortController | null = null

async function loadTransfers(batchId: number) {
  transferRequest?.abort()
  const controller = new AbortController()
  transferRequest = controller
  transferState.value = 'loading'
  try {
    const page = await listTransfers({ batchId, page: 1, size: 20 }, controller.signal)
    if (controller.signal.aborted) return
    transfers.value = page.items
    directory.resolveOrganizations(page.items.flatMap((t) => [t.senderOrgId, t.receiverOrgId]))
    transferState.value = 'loaded'
  } catch {
    if (controller.signal.aborted) return
    transfers.value = []
    transferState.value = 'error'
  }
}

async function loadHistory(batchId: number, signal: AbortSignal) {
  try {
    const [page, ownEvents, ownOperations] = await Promise.all([
      listTransfers({ batchId, page: 1, size: 20 }, signal),
      listBatchEvents(batchId, signal).catch(() => [] as TraceEvent[]),
      listBatchOperations(batchId, signal).then((r) => r.items).catch(() => [] as BatchOperation[])
    ])
    if (signal.aborted) return
    directory.resolveOrganizations(page.items.flatMap((t) => [t.senderOrgId, t.receiverOrgId]))
    history.value = { transfers: page.items, events: ownEvents, operations: ownOperations }
  } catch {
    history.value = null
  }
}

let operationRequest: AbortController | null = null

async function loadOperations(batchId: number) {
  operationRequest?.abort()
  const controller = new AbortController()
  operationRequest = controller
  operationState.value = 'loading'
  try {
    const page = await listBatchOperations(batchId, controller.signal)
    if (controller.signal.aborted) return
    operations.value = page.items
    operationState.value = 'loaded'
  } catch {
    if (controller.signal.aborted) return
    operations.value = []
    operationState.value = 'error'
  }
}

async function loadEvents(batchId: number) {
  eventRequest?.abort()
  const controller = new AbortController()
  eventRequest = controller
  eventState.value = 'loading'
  eventError.value = ''
  try {
    const result = await listBatchEvents(batchId, controller.signal)
    if (controller.signal.aborted) return
    events.value = result
    eventState.value = 'loaded'
  } catch (err: unknown) {
    if (controller.signal.aborted) return
    events.value = []
    if (err instanceof ApiError && err.status === 403) {
      eventState.value = 'forbidden'
    } else {
      eventState.value = 'error'
      eventError.value = err instanceof ApiError
        ? `${err.message}${err.requestId ? `（请求编号 ${err.requestId}）` : ''}`
        : '追溯事件加载失败，请稍后重试'
    }
  }
}

async function load() {
  activeRequest?.abort()
  const controller = new AbortController()
  activeRequest = controller
  loadState.value = 'loading'
  batch.value = null
  history.value = null
  errorMessage.value = ''

  const batchId = Number(props.id)
  if (!Number.isInteger(batchId) || batchId <= 0) {
    loadState.value = 'not-found'
    return
  }

  try {
    const result = await getBatch(batchId, controller.signal)
    if (controller.signal.aborted) return
    batch.value = result
    loadState.value = 'loaded'
    directory.resolveProducts([result.productId])
    directory.resolveOrganizations([result.orgId])
    loadEvents(result.id)
    loadTransfers(result.id)
    loadOperations(result.id)
  } catch (err: unknown) {
    if (controller.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) {
      loadState.value = 'not-found'
    } else if (err instanceof ApiError && err.status === 403) {
      loadState.value = 'forbidden'
      loadHistory(batchId, controller.signal)
    } else {
      loadState.value = 'error'
      errorMessage.value = err instanceof ApiError
        ? `${err.message}${err.requestId ? `（请求编号 ${err.requestId}）` : ''}`
        : '批次详情加载失败，请稍后重试'
    }
  }
}

async function submitActivation() {
  const current = batch.value
  if (!current || submitting.value) return
  submitting.value = true
  submitError.value = ''
  flash.value = null
  try {
    await submitBatch(current.id, current.version)
    flash.value = { tone: 'success', message: '来源批次已激活，系统已自动生成 SOURCE 追溯事件。' }
    // 以服务端最新数据刷新详情与事件
    await load()
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '提交激活')
    // 409 表示状态或版本已被修改：刷新详情以展示服务端最新状态，并保留错误提示
    if (err instanceof ApiError && err.status === 409) await load()
    submitError.value = message
  } finally {
    submitting.value = false
  }
}

watch(() => props.id, (id) => {
  const numericId = Number(id)
  flash.value = Number.isInteger(numericId) ? takeBatchFlash(numericId) : null
  submitError.value = ''
  load()
}, { immediate: true })
onBeforeUnmount(() => {
  activeRequest?.abort()
  eventRequest?.abort()
  transferRequest?.abort()
  operationRequest?.abort()
})
</script>

<template>
  <div class="batch-detail-page">
    <RouterLink :to="backLink" class="ent-button back-link" data-testid="back-to-list">← 返回批次列表</RouterLink>

    <p v-if="flash" class="flash" :class="flash.tone" role="status" data-testid="batch-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="batch-detail-loading">正在加载批次详情…</div>

    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert" data-testid="batch-detail-not-found">
      未找到该批次，可能已被删除或编号有误。
    </div>

    <template v-else-if="loadState === 'forbidden'">
      <div class="ent-card ent-state" role="alert" data-testid="batch-detail-forbidden">
        该批次当前不由本组织负责，无权查看批次详情，也不能再修改该批次。
      </div>
      <section v-if="history && (history.transfers.length > 0 || history.events.length > 0 || history.operations.length > 0)" class="ent-card" data-testid="batch-history">
        <h2 class="ent-card-title">本组织参与的历史记录（只读）</h2>
        <ul class="history-list">
          <li v-for="t in history.transfers" :key="`t-${t.id}`" data-testid="history-transfer">
            交接 <span class="mono">{{ t.transferNo }}</span>
            <StatusBadge :info="formatTransferStatus(t.status)" dimension="交接" />
            {{ directory.organizationLabel(t.senderOrgId) }} → {{ directory.organizationLabel(t.receiverOrgId) }}
            <RouterLink v-if="t.shipmentId" :to="`/app/shipments/${t.shipmentId}`" class="mono">运输任务 {{ t.shipmentNo }}</RouterLink>
          </li>
          <li v-for="op in history.operations" :key="`o-${op.id}`" data-testid="history-operation">
            {{ formatOperationType(op.operationType) }}单
            <RouterLink :to="`/app/batch-operations/${op.id}`" class="mono">{{ op.operationNo }}</RouterLink>
            <StatusBadge :info="formatOperationStatus(op.status)" dimension="操作" />
          </li>
          <li v-for="event in history.events" :key="`e-${event.id}`" data-testid="history-event" :data-event-type="event.eventType">
            {{ formatTraceEventType(event.eventType) }} · {{ formatIsoDateTime(event.occurredAt) }} · {{ event.summary }}
          </li>
        </ul>
      </section>
    </template>

    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert" data-testid="batch-detail-error">
      <p>{{ errorMessage }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="batch">
      <div class="ent-page-header">
        <div>
          <p class="ent-page-subtitle">追溯批次号</p>
          <h1 class="ent-page-title mono" data-testid="detail-trace-batch-no">{{ batch.traceBatchNo }}</h1>
        </div>
        <div class="status-pair">
          <span>流转 <StatusBadge :info="formatFlowStatus(batch.flowStatus)" dimension="流转" data-testid="detail-flow-status" /></span>
          <span>风险 <StatusBadge :info="formatRiskStatus(batch.riskStatus)" dimension="风险" data-testid="detail-risk-status" /></span>
        </div>
      </div>

      <p v-if="batch.producedByOperationId && batch.flowStatus === 'DRAFT'" class="ent-next-step" data-testid="operation-draft-output">
        <strong>批次操作产出草稿：</strong>该批次由
        <RouterLink :to="`/app/batch-operations/${batch.producedByOperationId}`">批次操作草稿</RouterLink>
        产出，只能随该操作提交激活，不能单独修改或激活。
      </p>
      <p v-if="batch.consumedByOperationId" class="ent-next-step" data-testid="operation-consumed">
        <strong>已全量消耗：</strong>该批次已被
        <RouterLink :to="`/app/batch-operations/${batch.consumedByOperationId}`">批次操作</RouterLink>
        全量消耗并关闭，不能再加工、拆分或交接；仍可查询追溯记录。
      </p>

      <section v-if="canSubmit" class="ent-card activation-card" aria-labelledby="activation-title" data-testid="activation-card">
        <h2 id="activation-title" class="ent-card-title">提交激活</h2>
        <p class="section-note">
          激活后批次变为 ACTIVE / NORMAL，可参与后续业务；系统将在同一事务内自动生成唯一的 SOURCE 追溯事件。
        </p>
        <p v-if="submitError" class="ent-state error submit-error" role="alert" data-testid="submit-error">{{ submitError }}</p>
        <button
          type="button"
          class="ent-button primary"
          data-testid="submit-activation"
          :disabled="submitting"
          @click="submitActivation"
        >
          {{ submitting ? '提交激活中…' : '提交激活' }}
        </button>
      </section>
      <p v-else-if="submitError" class="ent-state error submit-error" role="alert" data-testid="submit-error">{{ submitError }}</p>

      <div class="detail-grid">
        <section class="ent-card" aria-labelledby="identity-title">
          <h2 id="identity-title" class="ent-card-title">批次标识与数量</h2>
          <dl class="ent-dl">
            <dt>追溯批次号</dt>
            <dd class="mono">{{ batch.traceBatchNo }}</dd>
            <dt>外部批号</dt>
            <dd class="mono" data-testid="detail-external-batch-no">
              <span v-if="batch.externalBatchNo">{{ batch.externalBatchNo }}</span>
              <span v-else class="ent-muted">未填写</span>
            </dd>
            <dt>批次类型</dt>
            <dd>{{ formatBatchType(batch.batchType) }}</dd>
            <dt>声明数量</dt>
            <dd data-testid="detail-quantity">{{ formatQuantity(batch.quantity, batch.unitCode) }}</dd>
            <dt>剩余数量</dt>
            <dd data-testid="detail-remaining-quantity">{{ formatQuantity(remaining, batch.unitCode) }}</dd>
          </dl>
        </section>

        <section class="ent-card" aria-labelledby="product-title">
          <h2 id="product-title" class="ent-card-title">产品</h2>
          <dl class="ent-dl">
            <dt>产品名称</dt>
            <dd data-testid="detail-product-name">{{ directory.productLabel(batch.productId) }}</dd>
            <template v-if="product">
              <dt>产品编码</dt>
              <dd class="mono">{{ product.productCode }}</dd>
              <dt>规格</dt>
              <dd>{{ product.specification }}</dd>
              <dt>品类</dt>
              <dd>{{ formatProductCategory(product.category) }}</dd>
            </template>
          </dl>
        </section>

        <section class="ent-card" aria-labelledby="origin-title">
          <h2 id="origin-title" class="ent-card-title">来源信息</h2>
          <dl class="ent-dl">
            <dt>来源类型</dt>
            <dd>{{ formatOriginType(batch.originType) }}</dd>
            <dt>来源说明</dt>
            <dd data-testid="detail-origin-text">{{ batch.originText }}</dd>
          </dl>
        </section>

        <section class="ent-card" aria-labelledby="date-title">
          <h2 id="date-title" class="ent-card-title">日期信息</h2>
          <dl class="ent-dl">
            <dt>生产日期</dt>
            <dd>{{ formatDate(batch.productionDate) }}</dd>
            <dt>捕捞日期</dt>
            <dd>{{ formatDate(batch.captureDate) }}</dd>
            <dt>速冻日期</dt>
            <dd>{{ formatDate(batch.freezeDate) }}</dd>
            <dt>保质期</dt>
            <dd>{{ batch.shelfLifeDays ? `${batch.shelfLifeDays} 天` : '未标明' }}</dd>
          </dl>
        </section>

        <section class="ent-card" aria-labelledby="org-title">
          <h2 id="org-title" class="ent-card-title">当前责任组织</h2>
          <dl class="ent-dl">
            <dt>组织名称</dt>
            <dd data-testid="detail-org-name">{{ directory.organizationLabel(batch.orgId) }}</dd>
            <template v-if="organization">
              <dt>组织编号</dt>
              <dd class="mono">{{ organization.orgNo }}</dd>
              <dt>组织类型</dt>
              <dd>{{ formatOrgType(organization.orgType) }}</dd>
            </template>
          </dl>
          <p class="section-note">当前责任组织只会在交接被接收方接受后改变；运输期间仍由发送方负责。</p>
        </section>

        <section class="ent-card secondary" aria-labelledby="tech-title">
          <h2 id="tech-title" class="ent-card-title">技术信息</h2>
          <dl class="ent-dl">
            <dt>内部 ID</dt>
            <dd class="mono">{{ batch.id }}</dd>
            <dt>版本号</dt>
            <dd class="mono" data-testid="detail-version">{{ batch.version }}</dd>
            <dt>创建时间</dt>
            <dd>{{ formatIsoDateTime(batch.createdAt) }}</dd>
            <dt>更新时间</dt>
            <dd>{{ formatIsoDateTime(batch.updatedAt) }}</dd>
          </dl>
        </section>
      </div>

      <section class="ent-card" aria-labelledby="lineage-title" data-testid="batch-lineage">
        <h2 id="lineage-title" class="ent-card-title">加工与拆分谱系</h2>
        <div v-if="operationState === 'loading'" class="ent-state">正在加载批次操作…</div>
        <div v-else-if="operationState === 'error'" class="ent-state error" role="alert">
          批次操作加载失败
          <button type="button" class="ent-button" @click="loadOperations(batch.id)">重试</button>
        </div>
        <template v-else>
          <div v-if="operations.length === 0" class="ent-muted section-note" data-testid="batch-lineage-empty">本组织暂无涉及该批次的加工或拆分记录。</div>
          <ul v-else class="history-list">
            <li v-if="producedBy" data-testid="lineage-produced-by">
              由{{ formatOperationType(producedBy.operationType) }}单
              <RouterLink :to="`/app/batch-operations/${producedBy.id}`" class="mono">{{ producedBy.operationNo }}</RouterLink>
              <StatusBadge :info="formatOperationStatus(producedBy.status)" dimension="操作" />
              产出；上游：
              <template v-for="(item, index) in counterpartBatches(producedBy, 'INPUT')" :key="item.id">
                <span v-if="index > 0">、</span>
                <RouterLink :to="`/app/batches/${item.batchId}`" class="mono" data-testid="lineage-parent">{{ item.traceBatchNo }}</RouterLink>
              </template>
            </li>
            <li v-for="op in consumedBy" :key="op.id" data-testid="lineage-consumed-by" :data-status="op.status">
              {{ op.status === 'SUBMITTED' ? '已被' : '草稿' }}{{ formatOperationType(op.operationType) }}单
              <RouterLink :to="`/app/batch-operations/${op.id}`" class="mono">{{ op.operationNo }}</RouterLink>
              <StatusBadge :info="formatOperationStatus(op.status)" dimension="操作" />
              {{ op.status === 'SUBMITTED' ? '全量消耗' : '计划投入' }}；下游：
              <template v-for="(item, index) in counterpartBatches(op, 'OUTPUT')" :key="item.id">
                <span v-if="index > 0">、</span>
                <RouterLink :to="`/app/batches/${item.batchId}`" class="mono" data-testid="lineage-child">{{ item.traceBatchNo }}</RouterLink>
                （{{ formatQuantity(item.quantity, item.unitCode) }}）
              </template>
            </li>
          </ul>
          <div v-if="showOperate" class="ent-actions" data-testid="operate-actions">
            <RouterLink :to="{ path: `/app/batches/${batch.id}/operations/new`, query: { type: 'PROCESS' } }" class="ent-button ent-primary" data-testid="start-process">加工</RouterLink>
            <RouterLink :to="{ path: `/app/batches/${batch.id}/operations/new`, query: { type: 'SPLIT' } }" class="ent-button" data-testid="start-split">拆分</RouterLink>
            <small class="ent-muted">加工或拆分都会全量消耗剩余 {{ formatQuantity(remaining, batch.unitCode) }}；如需部分加工，请先拆分。</small>
          </div>
        </template>
      </section>

      <section class="ent-card" aria-labelledby="handover-title" data-testid="batch-transfers">
        <h2 id="handover-title" class="ent-card-title">交接与运输</h2>
        <div v-if="transferState === 'loading'" class="ent-state">正在加载交接记录…</div>
        <div v-else-if="transferState === 'error'" class="ent-state error" role="alert">
          交接记录加载失败
          <button type="button" class="ent-button" @click="loadTransfers(batch.id)">重试</button>
        </div>
        <template v-else>
          <div v-if="transfers.length === 0" class="ent-muted section-note" data-testid="batch-transfers-empty">该批次暂无交接记录。</div>
          <div v-else class="ent-table-scroll">
            <table class="ent-table">
              <thead>
                <tr><th>交接单号</th><th>发送 → 接收</th><th>交接状态</th><th>运输任务</th></tr>
              </thead>
              <tbody>
                <tr v-for="t in transfers" :key="t.id" data-testid="batch-transfer-row" :data-transfer-id="t.id">
                  <td class="mono">{{ t.transferNo }}</td>
                  <td>{{ directory.organizationLabel(t.senderOrgId) }} → {{ directory.organizationLabel(t.receiverOrgId) }}</td>
                  <td><StatusBadge :info="formatTransferStatus(t.status)" dimension="交接" data-testid="batch-transfer-status" :data-status="t.status" /></td>
                  <td>
                    <template v-if="t.shipmentId">
                      <RouterLink :to="`/app/shipments/${t.shipmentId}`" class="mono" data-testid="batch-transfer-shipment">{{ t.shipmentNo }}</RouterLink>
                      <StatusBadge v-if="t.shipmentStatus" :info="formatShipmentStatus(t.shipmentStatus)" dimension="运输" />
                    </template>
                    <RouterLink
                      v-else-if="t.status === 'DRAFT' && t.senderOrgId === user?.orgId"
                      :to="{ path: '/app/shipments/new', query: { transferId: String(t.id) } }"
                      class="ent-button"
                      data-testid="batch-transfer-create-shipment"
                    >
                      创建运输任务并装载
                    </RouterLink>
                    <span v-else class="ent-muted">未绑定</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="showInitiateTransfer" class="ent-actions">
            <RouterLink :to="`/app/batches/${batch.id}/transfers/new`" class="ent-button ent-primary" data-testid="initiate-transfer">发起交接</RouterLink>
            <small class="ent-muted">交接给其他企业；接受前批次仍由本组织负责。</small>
          </div>
        </template>
      </section>

      <section class="ent-card" aria-labelledby="events-title" data-testid="trace-events">
        <h2 id="events-title" class="ent-card-title">追溯事件</h2>
        <div v-if="eventState === 'loading'" class="ent-state" data-testid="events-loading">正在加载追溯事件…</div>
        <div v-else-if="eventState === 'forbidden'" class="ent-state" role="alert" data-testid="events-forbidden">
          无权查看该批次的追溯事件。
        </div>
        <div v-else-if="eventState === 'error'" class="ent-state error" role="alert" data-testid="events-error">
          <p>追溯事件加载失败：{{ eventError }}</p>
          <button type="button" class="ent-button" @click="loadEvents(batch.id)">重试</button>
        </div>
        <div v-else-if="events.length === 0" class="ent-state" data-testid="events-empty">
          <template v-if="batch.flowStatus === 'DRAFT' && batch.producedByOperationId">批次操作产出草稿，暂无追溯事件。</template>
          <template v-else-if="batch.flowStatus === 'DRAFT'">草稿尚未激活，暂无追溯事件；提交激活后将自动生成 SOURCE 事件。</template>
          <template v-else>暂无追溯事件。</template>
        </div>
        <ol v-else class="event-list">
          <li
            v-for="event in events"
            :key="event.id"
            class="event-item"
            :data-event-type="event.eventType"
            data-testid="trace-event"
          >
            <div class="event-head">
              <strong data-testid="event-type">{{ formatTraceEventType(event.eventType) }}</strong>
              <span class="mono event-code">{{ event.eventType }}</span>
              <span class="event-status" :class="{ corrected: event.status === 'CORRECTED' }">
                {{ event.status === 'CORRECTED' ? '已更正' : '有效' }}
              </span>
            </div>
            <p class="event-summary">{{ event.summary }}</p>
            <dl class="ent-dl event-meta">
              <dt>发生时间</dt>
              <dd>{{ formatIsoDateTime(event.occurredAt) }}</dd>
              <dt>登记时间</dt>
              <dd>{{ formatIsoDateTime(event.recordedAt) }}</dd>
              <dt>数据来源</dt>
              <dd>{{ formatDataSource(event.dataSource) }}</dd>
              <template v-if="event.eventType === 'SOURCE'">
                <template v-for="fact in sourceFacts(event)" :key="fact.label">
                  <dt>{{ fact.label }}</dt>
                  <dd>{{ fact.value }}</dd>
                </template>
              </template>
              <template v-if="event.eventType === 'PROCESS' && eventOperationId(event)">
                <template v-for="fact in processFacts(event)" :key="fact.label">
                  <dt>{{ fact.label }}</dt>
                  <dd :data-testid="`event-fact-${fact.label}`">
                    <RouterLink v-if="fact.label === '加工单号'" :to="`/app/batch-operations/${eventOperationId(event)}`">{{ fact.value }}</RouterLink>
                    <span v-else>{{ fact.value }}</span>
                  </dd>
                </template>
              </template>
              <template v-if="event.eventType === 'TRANSPORT' || event.eventType === 'ARRIVAL'">
                <template v-for="fact in shipmentFacts(event)" :key="fact.label">
                  <dt>{{ fact.label }}</dt>
                  <dd :data-testid="`event-fact-${fact.label}`">
                    <RouterLink v-if="fact.label === '运输单号' && eventShipmentId(event)" :to="`/app/shipments/${eventShipmentId(event)}`">{{ fact.value }}</RouterLink>
                    <span v-else>{{ fact.value }}</span>
                  </dd>
                </template>
              </template>
            </dl>
          </li>
        </ol>
      </section>
    </template>
  </div>
</template>

<style scoped>
.back-link {
  margin-bottom: 14px;
}
.flash {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.flash.success {
  color: #166534;
  background-color: #f0fdf4;
  border: 1px solid #bbf7d0;
}
.flash.warning {
  color: #92400e;
  background-color: #fffbeb;
  border: 1px solid #fde68a;
}
.activation-card .section-note {
  margin: 0 0 12px;
}
.submit-error {
  margin: 0 0 12px;
  padding: 12px;
  text-align: left;
}
.ent-button.primary {
  background-color: var(--color-ocean);
  border-color: var(--color-ocean);
  color: #ffffff;
}
.ent-button.primary:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
  color: #ffffff;
}
.event-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.event-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 12px 14px;
}
.event-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.event-code {
  font-size: 12px;
  color: var(--color-text-muted);
}
.event-status {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  background-color: var(--color-ocean-subtle);
  color: var(--color-ocean-hover);
}
.event-status.corrected {
  background-color: #f1f5f9;
  color: var(--color-text-muted);
}
.event-summary {
  margin: 8px 0;
  font-size: 14px;
  color: var(--color-text-body);
}
.event-meta {
  font-size: 13px;
}
.status-pair {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--color-text-muted);
}
.status-pair > span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 0 16px;
}
.secondary {
  background-color: #f8fafc;
}
.history-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}
.section-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
