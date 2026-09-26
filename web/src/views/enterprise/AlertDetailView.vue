<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { acknowledgeAlert, getAlert } from '@/api/alerts'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useSession } from '@/stores/session'
import type { Alert } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatAlertAction,
  formatAlertStatus,
  formatAlertType,
  formatDurationSeconds,
  formatFlowStatus,
  formatIsoDateTime,
  formatQuantity,
  formatRiskStatus,
  formatTemperature,
  formatTransferStatus
} from '@/utils/formatters'
import { canAcknowledgeAlert, canHandleAlert } from '@/utils/permissions'

/**
 * 告警详情（Phase B PB3 起；统一业务契约 v1.1 §2.11 / §10.2 / §13）：持续超温片段与判定依据快照、受影响批次（自动冻结结果与当前状态）、
 * 处置历史；告警归属组织的质量管理员确认异常。告警与冻结是教学实训中的模拟质量处置，不代表真实监管措施。服务端是最终权限边界。
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
      return isOwner.value
        ? '异常已确认：请组织检验调查，依据质量结论处置受影响批次。'
        : '发货方质量管理员已确认异常并负责调查处置。'
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

watch(() => props.id, () => {
  flash.value = null
  actionError.value = ''
  closeAck()
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
              <tr><th>追溯批次号</th><th>交接</th><th>数量</th><th>告警时风险状态</th><th>自动冻结</th><th>当前状态</th></tr>
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
              </tr>
            </tbody>
          </table>
        </div>
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
</style>
