<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { listBatches } from '@/api/batches'
import { ApiError } from '@/api/client'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import {
  BATCH_FLOW_STATUSES,
  BATCH_RISK_STATUSES,
  type Batch,
  type BatchFlowStatus,
  type BatchRiskStatus
} from '@/types/enterprise'
import type { PageMeta } from '@/types/api'
import { formatFlowStatus, formatQuantity, formatRiskStatus } from '@/utils/formatters'
import { parseBatchListQuery, rememberBatchListQuery, toRouteQuery } from './batchQuery'

type LoadState = 'loading' | 'loaded' | 'error'

const route = useRoute()
const router = useRouter()
const directory = useDirectoryLabels()

const loadState = ref<LoadState>('loading')
const batches = ref<Batch[]>([])
const pageMeta = ref<PageMeta | null>(null)
const errorMessage = ref('')

const currentQuery = computed(() => parseBatchListQuery(route.query))
const hasFilters = computed(() => Boolean(currentQuery.value.flowStatus || currentQuery.value.riskStatus))

let activeRequest: AbortController | null = null

async function load() {
  activeRequest?.abort()
  const controller = new AbortController()
  activeRequest = controller
  const query = currentQuery.value
  loadState.value = 'loading'
  errorMessage.value = ''
  rememberBatchListQuery(toRouteQuery(query))

  try {
    const result = await listBatches(query, controller.signal)
    if (controller.signal.aborted) return
    batches.value = result.items
    pageMeta.value = result.page
    loadState.value = 'loaded'
    directory.resolveProducts(result.items.map((b) => b.productId))
    directory.resolveOrganizations(result.items.map((b) => b.orgId))
  } catch (err: unknown) {
    if (controller.signal.aborted) return
    batches.value = []
    pageMeta.value = null
    loadState.value = 'error'
    if (err instanceof ApiError) {
      if (err.status === 401) return
      errorMessage.value = err.status === 0 || err.status === 408
        ? err.message
        : `批次列表加载失败：${err.message}${err.requestId ? `（请求编号 ${err.requestId}）` : ''}`
    } else {
      errorMessage.value = '批次列表加载失败，请稍后重试'
    }
  }
}

function updateQuery(patch: { page?: number; flowStatus?: BatchFlowStatus; riskStatus?: BatchRiskStatus }) {
  const next = { ...currentQuery.value, ...patch }
  router.push({ path: '/app/batches', query: toRouteQuery(next) })
}

function onFlowChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  updateQuery({ page: 1, flowStatus: (value || undefined) as BatchFlowStatus | undefined })
}

function onRiskChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  updateQuery({ page: 1, riskStatus: (value || undefined) as BatchRiskStatus | undefined })
}

function clearFilters() {
  router.push({ path: '/app/batches' })
}

function goToPage(page: number) {
  updateQuery({ page })
}

function openBatch(batch: Batch) {
  router.push(`/app/batches/${batch.id}`)
}

watch(() => route.fullPath, () => {
  if (route.path === '/app/batches') load()
}, { immediate: true })

onBeforeUnmount(() => activeRequest?.abort())
</script>

