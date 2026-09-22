<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { arriveShipment, bindTransfer, cancelShipment, dispatchShipment, getShipment, unbindTransfer } from '@/api/shipments'
import { listTransfers, submitTransfer } from '@/api/transfers'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { Shipment, ShipmentTransferItem, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatIsoDateTime,
  formatOrgType,
  formatQuantity,
  formatShipmentStatus,
  formatSiteType,
  formatTransferStatus
} from '@/utils/formatters'
import { shipmentRoleOf } from '@/utils/permissions'
import { takePageFlash, type PageFlash } from './pageFlash'

const props = defineProps<{ id: string }>()

const { user } = useSession()
const writer = useIdempotentWrite()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const shipment = ref<Shipment | null>(null)
const bindableDrafts = ref<Transfer[]>([])
const bindChoice = ref('')
const cancelReason = ref('')
const busy = ref<string | null>(null)
const actionError = ref('')
const flash = ref<PageFlash | null>(null)

const role = computed(() => shipmentRoleOf(user.value, shipment.value))
const isOperator = computed(() => Boolean(user.value?.roles.includes('OPERATOR')))
const isPlanned = computed(() => shipment.value?.status === 'PLANNED')
const allPending = computed(() => Boolean(shipment.value && shipment.value.transfers.length > 0
  && shipment.value.transfers.every((t) => t.status === 'PENDING')))
const allDraft = computed(() => Boolean(shipment.value && shipment.value.transfers.every((t) => t.status === 'DRAFT')))

const canEditManifest = computed(() => role.value === 'SENDER' && isOperator.value && isPlanned.value)
const canDispatch = computed(() => role.value === 'CARRIER' && isOperator.value && isPlanned.value)
const canArrive = computed(() => role.value === 'CARRIER' && isOperator.value && shipment.value?.status === 'IN_TRANSIT')

/** 下一步业务动作提示（完全由服务端返回的状态推导，不写死业务状态）。 */
const nextStep = computed<string | null>(() => {
  const s = shipment.value
  if (!s) return null
  const drafts = s.transfers.filter((t) => t.status === 'DRAFT').length
  switch (s.status) {
    case 'PLANNED':
      if (s.transfers.length === 0) {
        return role.value === 'SENDER' ? '装载清单为空：请装载至少一张交接草稿。' : '等待发货方装载交接。'
      }
      if (drafts > 0) {
        return role.value === 'SENDER'
          ? `还有 ${drafts} 张交接未提交：请逐张“提交交接”，全部提交后承运商才能发运。`
          : '等待发货方提交全部交接。'
      }
      return role.value === 'CARRIER'
        ? '全部交接已提交：请核对装载清单后“确认装载发运”。'
        : '全部交接已提交，等待承运商确认装载发运。'
    case 'IN_TRANSIT':
      return role.value === 'CARRIER'
        ? '运输中：货物到达目的场所后请“确认到达”。'
        : '运输中：批次责任组织仍为发货方，等待承运商确认到达。'
    case 'DELIVERED':
      return role.value === 'RECEIVER'
        ? '运输任务已到达：请前往“待接收交接”逐张接受或拒收。'
        : '运输任务已到达，等待接收方接受或拒收交接；到达本身不改变责任组织。'
    case 'CANCELLED':
      return '运输任务已取消。'
    default:
      return null
  }
})

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  loadError.value = ''
  const shipmentId = Number(props.id)
  if (!Number.isInteger(shipmentId) || shipmentId <= 0) {
    loadState.value = 'not-found'
    return
  }
  try {
    const result = await getShipment(shipmentId, current.signal)
    if (current.signal.aborted) return
    shipment.value = result
    loadState.value = 'loaded'
    if (shipmentRoleOf(user.value, result) === 'SENDER' && result.status === 'PLANNED') {
      const page = await listTransfers({ direction: 'SENT', status: 'DRAFT', page: 1, size: 100 }, current.signal)
      if (current.signal.aborted) return
      bindableDrafts.value = page.items.filter((t) => !t.shipmentId && t.receiverOrgId === result.receiverOrg.id)
    } else {
      bindableDrafts.value = []
    }
  } catch (err: unknown) {
    if (current.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) loadState.value = 'not-found'
    else if (err instanceof ApiError && err.status === 403) loadState.value = 'forbidden'
    else {
      loadState.value = 'error'
      loadError.value = err instanceof ApiError ? err.message : '运输任务加载失败，请稍后重试'
    }
  }
}

