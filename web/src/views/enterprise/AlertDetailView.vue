<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import InspectionReportPanel from '@/components/enterprise/InspectionReportPanel.vue'
import RecallStartForm from '@/components/enterprise/RecallStartForm.vue'
import { acknowledgeAlert, getAlert, releaseAlertBatch, resolveAlert } from '@/api/alerts'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { Alert, AlertAffectedBatch } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatAlertAction,
  formatAlertStatus,
  formatAlertType,
  formatDurationSeconds,
  formatFlowStatus,
  formatInspectionConclusion,
  formatIsoDateTime,
  formatQuantity,
  formatRiskStatus,
  formatTemperature,
  formatTransferStatus
} from '@/utils/formatters'
import {
  canAcknowledgeAlert,
  canHandleAlert,
  canReleaseAlertBatch,
  canResolveAlert,
  canStartRecall,
  canSubmitInspection
} from '@/utils/permissions'

/**
 * 告警详情（Phase B PB3 / PB4；统一业务契约 v1.1 §2.11 / §10.2 / §10.3 / §13）：持续超温片段与判定依据快照、受影响批次
 * （自动冻结结果与当前状态）、处置历史；告警归属组织的质量管理员确认异常、依据关联本告警的最新检验结论放行受影响批次、
 * 全部批次处置后形成处置结论；当前责任组织与隔离收货方的质量管理员按批次提交检验证据。
 * 告警、冻结与放行是教学实训中的模拟质量处置，不代表真实监管措施。服务端是最终权限边界。
 */
const props = defineProps<{ id: string }>()

const { user } = useSession()
const writer = useIdempotentWrite()
const { resolveOrganizations, organizationLabel } = useDirectoryLabels()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const alert = ref<Alert | null>(null)
const flash = ref<{ tone: 'success' | 'warning'; message: string } | null>(null)
const actionError = ref('')
const busy = ref(false)

const ackOpen = ref(false)
const ackConfirming = ref(false)
const ackNote = ref('')
const NOTE_MAX = 500

const handler = computed(() => canHandleAlert(user.value, alert.value))
const canAck = computed(() => canAcknowledgeAlert(user.value, alert.value))
const canResolve = computed(() => canResolveAlert(user.value, alert.value))

/** 放行（按批次）与处置结论的二次确认表单。 */
const releasing = ref<number | null>(null)
const releaseConfirming = ref(false)
const releaseNote = ref('')
const resolveOpen = ref(false)
const resolveConfirming = ref(false)
const resolution = ref('')

function canRelease(b: AlertAffectedBatch): boolean {
  return canReleaseAlertBatch(user.value, alert.value, b)
}

/** 告警处置中，归属组织的质量管理员可以对仍冻结（或最新检验不合格）的受影响批次发起模拟召回（PB5）。 */
function canRecall(b: AlertAffectedBatch): boolean {
  return Boolean(alert.value && alert.value.status === 'ACKNOWLEDGED' && canHandleAlert(user.value, alert.value)
    && canStartRecall(user.value, b)
    && (b.currentRiskStatus === 'FROZEN' || b.latestInspectionConclusion === 'FAIL'))
}

function onRecallConflict(message: string) {
  flash.value = { tone: 'warning', message }
  load()
}

/** 批次的质量处置进展（由服务端返回的事实推导）。 */
/** 仍使批次保持冻结的其他风险事项（服务端只对批次当前责任组织输出明细）。 */
function holdsText(b: AlertAffectedBatch): string {
  const holds = b.pendingHolds ?? []
  if (holds.length === 0) return '其他风险事项'
  return holds.map((h) => (h.type === 'ALERT' ? `告警 ${h.alertNo}` : '人工风险冻结')).join('、')
}

function dispositionOf(b: AlertAffectedBatch): string {
  if (b.currentRiskStatus === 'RECALLED') return '已进入模拟召回'
  if (b.released && b.currentRiskStatus === 'FROZEN') return `已记录本告警的放行结论；批次仍被${holdsText(b)}冻结`
  if (b.released) return '已依据检验结论放行'
  if (b.latestInspectionConclusion === 'FAIL') return '最新检验不合格：可拒收或发起模拟召回'
  // 这里只有关联本告警的最新检验结论；放行时服务端还要求批次的最新检验报告（不论是否关联本告警）不是不合格
  if (b.latestInspectionConclusion === 'PASS') return '本告警关联检验合格：放行时还将核对批次最新检验报告'
  return '待提交检验证据'
}

