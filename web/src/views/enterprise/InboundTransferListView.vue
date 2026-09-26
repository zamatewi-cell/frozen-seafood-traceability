<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { listSites } from '@/api/directory'
import { acceptTransfer, listTransfers, quarantineTransfer, rejectTransfer } from '@/api/transfers'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { SiteSummary, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatIsoDateTime,
  formatQuantity,
  formatRiskStatus,
  formatShipmentStatus,
  formatSiteType,
  formatTransferStatus
} from '@/utils/formatters'
import { canAcceptTransfer, canDecideTransfer, canQuarantineTransfer } from '@/utils/permissions'
import { setBatchFlash } from './batchFlash'
import { takePageFlash, type PageFlash } from './pageFlash'

/**
 * 待接收交接（Phase A Slice 2 起；PB4 起含隔离收货）：发往本组织的 PENDING 与 QUARANTINED 交接。
 * 运输任务到达后接收方：批次风险正常时可以接受；批次被冻结（例如在途持续超温告警）时不能接受，只能隔离收货或拒收；
 * 隔离后等待发货方质量管理员依据检验结论放行，批次恢复正常后接受（沿用隔离登记的实收数量），或随时拒收。
 * 隔离不改变批次责任组织、数量与风险状态，不生成追溯事件。服务端是最终权限边界。
 */
const router = useRouter()
const { user } = useSession()
const directory = useDirectoryLabels()
const writer = useIdempotentWrite()

const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref('')
const pending = ref<Transfer[]>([])
const flash = ref<PageFlash | null>(takePageFlash('inbound'))
const sites = ref<SiteSummary[]>([])

type Mode = 'none' | 'reject' | 'quarantine'

interface DecisionForm {
  receivedQuantity: string
  differenceReason: string
  rejectReason: string
  quarantineSiteId: string
  quarantineReason: string
  mode: Mode
  confirming: boolean
  busy: boolean
  error: string
}
const forms = reactive(new Map<number, DecisionForm>())

function formFor(t: Transfer): DecisionForm {
  let f = forms.get(t.id)
  if (!f) {
    f = {
      receivedQuantity: String(t.receivedQuantity ?? t.quantity), differenceReason: '', rejectReason: '',
      quarantineSiteId: '', quarantineReason: '', mode: 'none', confirming: false, busy: false, error: ''
    }
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
    const [open, quarantined] = await Promise.all([
      listTransfers({ direction: 'RECEIVED', status: 'PENDING', page: 1, size: 100 }, current.signal),
      listTransfers({ direction: 'RECEIVED', status: 'QUARANTINED', page: 1, size: 100 }, current.signal)
    ])
    if (current.signal.aborted) return
    pending.value = [...quarantined.items, ...open.items]
    directory.resolveOrganizations(pending.value.map((t) => t.senderOrgId))
    loadState.value = 'loaded'
    if (user.value && sites.value.length === 0 && pending.value.length > 0) {
      listSites(user.value.orgId, current.signal)
        .then((all) => { if (!current.signal.aborted) sites.value = all.filter((s) => s.status === 'ACTIVE') })
        .catch(() => { /* 场所目录不可用时隔离表单提示无可选场所 */ })
    }
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '待接收交接加载失败，请稍后重试'
  }
}

function quantityDiffers(t: Transfer, f: DecisionForm): boolean {
  return Number(f.receivedQuantity) !== Number(t.quantity)
}

function frozen(t: Transfer): boolean {
  return Boolean(t.batchRiskStatus && t.batchRiskStatus !== 'NORMAL')
}

function siteLabel(id: number | undefined): string {
  const site = sites.value.find((s) => s.id === id)
  return site ? `${site.name}（${formatSiteType(site.siteType)}）` : id ? `场所 #${id}` : '—'
}

function validQuantity(f: DecisionForm): boolean {
  return /^\d{1,15}(\.\d{1,3})?$/.test(f.receivedQuantity.trim()) && Number(f.receivedQuantity) > 0
}

