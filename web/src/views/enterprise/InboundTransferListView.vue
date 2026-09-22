<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { acceptTransfer, listTransfers, rejectTransfer } from '@/api/transfers'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatIsoDateTime, formatQuantity, formatShipmentStatus, formatTransferStatus } from '@/utils/formatters'
import { canDecideTransfer } from '@/utils/permissions'
import { setBatchFlash } from './batchFlash'
import { takePageFlash, type PageFlash } from './pageFlash'

const router = useRouter()
const { user } = useSession()
const directory = useDirectoryLabels()
const writer = useIdempotentWrite()

const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref('')
const pending = ref<Transfer[]>([])
const flash = ref<PageFlash | null>(takePageFlash('inbound'))

interface DecisionForm {
  receivedQuantity: string
  differenceReason: string
  rejectReason: string
  mode: 'none' | 'reject'
  busy: boolean
  error: string
}
const forms = reactive(new Map<number, DecisionForm>())

function formFor(t: Transfer): DecisionForm {
  let f = forms.get(t.id)
  if (!f) {
    f = { receivedQuantity: String(t.quantity), differenceReason: '', rejectReason: '', mode: 'none', busy: false, error: '' }
    forms.set(t.id, f)
  }
  return f
}

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const page = await listTransfers({ direction: 'RECEIVED', status: 'PENDING', page: 1, size: 100 }, current.signal)
    if (current.signal.aborted) return
    pending.value = page.items
    directory.resolveOrganizations(page.items.map((t) => t.senderOrgId))
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '待接收交接加载失败，请稍后重试'
  }
}

function quantityDiffers(t: Transfer, f: DecisionForm): boolean {
  return Number(f.receivedQuantity) !== Number(t.quantity)
}

async function accept(t: Transfer) {
  const f = formFor(t)
  if (f.busy) return
  f.error = ''
  const qty = Number(f.receivedQuantity)
  if (!/^\d{1,15}(\.\d{1,3})?$/.test(f.receivedQuantity.trim()) || qty <= 0) {
    f.error = '实收数量必须为大于 0 的数字，最多 3 位小数'
    return
  }
  const reason = f.differenceReason.trim()
  if (quantityDiffers(t, f) && !reason) {
    f.error = '实收数量与交接数量不一致，必须填写差异原因'
    return
  }
  f.busy = true
  try {
    await writer.run(
      `accept:${t.id}:${t.version}:${qty}:${reason}`,
      () => ({
        receivedQuantity: qty,
        unitCode: t.unitCode,
        occurredAt: new Date().toISOString(),
        differenceReason: quantityDiffers(t, f) ? reason : undefined,
        expectedVersion: t.version
      }),
      (body, key) => acceptTransfer(t.id, body, key)
    )
    setBatchFlash(t.batchId, {
      tone: 'success',
      message: `已接受交接 ${t.transferNo}：本组织已成为该批次的当前责任组织。`
    })
    await router.push(`/app/batches/${t.batchId}`)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    f.error = describeWriteError(err, '接受交接')
    if (err instanceof ApiError && err.status === 409) await load()
  } finally {
    f.busy = false
  }
}

async function reject(t: Transfer) {
  const f = formFor(t)
  if (f.busy) return
  f.error = ''
  const reason = f.rejectReason.trim()
  if (!reason) {
    f.error = '请填写拒收原因'
    return
  }
  f.busy = true
  try {
    await writer.run(
      `reject:${t.id}:${t.version}:${reason}`,
      () => ({ reason, occurredAt: new Date().toISOString(), expectedVersion: t.version }),
      (body, key) => rejectTransfer(t.id, body, key)
    )
    flash.value = { tone: 'success', message: `已拒收交接 ${t.transferNo}，批次仍由发送方负责。` }
    forms.delete(t.id)
    await load()
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    f.error = describeWriteError(err, '拒收交接')
    if (err instanceof ApiError && err.status === 409) await load()
  } finally {
    f.busy = false
  }
}

