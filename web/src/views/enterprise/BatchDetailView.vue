<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { getBatch } from '@/api/batches'
import { ApiError } from '@/api/client'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import type { Batch } from '@/types/enterprise'
import {
  formatBatchType,
  formatDate,
  formatFlowStatus,
  formatIsoDateTime,
  formatOrgType,
  formatOriginType,
  formatProductCategory,
  formatQuantity,
  formatRiskStatus
} from '@/utils/formatters'
import { recalledBatchListQuery } from './batchQuery'

const props = defineProps<{
  id: string
}>()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'

const directory = useDirectoryLabels()
const loadState = ref<LoadState>('loading')
const batch = ref<Batch | null>(null)
const errorMessage = ref('')
const backLink = computed(() => ({ path: '/app/batches', query: recalledBatchListQuery() }))

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

watch(() => props.id, load, { immediate: true })
onBeforeUnmount(() => activeRequest?.abort())
</script>

<template>
  <div class="batch-detail-page">
    <RouterLink :to="backLink" class="ent-button back-link" data-testid="back-to-list">← 返回批次列表</RouterLink>

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
    </template>
  </div>
</template>

<style scoped>
.back-link {
  margin-bottom: 14px;
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
