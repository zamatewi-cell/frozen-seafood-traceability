<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { deleteBatchOperationDraft, getBatchOperation, submitBatchOperation } from '@/api/batchOperations'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { BatchOperation } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatBatchType,
  formatFlowStatus,
  formatIsoDateTime,
  formatItemRole,
  formatOperationStatus,
  formatOperationType,
  formatQuantity
} from '@/utils/formatters'
import { setBatchFlash } from './batchFlash'
import { takePageFlash, type PageFlash } from './pageFlash'

const props = defineProps<{ id: string }>()

const router = useRouter()
const { user } = useSession()
const directory = useDirectoryLabels()
const submitWriter = useIdempotentWrite()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const operation = ref<BatchOperation | null>(null)
const flash = ref<PageFlash | null>(null)
const acting = ref(false)
const actionError = ref('')

const canWrite = computed(() => {
  const u = user.value
  const op = operation.value
  return Boolean(u && op && op.status === 'DRAFT'
    && op.orgId === u.orgId
    && u.orgType === 'PROCESSOR'
    && u.roles.includes('OPERATOR')
    && !u.scopes.includes('PLATFORM'))
})
const inputs = computed(() => operation.value?.items.filter((i) => i.role === 'INPUT') ?? [])
const outputs = computed(() => operation.value?.items.filter((i) => i.role === 'OUTPUT') ?? [])
const others = computed(() => operation.value?.items.filter((i) => i.role !== 'INPUT' && i.role !== 'OUTPUT') ?? [])

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  loadError.value = ''
  const operationId = Number(props.id)
  if (!Number.isInteger(operationId) || operationId <= 0) {
    loadState.value = 'not-found'
    return
  }
  try {
    const result = await getBatchOperation(operationId, current.signal)
    if (current.signal.aborted) return
    operation.value = result
    directory.resolveProducts(result.items.flatMap((i) => (i.productId ? [i.productId] : [])))
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) loadState.value = 'not-found'
    else if (err instanceof ApiError && err.status === 403) loadState.value = 'forbidden'
    else {
      loadState.value = 'error'
      loadError.value = err instanceof ApiError ? err.message : '批次操作加载失败，请稍后重试'
    }
  }
}

async function submitDraft() {
  const op = operation.value
  if (!op || acting.value) return
  acting.value = true
  actionError.value = ''
  flash.value = null
  try {
    const submitted = await submitWriter.run(
      `submit-op:${op.id}:${op.version}`,
      () => op.version,
      (version, key) => submitBatchOperation(op.id, version, key)
    )
    operation.value = submitted
    flash.value = {
      tone: 'success',
      message: submitted.operationType === 'PROCESS'
        ? '已提交：输入批次已全量消耗并关闭，产出批次已激活，系统已自动生成 PROCESS 追溯事件。'
        : '已提交：输入批次已关闭，产出批次已激活（拆分只生成谱系，不生成包装事件）。'
    }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    actionError.value = describeWriteError(err, '提交批次操作')
    if (err instanceof ApiError && err.status === 409) await load()
  } finally {
    acting.value = false
  }
}

async function deleteDraft() {
  const op = operation.value
  if (!op || acting.value) return
  acting.value = true
  actionError.value = ''
  try {
    await deleteBatchOperationDraft(op.id, op.version)
    const input = op.items.find((i) => i.role === 'INPUT' && i.batchId)
    if (input?.batchId) {
      setBatchFlash(input.batchId, { tone: 'success', message: `操作草稿 ${op.operationNo} 已删除，其产出草稿批次一并删除；本批次未被消耗。` })
      await router.push(`/app/batches/${input.batchId}`)
    } else {
      await router.push('/app/batches')
    }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    actionError.value = describeWriteError(err, '删除操作草稿')
    if (err instanceof ApiError && err.status === 409) await load()
  } finally {
    acting.value = false
  }
}