/** 当前账号能否为该批次提交检验证据（当前责任组织，或隔离收货方）。 */
function canInspect(b: AlertAffectedBatch): boolean {
  const a = alert.value
  if (!a || a.status === 'RESOLVED') return false
  return canSubmitInspection(user.value, b.currentOrgId, b.transferStatus === 'QUARANTINED' ? a.receiverOrgId : null)
}
const isOwner = computed(() => Boolean(alert.value && user.value && alert.value.orgId === user.value.orgId))

/** 下一步提示（完全由服务端返回的状态推导）。 */
const nextStep = computed<string | null>(() => {
  const a = alert.value
  if (!a) return null
  switch (a.status) {
    case 'OPEN':
      return handler.value
        ? '请核对持续超温依据与受影响批次后“确认异常”，确认人即处置负责人。'
        : '等待发货方质量管理员确认异常；受影响批次已冻结，接收方不能接受冻结批次。'
    case 'ACKNOWLEDGED':
      if (canResolve.value) return '全部受影响批次已放行或已进入召回：请填写处置结论。'
      return isOwner.value
        ? '异常已确认：请为受影响批次提交检验证据；关联本告警的检验合格、且批次最新检验报告不是不合格时可放行，不合格的可由接收方拒收或发起模拟召回。'
        : '发货方质量管理员已确认异常并负责调查处置；隔离收货方可以为隔离批次提交检验证据。'
    case 'RESOLVED':
      return '告警已形成处置结论。'
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
  const alertId = Number(props.id)
  if (!Number.isInteger(alertId) || alertId <= 0) {
    loadState.value = 'not-found'
    return
  }
  try {
    const result = await getAlert(alertId, current.signal)
    if (current.signal.aborted) return
    alert.value = result
    loadState.value = 'loaded'
    resolveOrganizations([result.orgId, ...(result.actions ?? []).map((x) => x.orgId), ...(result.batches ?? []).map((b) => b.currentOrgId)])
  } catch (err: unknown) {
    if (current.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) loadState.value = 'not-found'
    else if (err instanceof ApiError && err.status === 403) loadState.value = 'forbidden'
    else {
      loadState.value = 'error'
      loadError.value = err instanceof ApiError ? err.message : '告警加载失败，请稍后重试'
    }
  }
}

function openAck() {
  ackOpen.value = true
  ackConfirming.value = false
  ackNote.value = ''
  actionError.value = ''
}

function closeAck() {
  ackOpen.value = false
  ackConfirming.value = false
}

async function submitAck() {
  const a = alert.value
  if (!a || busy.value) return
  const note = ackNote.value.trim()
  if (note.length > NOTE_MAX) {
    actionError.value = `说明不能超过 ${NOTE_MAX} 个字符`
    return
  }
  busy.value = true
  actionError.value = ''
  flash.value = null
  try {
    const result = await writer.run(
      `alert-ack:${a.id}:${a.version}:${note}`,
      () => note || undefined,
      (payload, key) => acknowledgeAlert(a.id, payload, key)
    )
    alert.value = result
    closeAck()
    flash.value = { tone: 'success', message: '已确认异常，本账号为该告警的处置负责人。' }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '确认异常')
    if (err instanceof ApiError && err.status === 409) {
      closeAck()
      flash.value = { tone: 'warning', message }
      await load()
    } else {
      actionError.value = message
    }
  } finally {
    busy.value = false
  }
}

function openRelease(b: AlertAffectedBatch) {
  releasing.value = b.batchId
  releaseConfirming.value = false
  releaseNote.value = ''
  actionError.value = ''
}

function closeRelease() {
  releasing.value = null
  releaseConfirming.value = false
}

async function submitRelease(b: AlertAffectedBatch) {
  const a = alert.value
  if (!a || busy.value) return
  const note = releaseNote.value.trim()
  if (note.length > NOTE_MAX) {
    actionError.value = `说明不能超过 ${NOTE_MAX} 个字符`
    return
  }
  busy.value = true
  actionError.value = ''
  flash.value = null
  try {
    alert.value = await writer.run(
      `alert-release:${a.id}:${b.batchId}:${note}`,
      () => note || undefined,
      (payload, key) => releaseAlertBatch(a.id, b.batchId, payload, key)
    )
    closeRelease()
    const after = alert.value?.batches?.find((x) => x.batchId === b.batchId)
    flash.value = after && after.currentRiskStatus === 'FROZEN'
      ? { tone: 'warning', message: `已记录本告警对批次 ${b.traceBatchNo} 的放行结论；批次仍被${holdsText(after)}冻结，这些风险事项也形成结论后才恢复正常。` }
      : { tone: 'success', message: `批次 ${b.traceBatchNo} 已依据检验结论放行，风险状态恢复正常；接收方可以接受隔离中的交接。` }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '放行批次')
    closeRelease()
    if (err instanceof ApiError && err.status === 409) {
      flash.value = { tone: 'warning', message }
      await load()
    } else {
      actionError.value = message
    }
  } finally {
    busy.value = false
  }
}