async function act(label: string, name: string, action: () => Promise<unknown>, successMessage: string) {
  if (busy.value) return
  busy.value = name
  actionError.value = ''
  flash.value = null
  try {
    await action()
    flash.value = { tone: 'success', message: successMessage }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    actionError.value = describeWriteError(err, label)
  } finally {
    busy.value = null
    await load()
  }
}

function nowIso(): string {
  return new Date().toISOString()
}

function submitOne(item: ShipmentTransferItem) {
  return act('提交交接', `submit-${item.transferId}`, () => writer.run(
    `submit:${item.transferId}:${item.version}`,
    () => item.version,
    (version, key) => submitTransfer(item.transferId, version, key)
  ), `交接 ${item.transferNo} 已提交（PENDING）。`)
}

function unbindOne(item: ShipmentTransferItem) {
  return act('移出装载清单', `unbind-${item.transferId}`,
    () => unbindTransfer(Number(props.id), item.transferId, item.version),
    `交接 ${item.transferNo} 已移出装载清单，仍为草稿。`)
}

function bindSelected() {
  const transfer = bindableDrafts.value.find((t) => String(t.id) === bindChoice.value)
  if (!transfer) return
  return act('装载交接', 'bind', () => writer.run(
    `bind:${props.id}:${transfer.id}:${transfer.version}`,
    () => ({ transferId: transfer.id, expectedTransferVersion: transfer.version }),
    (body, key) => bindTransfer(Number(props.id), body, key)
  ), `交接 ${transfer.transferNo} 已装载。`).then(() => { bindChoice.value = '' })
}

function dispatch() {
  const s = shipment.value
  if (!s) return
  return act('确认装载发运', 'dispatch', () => writer.run(
    `dispatch:${s.id}:${s.version}`,
    () => ({ loadedAt: nowIso(), expectedVersion: s.version }),
    (body, key) => dispatchShipment(s.id, body, key)
  ), `已确认装载发运，运输任务进入运输中；系统已为 ${s.transfers.length} 个批次各生成一条 TRANSPORT 追溯事件。`)
}

function arrive() {
  const s = shipment.value
  if (!s) return
  return act('确认到达', 'arrive', () => writer.run(
    `arrive:${s.id}:${s.version}`,
    () => ({ unloadedAt: nowIso(), expectedVersion: s.version }),
    (body, key) => arriveShipment(s.id, body, key)
  ), `已确认到达；系统已为 ${s.transfers.length} 个批次各生成一条 ARRIVAL 追溯事件。交接仍待接收方接受或拒收。`)
}

function cancel() {
  const s = shipment.value
  const reason = cancelReason.value.trim()
  if (!s) return
  if (!reason) {
    actionError.value = '请填写取消原因'
    return
  }
  return act('取消运输任务', 'cancel', () => writer.run(
    `cancel:${s.id}:${s.version}:${reason}`,
    () => ({ reason, expectedVersion: s.version }),
    (body, key) => cancelShipment(s.id, body, key)
  ), '运输任务已取消，装载的交接草稿已解绑。')
}