load()
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="inbound-page">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">待接收交接</h1>
        <p class="ent-page-subtitle">
          发往本组织、已提交的交接。运输任务确认到达（DELIVERED）后才能接受或拒收；只有接受会把批次当前责任组织转为本组织。
        </p>
      </div>
    </div>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="inbound-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="inbound-loading">正在加载待接收交接…</div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>
    <div v-else-if="pending.length === 0" class="ent-card ent-state" data-testid="inbound-empty">当前没有待接收的交接。</div>

    <template v-else>
      <section
        v-for="t in pending"
        :key="t.id"
        class="ent-card"
        data-testid="inbound-transfer"
        :data-transfer-id="t.id"
      >
        <div class="ent-page-header card-head">
          <h2 class="ent-card-title mono">{{ t.transferNo }}</h2>
          <StatusBadge :info="formatTransferStatus(t.status)" dimension="交接" />
        </div>
        <dl class="ent-dl">
          <dt>追溯批次号</dt>
          <dd class="mono" data-testid="inbound-trace-batch-no">{{ t.traceBatchNo }}</dd>
          <dt>发送企业</dt>
          <dd>{{ directory.organizationLabel(t.senderOrgId) }}</dd>
          <dt>交接数量</dt>
          <dd>{{ formatQuantity(t.quantity, t.unitCode) }}</dd>
          <dt>运输任务</dt>
          <dd>
            <RouterLink v-if="t.shipmentId" :to="`/app/shipments/${t.shipmentId}`" class="mono" data-testid="inbound-shipment-link">{{ t.shipmentNo }}</RouterLink>
            <StatusBadge v-if="t.shipmentStatus" :info="formatShipmentStatus(t.shipmentStatus)" dimension="运输" data-testid="inbound-shipment-status" :data-status="t.shipmentStatus" />
          </dd>
          <dt>提交时间</dt>
          <dd>{{ formatIsoDateTime(t.submittedRecordedAt) }}</dd>
        </dl>

        <p v-if="t.shipmentStatus !== 'DELIVERED'" class="ent-next-step" data-testid="inbound-waiting">
          <strong>等待到达：</strong>运输任务当前为“{{ formatShipmentStatus(t.shipmentStatus).label }}”，承运商确认到达后才能接受或拒收。
        </p>

        <template v-else-if="canDecideTransfer(user, t)">
          <p class="ent-next-step"><strong>下一步：</strong>货物已到达，请核对实收数量后接受，或填写原因拒收。</p>
          <div class="ent-form-grid">
            <label class="ent-field">
              <span>实收数量（{{ t.unitCode }}）<em>*</em></span>
              <input v-model="formFor(t).receivedQuantity" inputmode="decimal" :data-testid="`received-quantity-${t.id}`" />
            </label>
            <label v-if="quantityDiffers(t, formFor(t))" class="ent-field">
              <span>数量差异原因 <em>*</em></span>
              <input v-model="formFor(t).differenceReason" maxlength="500" :data-testid="`difference-reason-${t.id}`" />
            </label>
          </div>
          <div v-if="formFor(t).mode === 'reject'" class="ent-form-grid reject-grid">
            <label class="ent-field wide">
              <span>拒收原因 <em>*</em></span>
              <input v-model="formFor(t).rejectReason" maxlength="500" :data-testid="`reject-reason-${t.id}`" />
            </label>
          </div>
          <p v-if="formFor(t).error" class="ent-flash error" role="alert" :data-testid="`inbound-error-${t.id}`">{{ formFor(t).error }}</p>
          <div class="ent-actions">
            <button
              type="button"
              class="ent-button ent-primary"
              :disabled="formFor(t).busy"
              :data-testid="`accept-transfer-${t.id}`"
              @click="accept(t)"
            >
              {{ formFor(t).busy ? '处理中…' : '接受交接' }}
            </button>
            <button
              v-if="formFor(t).mode !== 'reject'"
              type="button"
              class="ent-button ent-danger"
              :disabled="formFor(t).busy"
              :data-testid="`start-reject-${t.id}`"
              @click="formFor(t).mode = 'reject'"
            >
              拒收…
            </button>
            <button
              v-else
              type="button"
              class="ent-button ent-danger"
              :disabled="formFor(t).busy"
              :data-testid="`reject-transfer-${t.id}`"
              @click="reject(t)"
            >
              确认拒收
            </button>
          </div>
        </template>
        <p v-else class="ent-muted">当前账号没有接受或拒收交接的角色。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.card-head {
  margin-bottom: 8px;
  align-items: center;
}
.card-head .ent-card-title {
  margin: 0;
}
.reject-grid {
  margin-top: 12px;
}
</style>
