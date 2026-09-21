<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { getBatch, listBatchEvents, submitBatch } from '@/api/batches'
import { ApiError } from '@/api/client'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { Batch, TraceEvent } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatBatchType,
  formatDataSource,
  formatDate,
  formatFlowStatus,
  formatIsoDateTime,
  formatOrgType,
  formatOriginType,
  formatProductCategory,
  formatQuantity,
  formatRiskStatus,
  formatTraceEventType
} from '@/utils/formatters'
import { canManageSourceBatches } from '@/utils/permissions'
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
const submitting = ref(false)
const submitError = ref('')

/** 只有来源组织 OPERATOR、本组织负责的 DRAFT/NORMAL 来源批次显示“提交激活”；服务端仍独立校验。 */
const canSubmit = computed(() => {
  const b = batch.value
  return Boolean(b && canManageSourceBatches(user.value)
    && b.orgId === user.value?.orgId
    && b.batchType === 'SOURCE'
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
  } catch (err: unknown) {
    if (controller.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) {
      loadState.value = 'not-found'
    } else if (err instanceof ApiError && err.status === 403) {
      loadState.value = 'forbidden'
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

    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state" role="alert" data-testid="batch-detail-forbidden">
      该批次当前不由本组织负责，无权查看。
    </div>

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
          {{ batch.flowStatus === 'DRAFT' ? '草稿尚未激活，暂无追溯事件；提交激活后将自动生成 SOURCE 事件。' : '暂无追溯事件。' }}
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
.section-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