watch(() => props.id, (id) => {
  flash.value = takePageFlash(`shipment:${id}`)
  actionError.value = ''
  load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="shipment-detail-page">
    <RouterLink to="/app/shipments" class="ent-button ent-back-link" data-testid="back-to-shipments">← 返回运输任务</RouterLink>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="shipment-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading' && !shipment" class="ent-card ent-state" data-testid="shipment-loading">正在加载运输任务…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert" data-testid="shipment-not-found">未找到该运输任务。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="shipment-forbidden">
      只有运输任务的发货方、承运方与接收方可以查看。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="shipment">
      <div class="ent-page-header">
        <div>
          <p class="ent-page-subtitle">运输单号</p>
          <h1 class="ent-page-title mono" data-testid="shipment-no">{{ shipment.shipmentNo }}</h1>
        </div>
        <StatusBadge :info="formatShipmentStatus(shipment.status)" dimension="运输" data-testid="shipment-status" :data-status="shipment.status" />
      </div>

      <p v-if="nextStep" class="ent-next-step" data-testid="shipment-next-step"><strong>下一步：</strong>{{ nextStep }}</p>
      <p v-if="actionError" class="ent-flash error" role="alert" data-testid="shipment-action-error">{{ actionError }}</p>

      <section v-if="canDispatch || canArrive" class="ent-card" aria-labelledby="carrier-actions-title" data-testid="carrier-actions">
        <h2 id="carrier-actions-title" class="ent-card-title">承运操作</h2>
        <p class="section-note">承运商只负责物理运输：确认装载、发运与到达，不能接受交接，也不会成为批次责任组织。</p>
        <div class="ent-actions">
          <button
            v-if="canDispatch"
            type="button"
            class="ent-button ent-primary"
            data-testid="dispatch-shipment"
            :disabled="Boolean(busy) || !allPending"
            @click="dispatch"
          >
            {{ busy === 'dispatch' ? '发运中…' : '确认装载发运' }}
          </button>
          <small v-if="canDispatch && !allPending" class="ent-muted">装载清单中的交接全部提交后才能发运。</small>
          <button
            v-if="canArrive"
            type="button"
            class="ent-button ent-primary"
            data-testid="arrive-shipment"
            :disabled="Boolean(busy)"
            @click="arrive"
          >
            {{ busy === 'arrive' ? '确认中…' : '确认到达' }}
          </button>
        </div>
      </section>

      <section v-if="role === 'RECEIVER' && shipment.status === 'DELIVERED'" class="ent-card" data-testid="receiver-actions">
        <h2 class="ent-card-title">接收方操作</h2>
        <RouterLink to="/app/transfers/inbound" class="ent-button ent-primary" data-testid="go-inbound">前往待接收交接</RouterLink>
      </section>

      <div class="detail-grid">
        <section class="ent-card" aria-labelledby="parties-title">
          <h2 id="parties-title" class="ent-card-title">参与方</h2>
          <dl class="ent-dl">
            <dt>发货方（运输期间责任组织）</dt>
            <dd data-testid="shipment-sender">{{ shipment.senderOrg.name }}</dd>
            <dt>承运方</dt>
            <dd data-testid="shipment-carrier">{{ shipment.carrierOrg.name }}（{{ formatOrgType(shipment.carrierOrg.orgType) }}）</dd>
            <dt>接收方</dt>
            <dd data-testid="shipment-receiver">{{ shipment.receiverOrg.name }}</dd>
            <dt>车辆 / 容器</dt>
            <dd class="mono">{{ shipment.vehicleOrContainerNo }}</dd>
          </dl>
        </section>
        <section class="ent-card" aria-labelledby="route-title">
          <h2 id="route-title" class="ent-card-title">路线与时间</h2>
          <dl class="ent-dl">
            <dt>启运场所</dt>
            <dd data-testid="shipment-origin">{{ shipment.originSite?.name }}（{{ formatSiteType(shipment.originSite?.siteType) }}）</dd>
            <dt>目的场所</dt>
            <dd data-testid="shipment-destination">{{ shipment.destinationSite?.name }}（{{ formatSiteType(shipment.destinationSite?.siteType) }}）</dd>
            <dt>装载发运时间</dt>
            <dd data-testid="shipment-loaded-at">{{ shipment.loadedAt ? formatIsoDateTime(shipment.loadedAt) : '尚未发运' }}</dd>
            <dt>到达时间</dt>
            <dd data-testid="shipment-unloaded-at">{{ shipment.unloadedAt ? formatIsoDateTime(shipment.unloadedAt) : '尚未到达' }}</dd>
            <template v-if="shipment.cancelReason">
              <dt>取消原因</dt>
              <dd>{{ shipment.cancelReason }}</dd>
            </template>
          </dl>
        </section>
      </div>

      <section class="ent-card" aria-labelledby="manifest-title" data-testid="shipment-manifest">
        <h2 id="manifest-title" class="ent-card-title">装载清单（{{ shipment.transfers.length }} 张交接）</h2>
        <div v-if="shipment.transfers.length === 0" class="ent-state" data-testid="manifest-empty">尚未装载任何交接。</div>
        <div v-else class="ent-table-scroll">
          <table class="ent-table">
            <thead>
              <tr><th>交接单号</th><th>追溯批次号</th><th>数量</th><th>交接状态</th><th v-if="canEditManifest">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="item in shipment.transfers" :key="item.transferId" data-testid="manifest-row" :data-transfer-id="item.transferId">
                <td class="mono">{{ item.transferNo }}</td>
                <td class="mono">
                  <RouterLink v-if="role === 'SENDER'" :to="`/app/batches/${item.batchId}`">{{ item.traceBatchNo }}</RouterLink>
                  <span v-else>{{ item.traceBatchNo }}</span>
                </td>
                <td>{{ formatQuantity(item.quantity, item.unitCode) }}</td>
                <td>
                  <StatusBadge :info="formatTransferStatus(item.status)" dimension="交接" data-testid="manifest-transfer-status" :data-status="item.status" />
                </td>
                <td v-if="canEditManifest">
                  <div class="row-actions">
                    <button
                      v-if="item.status === 'DRAFT'"
                      type="button"
                      class="ent-button ent-primary"
                      :data-testid="`submit-transfer-${item.transferId}`"
                      :disabled="Boolean(busy)"
                      @click="submitOne(item)"
                    >
                      {{ busy === `submit-${item.transferId}` ? '提交中…' : '提交交接' }}
                    </button>
                    <button
                      v-if="item.status === 'DRAFT'"
                      type="button"
                      class="ent-button"
                      :data-testid="`unbind-transfer-${item.transferId}`"
                      :disabled="Boolean(busy)"
                      @click="unbindOne(item)"
                    >
                      移出
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="canEditManifest && bindableDrafts.length > 0" class="bind-row" data-testid="bind-more">
          <label class="ent-field">
            <span>继续装载同一接收方的交接草稿</span>
            <select v-model="bindChoice" data-testid="field-bind-transfer">
              <option value="">请选择交接草稿</option>
              <option v-for="t in bindableDrafts" :key="t.id" :value="String(t.id)">{{ t.transferNo }} · {{ t.traceBatchNo }}</option>
            </select>
          </label>
          <button type="button" class="ent-button" :disabled="!bindChoice || Boolean(busy)" data-testid="bind-transfer" @click="bindSelected">装载</button>
        </div>
      </section>

      <section v-if="canEditManifest && allDraft" class="ent-card" aria-labelledby="cancel-title" data-testid="cancel-shipment-card">
        <h2 id="cancel-title" class="ent-card-title">取消运输任务</h2>
        <p class="section-note">仅当装载清单中的交接全部仍为草稿时可以取消；取消后这些草稿会被解绑。</p>
        <div class="bind-row">
          <label class="ent-field">
            <span>取消原因</span>
            <input v-model="cancelReason" maxlength="500" data-testid="field-cancel-reason" />
          </label>
          <button type="button" class="ent-button ent-danger" :disabled="Boolean(busy)" data-testid="cancel-shipment" @click="cancel">取消运输任务</button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 0 16px;
}
.row-actions {
  display: flex;
  gap: 6px;
}
.bind-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 14px;
}
.bind-row .ent-field {
  min-width: 260px;
  flex: 1;
}
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
