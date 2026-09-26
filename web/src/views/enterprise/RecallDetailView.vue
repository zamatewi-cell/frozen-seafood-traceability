<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { closeRecall, getRecall } from '@/api/recalls'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { Recall, RecallPublicDisposition, RecallScopeItem, RecallScopeRole } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import {
  formatFlowStatus,
  formatIsoDateTime,
  formatQuantity,
  formatRecallDisposition,
  formatRecallScopeAction,
  formatRecallScopeRole,
  formatRecallStatus,
  formatRiskStatus,
  formatShipmentStatus,
  formatTransferStatus
} from '@/utils/formatters'
import { canCloseRecall } from '@/utils/permissions'

/**
 * 模拟召回详情（Phase B PB5；统一业务契约 v1.1 §2.12 / §13 步骤 8–14）：召回批次、正向后续批次（本组织持有的已召回，
 * 其他组织持有的已通知持有方）、反向上游批次（溯源调查），以及剩余（库存与在途）/ 已售数量、未结束交接与运输状态、公开追溯码事实；
 * 发起组织的质量管理员给出受控公开处置结论与内部总结后关闭，批次仍保留模拟召回风险终态。范围批次的持有方只看到本组织的范围行。
 * 模拟召回是教学演练，不代表真实法定召回。服务端是最终权限边界。
 */
const props = defineProps<{ id: string }>()

const { user } = useSession()
const writer = useIdempotentWrite()
const { resolveOrganizations, organizationLabel } = useDirectoryLabels()

type LoadState = 'loading' | 'loaded' | 'not-found' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const recall = ref<Recall | null>(null)
const flash = ref<{ tone: 'success' | 'warning'; message: string } | null>(null)

const closeOpen = ref(false)
const closeConfirming = ref(false)
const disposition = ref<'' | RecallPublicDisposition>('')
const summaryText = ref('')
const closeError = ref('')
const busy = ref(false)
const SUMMARY_MAX = 1000

const canClose = computed(() => canCloseRecall(user.value, recall.value))
const owner = computed(() => Boolean(recall.value && user.value && recall.value.ownerOrgId === user.value.orgId))
const ROLES: RecallScopeRole[] = ['SEED', 'DESCENDANT', 'ANCESTOR']

function rowsOf(role: RecallScopeRole): RecallScopeItem[] {
  return (recall.value?.scope ?? []).filter((r) => r.scopeRole === role)
}

const nextStep = computed<string | null>(() => {
  const r = recall.value
  if (!r) return null
  if (r.status === 'CLOSED') return '模拟召回已关闭：批次仍保留模拟召回风险终态，消费者页面显示处置已完成。'
  if (owner.value) {
    return (r.summary?.notifiedCount ?? 0) > 0
      ? '处置中：其他组织持有的后续批次已通知持有方，由持有方质量管理员发起召回；处置完成后填写公开处置结论并关闭。'
      : '处置中：完成库存、在途与已售部分的演练处置后，填写公开处置结论并关闭。'
  }
  return rowsOf('DESCENDANT').some((row) => row.action === 'NOTIFY_HOLDER' && row.currentRiskStatus !== 'RECALLED')
    ? '本组织持有的后续批次已被上游模拟召回圈定：请质量管理员在批次详情中以此为依据发起本组织的模拟召回。'
    : '本组织持有的范围批次仅用于溯源协查。'
})

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  loadError.value = ''
  const recallId = Number(props.id)
  if (!Number.isInteger(recallId) || recallId <= 0) {
    loadState.value = 'not-found'
    return
  }
  try {
    const result = await getRecall(recallId, current.signal)
    if (current.signal.aborted) return
    recall.value = result
    loadState.value = 'loaded'
    resolveOrganizations([result.ownerOrgId, ...(result.scope ?? []).map((row) => row.holderOrgId)])
  } catch (err: unknown) {
    if (current.signal.aborted) return
    if (err instanceof ApiError && err.status === 404) loadState.value = 'not-found'
    else if (err instanceof ApiError && err.status === 403) loadState.value = 'forbidden'
    else {
      loadState.value = 'error'
      loadError.value = err instanceof ApiError ? err.message : '模拟召回加载失败，请稍后重试'
    }
  }
}

function nextClose() {
  const text = summaryText.value.trim()
  closeError.value = !disposition.value ? '请选择公开处置结论'
    : !text ? '请填写内部处置总结' : text.length > SUMMARY_MAX ? `处置总结不能超过 ${SUMMARY_MAX} 个字符` : ''
  if (!closeError.value) closeConfirming.value = true
}