async function accept(t: Transfer) {
  const f = formFor(t)
  if (f.busy) return
  f.error = ''
  // 隔离交接沿用隔离登记的实收事实：数量固定为登记值，不重复填写差异原因
  const fromQuarantine = t.status === 'QUARANTINED'
  const qty = fromQuarantine ? Number(t.receivedQuantity) : Number(f.receivedQuantity)
  if (!fromQuarantine && !validQuantity(f)) {
    f.error = '实收数量必须为大于 0 的数字，最多 3 位小数'
    return
  }
  const reason = f.differenceReason.trim()
  if (!fromQuarantine && quantityDiffers(t, f) && !reason) {
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
        differenceReason: !fromQuarantine && quantityDiffers(t, f) ? reason : undefined,
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

function startQuarantine(t: Transfer) {
  const f = formFor(t)
  f.mode = 'quarantine'
  f.confirming = false
  f.error = ''
  if (!f.quarantineSiteId && sites.value.length > 0) f.quarantineSiteId = String(sites.value[0].id)
}

function nextQuarantine(t: Transfer) {
  const f = formFor(t)
  f.error = ''
  if (!validQuantity(f)) f.error = '实收数量必须为大于 0 的数字，最多 3 位小数'
  else if (quantityDiffers(t, f) && !f.differenceReason.trim()) f.error = '实收数量与交接数量不一致，必须填写差异原因'
  else if (!f.quarantineSiteId) f.error = '请选择本组织的隔离场所'
  else if (!f.quarantineReason.trim()) f.error = '请填写隔离原因'
  if (!f.error) f.confirming = true
}

async function quarantine(t: Transfer) {
  const f = formFor(t)
  if (f.busy) return
  f.busy = true
  f.error = ''
  const qty = Number(f.receivedQuantity)
  const reason = f.quarantineReason.trim()
  const diff = quantityDiffers(t, f) ? f.differenceReason.trim() : ''
  try {
    await writer.run(
      `quarantine:${t.id}:${t.version}:${qty}:${diff}:${f.quarantineSiteId}:${reason}`,
      () => ({
        receivedQuantity: qty,
        unitCode: t.unitCode,
        occurredAt: new Date().toISOString(),
        differenceReason: diff || undefined,
        quarantineSiteId: Number(f.quarantineSiteId),
        reason,
        expectedVersion: t.version
      }),
      (body, key) => quarantineTransfer(t.id, body, key)
    )
    flash.value = {
      tone: 'success',
      message: `已隔离收货 ${t.transferNo}：批次仍由发送方负责，等待发货方依据检验结论放行后再接受，或拒收。`
    }
    forms.delete(t.id)
    await load()
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    f.error = describeWriteError(err, '隔离收货')
    f.confirming = false
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
          发往本组织、已提交的交接与隔离收货中的交接。运输任务确认到达（DELIVERED）后才能接受、隔离收货或拒收；只有接受会把批次当前责任组织转为本组织。
          风险冻结的批次不能直接接受，只能隔离收货或拒收。
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
        :data-status="t.status"
      >
        <div class="ent-page-header card-head">
          <h2 class="ent-card-title mono">{{ t.transferNo }}</h2>
          <StatusBadge :info="formatTransferStatus(t.status)" dimension="交接" data-testid="inbound-transfer-status" :data-status="t.status" />
        </div>
        <dl class="ent-dl">
          <dt>追溯批次号</dt>
          <dd class="mono" data-testid="inbound-trace-batch-no">{{ t.traceBatchNo }}</dd>
          <dt>批次风险状态</dt>
          <dd>
            <StatusBadge :info="formatRiskStatus(t.batchRiskStatus)" dimension="风险" data-testid="inbound-batch-risk" :data-status="t.batchRiskStatus" />
          </dd>
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
          <template v-if="t.status === 'QUARANTINED'">
            <dt>实收数量</dt>
            <dd data-testid="inbound-received-quantity">{{ formatQuantity(t.receivedQuantity, t.unitCode) }}<span v-if="t.differenceReason">（差异原因：{{ t.differenceReason }}）</span></dd>
            <dt>隔离场所</dt>
            <dd data-testid="inbound-quarantine-site">{{ siteLabel(t.quarantineSiteId) }}</dd>
            <dt>隔离原因</dt>
            <dd data-testid="inbound-quarantine-reason">{{ t.quarantineReason }}</dd>
          </template>
        </dl>

        <p v-if="t.shipmentStatus !== 'DELIVERED'" class="ent-next-step" data-testid="inbound-waiting">
          <strong>等待到达：</strong>运输任务当前为“{{ formatShipmentStatus(t.shipmentStatus).label }}”，承运商确认到达后才能接受、隔离收货或拒收。
        </p>

        <template v-else-if="canDecideTransfer(user, t)">
          <p v-if="t.status === 'QUARANTINED' && frozen(t)" class="ent-next-step" data-testid="inbound-quarantine-waiting">
            <strong>隔离中：</strong>批次仍处于风险冻结，责任组织仍为发送方；可在
            <RouterLink :to="`/app/alerts?status=ACKNOWLEDGED`">告警</RouterLink>中为该批次提交检验证据，
            等待发货方质量管理员依据检验结论放行后再接受，或填写原因拒收。
          </p>
          <p v-else-if="t.status === 'QUARANTINED'" class="ent-next-step" data-testid="inbound-quarantine-ready">
            <strong>下一步：</strong>批次风险已恢复正常，可以按隔离登记的实收数量接受，或填写原因拒收。
          </p>
          <p v-else-if="frozen(t)" class="ent-flash warning" role="status" data-testid="inbound-frozen-warning">
            批次当前为“{{ formatRiskStatus(t.batchRiskStatus).label }}”，不能直接接受：请隔离收货（登记实收数量与隔离场所）或拒收。
          </p>
          <p v-else class="ent-next-step"><strong>下一步：</strong>货物已到达，请核对实收数量后接受；需要复核时可以隔离收货，或填写原因拒收。</p>

          <div v-if="t.status === 'PENDING' && formFor(t).mode !== 'reject'" class="ent-form-grid">
            <label class="ent-field">
              <span>实收数量（{{ t.unitCode }}）<em>*</em></span>
              <input v-model="formFor(t).receivedQuantity" inputmode="decimal" :disabled="formFor(t).confirming" :data-testid="`received-quantity-${t.id}`" />
            </label>
            <label v-if="quantityDiffers(t, formFor(t))" class="ent-field">
              <span>数量差异原因 <em>*</em></span>
              <input v-model="formFor(t).differenceReason" maxlength="500" :disabled="formFor(t).confirming" :data-testid="`difference-reason-${t.id}`" />
            </label>
          </div>
          <div v-if="formFor(t).mode === 'quarantine'" class="ent-form-grid reject-grid" :data-testid="`quarantine-form-${t.id}`">
            <label class="ent-field">
              <span>隔离场所 <em>*</em></span>
              <select v-model="formFor(t).quarantineSiteId" :disabled="formFor(t).confirming" :data-testid="`quarantine-site-${t.id}`">
                <option value="">请选择本组织场所</option>
                <option v-for="s in sites" :key="s.id" :value="String(s.id)">{{ s.name }}（{{ formatSiteType(s.siteType) }}）</option>
              </select>
            </label>
            <label class="ent-field wide">
              <span>隔离原因 <em>*</em></span>
              <input v-model="formFor(t).quarantineReason" maxlength="500" :disabled="formFor(t).confirming" :data-testid="`quarantine-reason-${t.id}`" />
            </label>
          </div>
          <div v-if="formFor(t).mode === 'reject'" class="ent-form-grid reject-grid">
            <label class="ent-field wide">
              <span>拒收原因 <em>*</em></span>
              <input v-model="formFor(t).rejectReason" maxlength="500" :data-testid="`reject-reason-${t.id}`" />
            </label>
          </div>
          <p v-if="formFor(t).error" class="ent-flash error" role="alert" :data-testid="`inbound-error-${t.id}`">{{ formFor(t).error }}</p>

          <div v-if="formFor(t).mode === 'quarantine' && formFor(t).confirming" class="ent-flash warning" role="alert" :data-testid="`quarantine-confirm-panel-${t.id}`">
            确认隔离收货？货物将登记为已到达并隔离在所选场所，批次责任组织仍为发送方，本组织不能加工、销售或再次交接；
            之后只能在批次风险恢复正常后接受，或拒收。
            <div class="ent-actions">
              <button type="button" class="ent-button ent-danger" :disabled="formFor(t).busy" :data-testid="`quarantine-confirm-${t.id}`" @click="quarantine(t)">
                {{ formFor(t).busy ? '处理中…' : '确认隔离收货' }}
              </button>
              <button type="button" class="ent-button" :disabled="formFor(t).busy" :data-testid="`quarantine-back-${t.id}`" @click="formFor(t).confirming = false">返回修改</button>
            </div>
          </div>

          <div v-else class="ent-actions">
            <button
              v-if="canAcceptTransfer(user, t) && formFor(t).mode === 'none'"
              type="button"
              class="ent-button ent-primary"
              :disabled="formFor(t).busy"
              :data-testid="`accept-transfer-${t.id}`"
              @click="accept(t)"
            >
              {{ formFor(t).busy ? '处理中…' : t.status === 'QUARANTINED' ? '接受（沿用隔离实收数量）' : '接受交接' }}
            </button>
            <template v-if="canQuarantineTransfer(user, t)">
              <button
                v-if="formFor(t).mode !== 'quarantine'"
                type="button"
                class="ent-button ent-danger"
                :disabled="formFor(t).busy"
                :data-testid="`start-quarantine-${t.id}`"
                @click="startQuarantine(t)"
              >
                隔离收货…
              </button>
              <template v-else>
                <button type="button" class="ent-button ent-danger" :disabled="formFor(t).busy" :data-testid="`quarantine-next-${t.id}`" @click="nextQuarantine(t)">
                  下一步：确认隔离
                </button>
                <button type="button" class="ent-button" :disabled="formFor(t).busy" :data-testid="`quarantine-cancel-${t.id}`" @click="formFor(t).mode = 'none'">
                  取消隔离
                </button>
              </template>
            </template>
            <button
              v-if="formFor(t).mode === 'none'"
              type="button"
              class="ent-button ent-danger"
              :disabled="formFor(t).busy"
              :data-testid="`start-reject-${t.id}`"
              @click="formFor(t).mode = 'reject'"
            >
              拒收…
            </button>
            <button
              v-if="formFor(t).mode === 'reject'"
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
        <p v-else class="ent-muted">当前账号没有接受、隔离收货或拒收交接的角色。</p>
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
