<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { getBatch } from '@/api/batches'
import { ApiError } from '@/api/client'
import { listOrganizations } from '@/api/directory'
import { createTransfer, listTransfers } from '@/api/transfers'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { Batch, OrganizationSummary, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatOrgType, formatQuantity, formatTransferStatus } from '@/utils/formatters'
import { canInitiateTransfer } from '@/utils/permissions'
import { setPageFlash } from './pageFlash'

const props = defineProps<{ id: string }>()

const router = useRouter()
const { user } = useSession()
const writer = useIdempotentWrite()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const batch = ref<Batch | null>(null)
const receivers = ref<OrganizationSummary[]>([])
const openTransfer = ref<Transfer | null>(null)

const form = reactive({ receiverOrgId: '' })
const fieldError = ref('')
const submitting = ref(false)
const submitError = ref('')

const allowed = computed(() => canInitiateTransfer(user.value, batch.value))

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
    const [loadedBatch, orgs, transfers] = await Promise.all([
      getBatch(batchId, current.signal),
      listOrganizations(undefined, current.signal),
      listTransfers({ batchId, direction: 'SENT', page: 1, size: 20 }, current.signal)
    ])
    if (current.signal.aborted) return
    batch.value = loadedBatch
    // 承运商只负责物理运输、不会成为批次责任组织，因此不出现在接收方候选中（服务端同样拒绝）
    receivers.value = orgs.filter((org) => org.id !== user.value?.orgId && org.orgType !== 'CARRIER' && org.status === 'ACTIVE')
    openTransfer.value = transfers.items.find((t) => t.status === 'DRAFT' || t.status === 'PENDING') ?? null
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

async function submit() {
  const current = batch.value
  if (!current || submitting.value) return
  fieldError.value = ''
  submitError.value = ''
  const receiverOrgId = Number(form.receiverOrgId)
  if (!form.receiverOrgId || !Number.isInteger(receiverOrgId)) {
    fieldError.value = '请选择接收企业'
    return
  }
  submitting.value = true
  try {
    const created = await writer.run(
      `create-transfer:${current.id}:${receiverOrgId}`,
      () => ({ batchId: current.id, receiverOrgId }),
      (payload, key) => createTransfer(payload, key)
    )
    setPageFlash('shipment-create', {
      tone: 'success',
      message: `交接草稿 ${created.transferNo} 已创建（DRAFT）。下一步：创建运输任务并装载该交接。`
    })
    await router.push({ path: '/app/shipments/new', query: { transferId: String(created.id) } })
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    submitError.value = describeWriteError(err, '创建交接草稿')
    if (err instanceof ApiError && err.status === 409) await load()
  } finally {
    submitting.value = false
  }
}

watch(() => props.id, load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="transfer-create-page">
    <RouterLink :to="`/app/batches/${id}`" class="ent-button ent-back-link" data-testid="back-to-batch">← 返回批次详情</RouterLink>

    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">发起交接</h1>
        <p class="ent-page-subtitle">
          交接只表达责任交接；物理运输由运输任务承担。创建后为草稿（DRAFT），需装载到运输任务并提交，
          承运商到达后由接收方接受，接受后当前责任组织才会改变。
        </p>
      </div>
    </div>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="transfer-create-loading">正在加载…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert">未找到该批次。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="transfer-create-forbidden">
      该批次当前不由本组织负责，不能发起交接。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="batch">
      <section class="ent-card" aria-labelledby="transfer-batch-title">
        <h2 id="transfer-batch-title" class="ent-card-title">交接批次</h2>
        <dl class="ent-dl">
          <dt>追溯批次号</dt>
          <dd class="mono" data-testid="transfer-batch-no">{{ batch.traceBatchNo }}</dd>
          <dt>交接数量</dt>
          <dd data-testid="transfer-batch-quantity">{{ formatQuantity(batch.quantity, batch.unitCode) }}（整批交接，数量不因交接改变）</dd>
        </dl>
      </section>

      <div v-if="openTransfer" class="ent-card" role="status" data-testid="open-transfer">
        <p>
          该批次已有未结束交接
          <strong class="mono">{{ openTransfer.transferNo }}</strong>
          <StatusBadge :info="formatTransferStatus(openTransfer.status)" dimension="交接" />，
          同一批次同时只能有一张未结束交接。
        </p>
        <div class="ent-actions">
          <RouterLink
            v-if="openTransfer.shipmentId"
            :to="`/app/shipments/${openTransfer.shipmentId}`"
            class="ent-button ent-primary"
            data-testid="open-transfer-shipment"
          >
            查看运输任务 {{ openTransfer.shipmentNo }}
          </RouterLink>
          <RouterLink
            v-else
            :to="{ path: '/app/shipments/new', query: { transferId: String(openTransfer.id) } }"
            class="ent-button ent-primary"
            data-testid="open-transfer-create-shipment"
          >
            下一步：创建运输任务并装载
          </RouterLink>
        </div>
      </div>

      <div v-else-if="!allowed" class="ent-card ent-state error" role="alert" data-testid="transfer-create-not-allowed">
        只有当前责任组织的操作员可以对“可流转 / 正常”的批次发起交接。
      </div>

      <form v-else class="ent-card" novalidate data-testid="transfer-create-form" @submit.prevent="submit">
        <div class="ent-form-grid">
          <label class="ent-field wide">
            <span>接收企业 <em>*</em></span>
            <select v-model="form.receiverOrgId" data-testid="field-receiver" :aria-invalid="Boolean(fieldError)">
              <option value="">请选择接收企业</option>
              <option v-for="org in receivers" :key="org.id" :value="String(org.id)">
                {{ org.name }}（{{ formatOrgType(org.orgType) }} · {{ org.orgNo }}）
              </option>
            </select>
            <small v-if="fieldError" class="field-error" data-testid="error-receiver">{{ fieldError }}</small>
            <small v-else-if="receivers.length === 0">当前没有可选的接收企业。</small>
          </label>
        </div>
        <p v-if="submitError" class="ent-flash error" role="alert" data-testid="transfer-create-error">{{ submitError }}</p>
        <div class="ent-actions">
          <button type="submit" class="ent-button ent-primary" :disabled="submitting" data-testid="create-transfer">
            {{ submitting ? '创建中…' : '创建交接草稿' }}
          </button>
        </div>
      </form>
    </template>
  </div>
</template>

<style scoped>
.field-error {
  color: var(--color-danger-text);
}
</style>