<template>
  <div class="batch-list-page">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">批次列表</h1>
        <p class="ent-page-subtitle">仅显示当前责任组织为本组织的批次（数据来自服务端实时查询）。</p>
      </div>
    </div>

    <section class="ent-card filter-bar" aria-label="批次筛选">
      <label class="filter-field">
        <span>流转状态</span>
        <select data-testid="filter-flow-status" :value="currentQuery.flowStatus ?? ''" @change="onFlowChange">
          <option value="">全部</option>
          <option v-for="status in BATCH_FLOW_STATUSES" :key="status" :value="status">
            {{ formatFlowStatus(status).label }}（{{ status }}）
          </option>
        </select>
      </label>
      <label class="filter-field">
        <span>风险状态</span>
        <select data-testid="filter-risk-status" :value="currentQuery.riskStatus ?? ''" @change="onRiskChange">
          <option value="">全部</option>
          <option v-for="status in BATCH_RISK_STATUSES" :key="status" :value="status">
            {{ formatRiskStatus(status).label }}（{{ status }}）
          </option>
        </select>
      </label>
      <button type="button" class="ent-button" data-testid="clear-filters" :disabled="!hasFilters" @click="clearFilters">
        清除筛选
      </button>
    </section>

    <section class="ent-card list-card" aria-live="polite">
      <div v-if="loadState === 'loading'" class="ent-state" data-testid="batch-list-loading">正在加载批次…</div>

      <div v-else-if="loadState === 'error'" class="ent-state error" role="alert" data-testid="batch-list-error">
        <p>{{ errorMessage || '批次列表加载失败' }}</p>
        <button type="button" class="ent-button" @click="load">重试</button>
      </div>

      <div v-else-if="batches.length === 0" class="ent-state" data-testid="batch-list-empty">
        {{ hasFilters ? '没有符合当前筛选条件的批次。' : '本组织当前没有负责的批次。' }}
      </div>

      <template v-else>
        <div class="table-scroll">
          <table class="batch-table" data-testid="batch-table">
            <thead>
              <tr>
                <th scope="col">追溯批次号</th>
                <th scope="col">外部批号</th>
                <th scope="col">产品</th>
                <th scope="col">声明数量</th>
                <th scope="col">当前责任组织</th>
                <th scope="col">流转状态</th>
                <th scope="col">风险状态</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="batch in batches"
                :key="batch.id"
                class="batch-row"
                :data-batch-id="batch.id"
                @click="openBatch(batch)"
              >
                <td>
                  <RouterLink :to="`/app/batches/${batch.id}`" class="mono trace-link" @click.stop>
                    {{ batch.traceBatchNo }}
                  </RouterLink>
                </td>
                <td class="mono">
                  <span v-if="batch.externalBatchNo">{{ batch.externalBatchNo }}</span>
                  <span v-else class="ent-muted">未填写</span>
                </td>
                <td>{{ directory.productLabel(batch.productId) }}</td>
                <td class="nowrap">{{ formatQuantity(batch.quantity, batch.unitCode) }}</td>
                <td>{{ directory.organizationLabel(batch.orgId) }}</td>
                <td><StatusBadge :info="formatFlowStatus(batch.flowStatus)" dimension="流转" /></td>
                <td><StatusBadge :info="formatRiskStatus(batch.riskStatus)" dimension="风险" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <nav v-if="loadState === 'loaded' && pageMeta && pageMeta.totalElements > 0" class="pagination" aria-label="分页">
        <span data-testid="batch-page-summary">
          共 {{ pageMeta.totalElements }} 条 · 第 {{ pageMeta.number }} / {{ Math.max(pageMeta.totalPages, 1) }} 页
        </span>
        <div class="pagination-actions">
          <button
            type="button"
            class="ent-button"
            data-testid="page-prev"
            :disabled="pageMeta.number <= 1"
            @click="goToPage(pageMeta.number - 1)"
          >
            上一页
          </button>
          <button
            type="button"
            class="ent-button"
            data-testid="page-next"
            :disabled="pageMeta.number >= pageMeta.totalPages"
            @click="goToPage(pageMeta.number + 1)"
          >
            下一页
          </button>
        </div>
      </nav>
    </section>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 14px;
}
.filter-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.filter-field select {
  min-height: 36px;
  min-width: 180px;
  padding: 4px 8px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  background-color: #ffffff;
  color: var(--color-text-body);
}
.list-card {
  padding: 0;
  overflow: hidden;
}
.table-scroll {
  overflow-x: auto;
}
.batch-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.batch-table th,
.batch-table td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
  vertical-align: middle;
}
.batch-table th {
  background-color: #f8fafc;
  color: var(--color-text-muted);
  font-weight: 600;
  white-space: nowrap;
}
.batch-row {
  cursor: pointer;
}
.batch-row:hover {
  background-color: var(--color-ocean-subtle);
}
.trace-link {
  color: var(--color-ocean-hover);
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}
.trace-link:hover {
  text-decoration: underline;
}
.nowrap {
  white-space: nowrap;
}
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 12px 14px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.pagination-actions {
  display: flex;
  gap: 8px;
}
.list-card .ent-state {
  margin: 14px;
}
</style>