async function submitClose() {
  const r = recall.value
  if (!r || busy.value || !disposition.value) return
  busy.value = true
  closeError.value = ''
  flash.value = null
  const chosen = disposition.value
  const text = summaryText.value.trim()
  try {
    recall.value = await writer.run(
      `recall-close:${r.id}:${r.version}:${chosen}:${text}`,
      () => ({ chosen, text }),
      (payload, key) => closeRecall(r.id, payload.chosen, payload.text, key)
    )
    closeOpen.value = false
    closeConfirming.value = false
    flash.value = { tone: 'success', message: '模拟召回已关闭；批次仍保留模拟召回风险终态，消费者页面显示处置已完成。' }
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '关闭模拟召回')
    closeConfirming.value = false
    if (err instanceof ApiError && err.status === 409) {
      closeOpen.value = false
      flash.value = { tone: 'warning', message }
      await load()
    } else {
      closeError.value = message
    }
  } finally {
    busy.value = false
  }
}

watch(() => props.id, () => {
  flash.value = null
  closeOpen.value = false
  load()
}, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="recall-detail-page">
    <RouterLink to="/app/recalls" class="ent-button ent-back-link" data-testid="back-to-recalls">← 返回模拟召回</RouterLink>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="recall-flash">{{ flash.message }}</p>

    <div v-if="loadState === 'loading' && !recall" class="ent-card ent-state" data-testid="recall-loading">正在加载模拟召回…</div>
    <div v-else-if="loadState === 'not-found'" class="ent-card ent-state" role="alert" data-testid="recall-not-found">未找到该模拟召回。</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-card ent-state error" role="alert" data-testid="recall-forbidden">
      只有召回发起组织与影响范围批次的持有组织可以查看该模拟召回。
    </div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="recall">
      <div class="ent-page-header">
        <div>
          <p class="ent-page-subtitle">模拟召回（教学演练）· 发起组织：{{ organizationLabel(recall.ownerOrgId) }}</p>
          <h1 class="ent-page-title mono" data-testid="recall-no">{{ recall.recallNo }}</h1>
        </div>
        <StatusBadge :info="formatRecallStatus(recall.status)" dimension="召回" data-testid="recall-status" :data-status="recall.status" />
      </div>

      <p v-if="nextStep" class="ent-next-step" data-testid="recall-next-step"><strong>下一步：</strong>{{ nextStep }}</p>
      <p class="ent-flash warning" data-testid="recall-disclaimer">
        模拟召回是教学实训中的演练流程：不代表真实法定召回、监管措施或产品安全结论；消费者页面只显示受控的模拟召回提示与处置进展。
      </p>

      <section class="ent-card" aria-labelledby="recall-facts-title" data-testid="recall-facts">
        <h2 id="recall-facts-title" class="ent-card-title">召回概况</h2>
        <dl class="ent-dl">
          <dt>召回原因</dt>
          <dd data-testid="recall-reason">{{ recall.reason }}</dd>
          <dt>发起时间</dt>
          <dd>{{ formatIsoDateTime(recall.startedAt) }}</dd>
          <template v-if="recall.sourceAlertId && owner">
            <dt>来源告警</dt>
            <dd><RouterLink :to="`/app/alerts/${recall.sourceAlertId}`" data-testid="recall-alert-link">查看告警</RouterLink></dd>
          </template>
          <template v-if="recall.summary">
            <dt>召回 / 通知</dt>
            <dd data-testid="recall-summary-counts">
              模拟召回 {{ recall.summary.recalledCount }} 个批次，通知持有方 {{ recall.summary.notifiedCount }} 个批次，
              上游溯源 {{ recall.summary.ancestorCount }} 个批次
            </dd>
            <dt>涉及数量</dt>
            <dd data-testid="recall-summary-quantities">
              剩余（库存与在途）{{ formatQuantity(recall.summary.remainingQuantity, 'kg') }}，已售 {{ formatQuantity(recall.summary.soldQuantity, 'kg') }}；
              未结束交接 {{ recall.summary.openTransferCount }} 张，启用中的公开追溯码 {{ recall.summary.publicCodeCount }} 个
            </dd>
          </template>
          <template v-if="recall.status === 'CLOSED'">
            <dt>公开处置结论</dt>
            <dd data-testid="recall-disposition">{{ formatRecallDisposition(recall.publicDisposition) }}（{{ formatIsoDateTime(recall.closedAt) }}）</dd>
            <template v-if="recall.resultSummary">
              <dt>内部处置总结</dt>
              <dd data-testid="recall-result-summary">{{ recall.resultSummary }}</dd>
            </template>
          </template>
        </dl>
      </section>

      <section
        v-for="role in ROLES"
        v-show="rowsOf(role).length > 0"
        :key="role"
        class="ent-card"
        :data-testid="`recall-scope-${role}`"
      >
        <h2 class="ent-card-title">{{ formatRecallScopeRole(role) }}（{{ rowsOf(role).length }}）</h2>
        <div class="ent-table-scroll">
          <table class="ent-table">
            <thead>
              <tr>
                <th>追溯批次号</th><th>持有组织</th><th>召回时状态</th><th>当前状态</th><th>处置</th>
                <th v-if="role !== 'ANCESTOR'">剩余 / 已售</th><th v-if="role !== 'ANCESTOR'">未结束交接</th><th v-if="role !== 'ANCESTOR'">公开码</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rowsOf(role)"
                :key="row.batchId"
                data-testid="recall-scope-row"
                :data-batch-id="row.batchId"
                :data-action="row.action"
                :data-risk-status="row.currentRiskStatus"
              >
                <td class="mono">
                  <RouterLink v-if="row.holderOrgId === user?.orgId" :to="`/app/batches/${row.batchId}`">{{ row.traceBatchNo }}</RouterLink>
                  <span v-else>{{ row.traceBatchNo }}</span>
                  <div v-if="row.productName" class="ent-muted">{{ row.productName }}</div>
                </td>
                <td>{{ organizationLabel(row.holderOrgId) }}</td>
                <td>{{ formatFlowStatus(row.flowStatus).label }} / {{ formatRiskStatus(row.riskStatusBefore).label }}</td>
                <td>
                  <StatusBadge :info="formatRiskStatus(row.currentRiskStatus)" dimension="风险" />
                  <StatusBadge :info="formatFlowStatus(row.currentFlowStatus)" dimension="流转" />
                </td>
                <td data-testid="recall-scope-action">{{ formatRecallScopeAction(row.action) }}</td>
                <td v-if="role !== 'ANCESTOR'" data-testid="recall-scope-quantities">
                  {{ formatQuantity(row.remainingQuantity, row.unitCode) }} / {{ formatQuantity(row.soldQuantity, row.unitCode) }}
                </td>
                <td v-if="role !== 'ANCESTOR'">
                  <template v-if="row.openTransferStatus">
                    <span class="mono">{{ row.openTransferNo }}</span>
                    <StatusBadge :info="formatTransferStatus(row.openTransferStatus)" dimension="交接" />
                    <StatusBadge v-if="row.shipmentStatus" :info="formatShipmentStatus(row.shipmentStatus)" dimension="运输" />
                  </template>
                  <span v-else class="ent-muted">无</span>
                </td>
                <td v-if="role !== 'ANCESTOR'">{{ row.publicCodeActive ? '启用中（消费者可见模拟召回提示）' : '未启用' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="canClose" class="ent-card" aria-labelledby="recall-close-title" data-testid="recall-close">
        <h2 id="recall-close-title" class="ent-card-title">关闭模拟召回</h2>
        <div v-if="!closeOpen" class="ent-actions">
          <button type="button" class="ent-button ent-primary" data-testid="recall-close-open" @click="closeOpen = true">填写处置结论并关闭</button>
        </div>
        <form v-else novalidate data-testid="recall-close-form" @submit.prevent="nextClose">
          <label class="ent-field">
            <span>公开处置结论 <em>*</em></span>
            <select v-model="disposition" :disabled="closeConfirming" data-testid="field-recall-disposition">
              <option value="">请选择</option>
              <option value="DESTROYED">按演练流程销毁处置</option>
              <option value="RETURNED">按演练流程退回处置</option>
            </select>
          </label>
          <label class="ent-field">
            <span>内部处置总结（不对消费者公开）<em>*</em></span>
            <textarea v-model="summaryText" rows="3" :maxlength="SUMMARY_MAX" :disabled="closeConfirming" data-testid="field-recall-summary" />
          </label>
          <div v-if="!closeConfirming" class="ent-actions">
            <button type="submit" class="ent-button ent-primary" data-testid="recall-close-next">下一步：确认</button>
            <button type="button" class="ent-button" data-testid="recall-close-cancel" @click="closeOpen = false">取消</button>
          </div>
          <div v-else class="ent-flash warning" role="alert" data-testid="recall-close-confirm-panel">
            确认关闭模拟召回？批次仍保留模拟召回风险终态；消费者页面显示“{{ formatRecallDisposition(disposition) }}”的固定文案（不显示内部总结）。
            <div class="ent-actions">
              <button type="button" class="ent-button ent-primary" :disabled="busy" data-testid="recall-close-confirm" @click="submitClose">
                {{ busy ? '提交中…' : '确认关闭' }}
              </button>
              <button type="button" class="ent-button" :disabled="busy" data-testid="recall-close-back" @click="closeConfirming = false">返回修改</button>
            </div>
          </div>
        </form>
        <p v-if="closeError" class="ent-flash error" role="alert" data-testid="recall-close-error">{{ closeError }}</p>
      </section>
    </template>
  </div>
</template>