watch(() => props.id, (id) => {
  flash.value = takePageFlash(`operation:${id}`)
  actionError.value = ''
  load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="operation-detail-page">
    <RouterLink to="/app/batches" class="ent-button ent-back-link">← 返回批次列表</RouterLink>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="operation-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading'" class="ent-card ent-state">正在加载批次操作…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert" data-testid="operation-not-found">未找到该批次操作，可能已被删除。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="operation-forbidden">
      该批次操作属于其他企业，无权查看。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="operation">
      <div class="ent-page-header">
        <div>
          <p class="ent-page-subtitle">{{ formatOperationType(operation.operationType) }}单</p>
          <h1 class="ent-page-title mono" data-testid="operation-no">{{ operation.operationNo }}</h1>
        </div>
        <StatusBadge :info="formatOperationStatus(operation.status)" dimension="操作" data-testid="operation-status" :data-status="operation.status" />
      </div>

      <p v-if="operation.status === 'SUBMITTED'" class="ent-next-step" data-testid="operation-next-step">
        <strong>下一步：</strong>产出批次已激活，可在批次详情中继续
        {{ operation.operationType === 'PROCESS' ? '拆分或发起交接' : '发起交接' }}。
      </p>

      <section class="ent-card" aria-labelledby="op-summary-title">
        <h2 id="op-summary-title" class="ent-card-title">操作信息</h2>
        <dl class="ent-dl">
          <dt>操作类型</dt>
          <dd data-testid="operation-type">{{ formatOperationType(operation.operationType) }}</dd>
          <dt>业务发生时间</dt>
          <dd>{{ formatIsoDateTime(operation.occurredAt) }}</dd>
          <dt>登记时间</dt>
          <dd>{{ formatIsoDateTime(operation.recordedAt) }}</dd>
          <template v-if="operation.note">
            <dt>备注</dt>
            <dd>{{ operation.note }}</dd>
          </template>
          <dt>物料平衡</dt>
          <dd data-testid="operation-balance" :data-balanced="operation.balanced ? 'true' : 'false'">
            投入 {{ formatQuantity(operation.inputTotal, 'kg') }} ＝ 产出 {{ formatQuantity(operation.outputTotal, 'kg') }}
            + 损耗 {{ formatQuantity(operation.lossTotal ?? 0, 'kg') }} + 废弃 {{ formatQuantity(operation.wasteTotal ?? 0, 'kg') }}
            + 留样 {{ formatQuantity(operation.sampleTotal ?? 0, 'kg') }}
            <strong>{{ operation.balanced ? '✔ 平衡' : '✘ 不平衡' }}</strong>
          </dd>
        </dl>
      </section>

      <section class="ent-card" aria-labelledby="op-items-title" data-testid="operation-items">
        <h2 id="op-items-title" class="ent-card-title">投入与产出</h2>
        <div class="ent-table-scroll">
          <table class="ent-table">
            <thead>
              <tr><th>角色</th><th>追溯批次号</th><th>产品</th><th>批次类型</th><th>数量</th><th>批次流转状态</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in [...inputs, ...outputs]" :key="item.id" data-testid="operation-item" :data-role="item.role">
                <td>{{ formatItemRole(item.role) }}</td>
                <td>
                  <RouterLink v-if="item.batchId && item.batchFlowStatus" :to="`/app/batches/${item.batchId}`" class="mono" data-testid="operation-item-batch">{{ item.traceBatchNo }}</RouterLink>
                  <span v-else class="mono">{{ item.traceBatchNo ?? '—' }}</span>
                </td>
                <td>{{ item.productId ? directory.productLabel(item.productId) : '—' }}</td>
                <td>{{ formatBatchType(item.batchType) }}</td>
                <td>{{ formatQuantity(item.quantity, item.unitCode) }}</td>
                <td>
                  <StatusBadge v-if="item.batchFlowStatus" :info="formatFlowStatus(item.batchFlowStatus)" dimension="流转" data-testid="operation-item-flow" :data-flow="item.batchFlowStatus" />
                </td>
              </tr>
              <tr v-for="item in others" :key="item.id" data-testid="operation-item" :data-role="item.role">
                <td>{{ formatItemRole(item.role) }}</td>
                <td colspan="3" class="ent-muted">不形成批次</td>
                <td>{{ formatQuantity(item.quantity, item.unitCode) }}</td>
                <td></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="operation.status === 'DRAFT'" class="ent-muted section-note">
          草稿阶段产出批次保持草稿（DRAFT），投入批次尚未消耗；提交后在同一事务内关闭投入、激活产出并固化谱系。
        </p>
        <p v-else-if="operation.operationType === 'SPLIT'" class="ent-muted section-note">拆分只建立谱系关系，不生成包装（PACK）或加工（PROCESS）事件。</p>
      </section>

      <p v-if="actionError" class="ent-flash error" role="alert" data-testid="operation-action-error">{{ actionError }}</p>
      <div v-if="canWrite" class="ent-actions">
        <button type="button" class="ent-button ent-primary" :disabled="acting" data-testid="operation-submit" @click="submitDraft">提交操作</button>
        <button type="button" class="ent-button ent-danger" :disabled="acting" data-testid="operation-delete" @click="deleteDraft">删除草稿</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.section-note {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>