async function submitResolve() {
  const a = alert.value
  if (!a || busy.value) return
  const text = resolution.value.trim()
  if (!text || text.length > NOTE_MAX) {
    actionError.value = !text ? '请填写处置结论' : `处置结论不能超过 ${NOTE_MAX} 个字符`
    resolveConfirming.value = false
    return
  }
  busy.value = true
  actionError.value = ''
  flash.value = null
  try {
    alert.value = await writer.run(
      `alert-resolve:${a.id}:${a.version}:${text}`,
      () => text,
      (payload, key) => resolveAlert(a.id, payload, key)
    )
    resolveOpen.value = false
    resolveConfirming.value = false
    flash.value = { tone: 'success', message: '已形成处置结论，告警处置完毕。' }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '形成处置结论')
    resolveConfirming.value = false
    if (err instanceof ApiError && err.status === 409) {
      resolveOpen.value = false
      flash.value = { tone: 'warning', message }
      await load()
    } else {
      actionError.value = message
    }
  } finally {
    busy.value = false
  }
}

watch(() => props.id, () => {
  flash.value = null
  actionError.value = ''
  closeAck()
  closeRelease()
  resolveOpen.value = false
  load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="alert-detail-page">
    <RouterLink to="/app/alerts" class="ent-button ent-back-link" data-testid="back-to-alerts">← 返回告警</RouterLink>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="alert-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading' && !alert" class="ent-card ent-state" data-testid="alert-loading">正在加载告警…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert" data-testid="alert-not-found">未找到该告警。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="alert-forbidden">
      只有告警归属组织与运输任务的接收方、承运方可以查看该告警。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="alert">
      <div class="ent-page-header">
        <div>
          <p class="ent-page-subtitle">{{ formatAlertType(alert.alertType) }} · 运输任务级告警</p>
          <h1 class="ent-page-title mono" data-testid="alert-no">{{ alert.alertNo }}</h1>
        </div>
        <StatusBadge :info="formatAlertStatus(alert.status)" dimension="告警" data-testid="alert-status" :data-status="alert.status" />
      </div>

      <p v-if="nextStep" class="ent-next-step" data-testid="alert-next-step"><strong>下一步：</strong>{{ nextStep }}</p>
      <p class="ent-flash warning" data-testid="alert-disclaimer">
        本告警依据运输途中登记的温度记录（教学演示中人工 / 模拟数据）按规则判定，冻结与处置均为模拟质量处置，
        不代表真实的产品扣留、安全判定或监管措施。
      </p>

      <section class="ent-card" aria-labelledby="alert-basis-title" data-testid="alert-basis">
        <h2 id="alert-basis-title" class="ent-card-title">持续超温依据</h2>
        <p data-testid="alert-reason">{{ alert.reason }}</p>
        <dl class="ent-dl">
          <dt>运输任务</dt>
          <dd class="mono"><RouterLink :to="`/app/shipments/${alert.shipmentId}`" data-testid="alert-shipment-link">{{ alert.shipmentNo }}</RouterLink></dd>
          <dt>归属组织（负责处置）</dt>
          <dd data-testid="alert-owner">{{ organizationLabel(alert.orgId) }}</dd>
          <dt>越界开始（测量时间）</dt>
          <dd data-testid="alert-episode-start">{{ formatIsoDateTime(alert.episodeStartedAt) }}</dd>
          <dt>达到持续超温（测量时间）</dt>
          <dd data-testid="alert-sustained-at">{{ formatIsoDateTime(alert.sustainedAt) }}</dd>
          <dt>已持续越界</dt>
          <dd data-testid="alert-duration">{{ formatDurationSeconds(alert.durationSeconds) }}</dd>
          <dt>判定依据（登记时快照）</dt>
          <dd data-testid="alert-rule">
            {{ formatTemperature(alert.rule.lowerLimit) }} ~ {{ formatTemperature(alert.rule.upperLimit) }}，
            允许连续越界 {{ formatDurationSeconds(alert.rule.allowedDurationSeconds) }}
            <span v-if="alert.rule.name" class="ent-muted">（{{ alert.rule.name }} v{{ alert.rule.versionNo }}）</span>
          </dd>
          <dt>告警创建时间</dt>
          <dd>{{ formatIsoDateTime(alert.triggeredAt) }}</dd>
        </dl>
      </section>

      <section class="ent-card" aria-labelledby="alert-batches-title" data-testid="alert-batches">
        <h2 id="alert-batches-title" class="ent-card-title">受影响批次（运输任务 → 交接 → 批次）</h2>
        <p class="section-note">告警创建时经运输任务装载的交接确定受影响批次；原为正常的批次已由系统自动风险冻结，数量、责任组织与流转状态不变。</p>
        <div class="ent-table-scroll">
          <table class="ent-table">
            <thead>
              <tr><th>追溯批次号</th><th>交接</th><th>数量</th><th>告警时风险状态</th><th>自动冻结</th><th>当前状态</th><th>质量处置</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="b in alert.batches ?? []"
                :key="b.batchId"
                data-testid="alert-batch-row"
                :data-batch-id="b.batchId"
                :data-risk-status="b.currentRiskStatus"
              >
                <td class="mono">
                  <RouterLink v-if="b.currentOrgId === user?.orgId" :to="`/app/batches/${b.batchId}`">{{ b.traceBatchNo }}</RouterLink>
                  <span v-else>{{ b.traceBatchNo }}</span>
                </td>
                <td class="mono">
                  {{ b.transferNo }}
                  <StatusBadge :info="formatTransferStatus(b.transferStatus)" dimension="交接" data-testid="alert-batch-transfer-status" />
                </td>
                <td>{{ formatQuantity(b.quantity, b.unitCode) }}</td>
                <td>{{ formatRiskStatus(b.riskStatusBefore).label }}</td>
                <td data-testid="alert-batch-auto-frozen">{{ b.autoFrozen ? '是（系统自动冻结）' : '否（告警时已非正常）' }}</td>
                <td>
                  <StatusBadge :info="formatRiskStatus(b.currentRiskStatus)" dimension="风险" data-testid="alert-batch-risk" />
                  <StatusBadge :info="formatFlowStatus(b.currentFlowStatus)" dimension="流转" />
                </td>
                <td data-testid="alert-batch-disposition">{{ dispositionOf(b) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="alert.status !== 'OPEN'" class="ent-card" aria-labelledby="alert-quality-title" data-testid="alert-quality">
        <h2 id="alert-quality-title" class="ent-card-title">检验证据与质量结论（按批次）</h2>
        <div
          v-for="b in alert.batches ?? []"
          :key="b.batchId"
          class="quality-block"
          data-testid="alert-quality-batch"
          :data-batch-id="b.batchId"
        >
          <div class="quality-head">
            <strong class="mono">{{ b.traceBatchNo }}</strong>
            <StatusBadge :info="formatInspectionConclusion(b.latestInspectionConclusion)" dimension="检验" data-testid="alert-batch-inspection" />
            <span class="ent-muted">{{ dispositionOf(b) }}</span>
          </div>
          <p v-if="b.currentRiskStatus === 'FROZEN' && b.pendingHolds && b.pendingHolds.length > 0" class="ent-muted" data-testid="alert-batch-pending-holds">
            仍未解除的风险事项：
            <template v-for="(h, i) in b.pendingHolds" :key="`${h.type}-${h.alertId ?? i}`">
              <span v-if="i > 0">、</span>
              <RouterLink v-if="h.type === 'ALERT'" :to="`/app/alerts/${h.alertId}`" data-testid="alert-batch-pending-alert">告警 {{ h.alertNo }}</RouterLink>
              <span v-else data-testid="alert-batch-pending-manual">人工风险冻结（需在批次详情中人工解除）</span>
            </template>
          </p>
          <InspectionReportPanel
            :batch-id="b.batchId"
            :alert-id="alert.id"
            :can-submit="canInspect(b)"
            only-alert
            @submitted="load"
          />
          <div v-if="canRelease(b) && releasing !== b.batchId" class="ent-actions">
            <button type="button" class="ent-button ent-primary" :data-testid="`alert-release-open-${b.batchId}`" @click="openRelease(b)">
              依据检验结论放行
            </button>
          </div>
          <RecallStartForm
            v-if="canRecall(b) && releasing !== b.batchId"
            :batch-ids="[b.batchId]"
            :label="`批次 ${b.traceBatchNo}`"
            :alert-id="alert.id"
            :normal="b.currentRiskStatus === 'NORMAL'"
            @conflict="onRecallConflict"
          />
          <div v-if="releasing === b.batchId" class="release-form" :data-testid="`alert-release-form-${b.batchId}`">
            <label class="ent-field">
              <span>放行说明（可选）</span>
              <input v-model="releaseNote" :maxlength="NOTE_MAX" :disabled="releaseConfirming" data-testid="field-alert-release-note" />
            </label>
            <div v-if="!releaseConfirming" class="ent-actions">
              <button type="button" class="ent-button ent-primary" data-testid="alert-release-next" @click="releaseConfirming = true">下一步：确认</button>
              <button type="button" class="ent-button" data-testid="alert-release-cancel" @click="closeRelease">取消</button>
            </div>
            <div v-else class="ent-flash warning" role="alert" data-testid="alert-release-confirm-panel">
              确认依据关联本告警的最新合格检验结论，对批次 {{ b.traceBatchNo }} 形成本告警的放行结论？服务端还将核对批次最新检验报告不是不合格；
              只有这一结论解除了批次最后一个风险事项（其他未处置告警、人工风险冻结）时，批次风险状态才恢复为正常，否则批次保持冻结。
              数量、责任组织与流转状态不变；交接保持原状态，由接收方随后决定接受。
              <div class="ent-actions">
                <button type="button" class="ent-button ent-primary" :disabled="busy" data-testid="alert-release-confirm" @click="submitRelease(b)">
                  {{ busy ? '提交中…' : '确认放行' }}
                </button>
                <button type="button" class="ent-button" :disabled="busy" data-testid="alert-release-back" @click="releaseConfirming = false">返回修改</button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="canResolve" class="resolve-block" data-testid="alert-resolve">
          <div v-if="!resolveOpen" class="ent-actions">
            <button type="button" class="ent-button ent-primary" data-testid="alert-resolve-open" @click="resolveOpen = true">形成处置结论</button>
          </div>
          <form v-else novalidate data-testid="alert-resolve-form" @submit.prevent="resolveConfirming = true">
            <label class="ent-field">
              <span>处置结论 <em>*</em></span>
              <textarea v-model="resolution" rows="2" :maxlength="NOTE_MAX" :disabled="resolveConfirming" data-testid="field-alert-resolution" />
            </label>
            <div v-if="!resolveConfirming" class="ent-actions">
              <button type="submit" class="ent-button ent-primary" data-testid="alert-resolve-next">下一步：确认</button>
              <button type="button" class="ent-button" data-testid="alert-resolve-cancel" @click="resolveOpen = false">取消</button>
            </div>
            <div v-else class="ent-flash warning" role="alert" data-testid="alert-resolve-confirm-panel">
              确认形成处置结论？告警将标记为已处置，之后不能再关联新的检验证据；批次状态不因此改变。
              <div class="ent-actions">
                <button type="button" class="ent-button ent-primary" :disabled="busy" data-testid="alert-resolve-confirm" @click="submitResolve">
                  {{ busy ? '提交中…' : '确认处置结论' }}
                </button>
                <button type="button" class="ent-button" :disabled="busy" data-testid="alert-resolve-back" @click="resolveConfirming = false">返回修改</button>
              </div>
            </div>
          </form>
        </div>
        <p v-if="alert.resolution" data-testid="alert-resolution"><strong>处置结论：</strong>{{ alert.resolution }}</p>
      </section>

      <section v-if="canAck" class="ent-card" aria-labelledby="alert-actions-title" data-testid="alert-actions">
        <h2 id="alert-actions-title" class="ent-card-title">质量处置</h2>
        <div v-if="!ackOpen" class="ent-actions">
          <button type="button" class="ent-button ent-primary" data-testid="alert-ack-open" @click="openAck">确认异常</button>
        </div>
        <form v-else novalidate data-testid="alert-ack-form" @submit.prevent="ackConfirming = true">
          <label class="ent-field">
            <span>说明（可选）</span>
            <textarea v-model="ackNote" rows="2" :maxlength="NOTE_MAX" :disabled="ackConfirming" data-testid="field-alert-ack-note" />
          </label>
          <div v-if="!ackConfirming" class="ent-actions">
            <button type="submit" class="ent-button ent-primary" data-testid="alert-ack-next">下一步：确认</button>
            <button type="button" class="ent-button" data-testid="alert-ack-cancel" @click="closeAck">取消</button>
          </div>
          <div v-else class="ent-flash warning" role="alert" data-testid="alert-ack-confirm-panel">
            确认异常后，本账号成为该告警的处置负责人；批次冻结状态不变，后续需依据检验调查结论处置受影响批次。
            <div class="ent-actions">
              <button type="button" class="ent-button ent-primary" :disabled="busy" data-testid="alert-ack-confirm" @click="submitAck">
                {{ busy ? '提交中…' : '确认异常' }}
              </button>
              <button type="button" class="ent-button" :disabled="busy" data-testid="alert-ack-back" @click="ackConfirming = false">返回修改</button>
            </div>
          </div>
        </form>
      </section>

      <p v-if="actionError" class="ent-flash error" role="alert" data-testid="alert-action-error">{{ actionError }}</p>

      <section class="ent-card" aria-labelledby="alert-history-title" data-testid="alert-history">
        <h2 id="alert-history-title" class="ent-card-title">处置历史</h2>
        <ol class="alert-history">
          <li data-testid="alert-history-row" data-action="TRIGGER">
            <strong>系统创建告警并自动冻结受影响批次</strong>
            <span class="ent-muted">{{ formatIsoDateTime(alert.triggeredAt) }}</span>
          </li>
          <li v-for="x in alert.actions ?? []" :key="x.id" data-testid="alert-history-row" :data-action="x.action">
            <strong>{{ formatAlertAction(x.action) }}</strong>
            <span class="ent-muted">{{ formatIsoDateTime(x.occurredAt) }} · {{ organizationLabel(x.orgId) }}</span>
            <div v-if="x.note" data-testid="alert-history-note">说明：{{ x.note }}</div>
          </li>
        </ol>
      </section>
    </template>
  </div>
</template>

<style scoped>
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.alert-history {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
}
.alert-history li strong {
  margin-right: 8px;
}
.quality-block {
  border-top: 1px solid var(--color-border, #e2e8f0);
  padding: 12px 0;
}
.quality-block:first-of-type {
  border-top: none;
  padding-top: 0;
}
.quality-head {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 8px;
}
.release-form,
.resolve-block {
  margin-top: 8px;
}
</style>
