<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { freezeBatch, getBatchRiskHolds, listRiskTransitions, releaseBatch } from '@/api/batchRisk'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { Batch, BatchRiskHolds, BatchRiskTransition, CurrentUser } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatFlowStatus, formatIsoDateTime, formatRiskStatus } from '@/utils/formatters'
import { canFreezeBatch, canReleaseBatch, canStartRecall } from '@/utils/permissions'
import RecallStartForm from '@/components/enterprise/RecallStartForm.vue'

/**
 * 批次风险状态（Phase B PB1；统一业务契约 v1.1 §4.2 / §4.3 / §14）：当前责任组织的质量管理员填写原因并二次确认后
 * 风险冻结（NORMAL → FROZEN）或解除冻结（FROZEN → NORMAL），ACTIVE 与 CLOSED 批次均可。
 * 风险转换只改变风险状态：不改变数量、当前责任组织与流转状态，不生成追溯事件；冻结期间的业务阻断由服务端既有守卫执行。
 * 这是教学实训中的模拟质量处置，不代表真实的产品扣留、安全判定或监管措施。服务端是最终权限边界。
 * 独立评审修复：风险事项与召回通知属于批次——当前责任组织看到仍未解除的告警风险事项、人工风险冻结与上游召回通知；
 * 仍有未处置告警时人工解除冻结入口隐藏（需先在告警中依据检验结论形成放行结论）；风险事项尚未读取成功时同样不提供该入口，
 * 并显示读取失败与重试（不把未知当作“没有未处置告警”）；重新读取期间或读取后出现未处置告警时，已打开的解除表单随之关闭。
 */
type Action = 'FREEZE' | 'RELEASE'

const props = defineProps<{
  batch: Batch
  user: CurrentUser | null
  orgLabel: (orgId: number) => string
  /** 请求目录解析组织名称（风险转换的历史责任组织、召回通知的发起组织）。 */
  resolveOrgs?: (orgIds: Iterable<number>) => void
}>()

const emit = defineEmits<{
  changed: [transition: BatchRiskTransition]
  /** 409：批次状态已被修改或请求冲突；父页面刷新详情（本面板随之重新挂载），并以提示条保留原因。 */
  conflict: [message: string]
}>()

const LABELS: Record<Action, string> = { FREEZE: '风险冻结', RELEASE: '解除冻结' }
const REASON_MAX = 500

type LoadState = 'loading' | 'loaded' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const transitions = ref<BatchRiskTransition[]>([])
const mode = ref<Action | null>(null)
const reason = ref('')
const reasonError = ref('')
const confirming = ref(false)
const submitting = ref(false)
const writeError = ref('')
const writer = useIdempotentWrite()
let controller: AbortController | null = null
const holds = ref<BatchRiskHolds | null>(null)
type HoldsState = 'idle' | 'loading' | 'loaded' | 'error'
const holdsState = ref<HoldsState>('idle')

/** 当前责任组织与平台只读角色可以查看批次的风险事项与召回通知。 */
const canSeeHolds = computed(() => Boolean(props.user
  && (props.user.orgId === props.batch.orgId || props.user.scopes.includes('PLATFORM'))))
/** 只展示当前可查看、且已成功读取到的本批次风险事项。 */
const visibleHolds = computed(() => (canSeeHolds.value && holdsState.value === 'loaded' && holds.value?.batchId === props.batch.id
  ? holds.value
  : null))
const pendingAlertHolds = computed(() => visibleHolds.value?.alertHolds ?? [])
const openRecallNotices = computed(() => (props.batch.riskStatus === 'RECALLED' ? [] : visibleHolds.value?.recallNotices ?? []))
const canFreeze = computed(() => canFreezeBatch(props.user, props.batch))
const canRecall = computed(() => canStartRecall(props.user, props.batch))
/** 只有成功读取到本批次的风险事项、且没有未处置告警时，才提供人工解除冻结入口（服务端仍是最终守卫）。 */
const canRelease = computed(() => canReleaseBatch(props.user, props.batch)
  && visibleHolds.value !== null && pendingAlertHolds.value.length === 0)
const confirmText = computed(() => mode.value === 'FREEZE'
  ? '确认风险冻结？冻结后本批次暂停加工 / 拆分、交接、终端销售、冷库仓储登记与首次激活公开追溯码；数量、当前责任组织与流转状态保持不变。'
  : props.batch.flowStatus === 'CLOSED'
    ? '确认解除冻结？批次风险状态恢复为正常，流转状态仍保持已关闭。'
    : '确认解除冻结？解除后批次恢复为正常，可继续符合状态条件的业务。')

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  holds.value = null
  holdsState.value = canSeeHolds.value ? 'loading' : 'idle'
  try {
    const result = await listRiskTransitions(props.batch.id, current.signal)
    if (current.signal.aborted) return
    transitions.value = result
    loadState.value = 'loaded'
    props.resolveOrgs?.(result.map((t) => t.orgId))
  } catch (err: unknown) {
    if (current.signal.aborted) return
    transitions.value = []
    loadState.value = err instanceof ApiError && err.status === 403 ? 'forbidden' : 'error'
  }
  if (!canSeeHolds.value) return
  try {
    const result = await getBatchRiskHolds(props.batch.id, current.signal)
    if (current.signal.aborted) return
    holds.value = result
    holdsState.value = 'loaded'
    props.resolveOrgs?.(result.recallNotices.map((n) => n.ownerOrgId))
  } catch (err: unknown) {
    if (current.signal.aborted || (err instanceof ApiError && err.status === 401)) return
    // 读取失败时不能确认是否仍有未处置告警：不提供人工解除冻结入口，其余功能不受影响；服务端仍是最终的放行守卫
    holdsState.value = 'error'
  }
}

function open(action: Action) {
  mode.value = action
  reason.value = ''
  reasonError.value = ''
  confirming.value = false
  writeError.value = ''
}

function close() {
  mode.value = null
  confirming.value = false
  reasonError.value = ''
}

/** 第一步：校验原因（去除首尾空白后 1..500 个字符），通过后进入二次确认。 */
function next() {
  const trimmed = reason.value.trim()
  reasonError.value = !trimmed ? '请填写原因' : trimmed.length > REASON_MAX ? `原因不能超过 ${REASON_MAX} 个字符` : ''
  if (!reasonError.value) confirming.value = true
}

async function submit() {
  const action = mode.value
  if (!action || submitting.value) return
  if (action === 'RELEASE' && !canRelease.value) {
    close()
    return
  }
  const trimmed = reason.value.trim()
  submitting.value = true
  writeError.value = ''
  try {
    const result = await writer.run(
      `risk:${props.batch.id}:${action}:${trimmed}`,
      () => trimmed,
      (payload, key) => (action === 'FREEZE' ? freezeBatch(props.batch.id, payload, key) : releaseBatch(props.batch.id, payload, key))
    )
    close()
    emit('changed', result)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    writeError.value = describeWriteError(err, LABELS[action])
    if (err instanceof ApiError && err.status === 409) {
      close()
      emit('conflict', writeError.value)
    }
  } finally {
    submitting.value = false
  }
}

function transitionLabel(t: BatchRiskTransition): string {
  if (t.sourceType === 'RECALL') return '模拟召回'
  if (t.sourceType === 'ALERT') return t.toStatus === 'FROZEN' ? '告警自动冻结' : '依据检验结论放行'
  return t.toStatus === 'FROZEN' ? '风险冻结' : '解除冻结'
}

function sourceLabel(t: BatchRiskTransition): string {
  if (t.sourceType === 'RECALL') return '模拟召回案件（质量管理员发起，风险终态）'
  if (t.sourceType === 'ALERT') {
    return t.toStatus === 'FROZEN' ? '在途持续超温告警（系统自动，无操作人）' : '告警质量结论（质量管理员依据检验报告）'
  }
  return '质量管理员人工处置'
}

// 风险事项重新变为未知（重新读取中 / 读取失败）或出现未处置告警时，关闭已打开的解除表单，不在未知状态下提交解除；
// 解除请求在途时等请求结束（失败后表单不能停留在已失效的确认状态）
watch([canRelease, submitting], ([allowed, busy]) => {
  if (!allowed && !busy && mode.value === 'RELEASE') close()
})

watch(() => [props.batch.id, props.batch.version], () => {
  close()
  load()
}, { immediate: true })

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="ent-card" aria-labelledby="risk-title" data-testid="risk-panel">
    <h2 id="risk-title" class="ent-card-title">风险状态</h2>
    <p class="section-note">
      风险冻结 / 解除冻结是教学实训中的模拟质量处置：只改变风险状态，不改变批次数量、当前责任组织与流转状态，也不会生成追溯事件；
      不代表真实的产品扣留、安全判定或监管措施。由批次当前责任组织的质量管理员填写原因后执行。
    </p>

    <dl class="ent-dl risk-dl">
      <dt>风险状态</dt>
      <dd><StatusBadge :info="formatRiskStatus(batch.riskStatus)" dimension="风险" data-testid="risk-current" :data-status="batch.riskStatus" /></dd>
      <dt>流转状态</dt>
      <dd><StatusBadge :info="formatFlowStatus(batch.flowStatus)" dimension="流转" /></dd>
    </dl>

    <p v-if="batch.riskStatus === 'FROZEN'" class="ent-flash warning" role="status" data-testid="risk-frozen-effects">
      风险冻结中：加工 / 拆分、交接的创建 / 绑定 / 提交 / 接收、终端销售、冷库仓储登记与首次激活公开追溯码均已暂停；
      查询、消费者公开追溯页、运输发运 / 到达与到货拒收不受影响。
    </p>
    <p v-else-if="batch.riskStatus === 'RECALLED'" class="ent-flash error" role="status" data-testid="risk-recalled-note">
      批次已进入模拟召回（风险终态）：不能再风险冻结、解除冻结、销售、交接或加工；消费者页面显示模拟召回提示。
      {{ batch.flowStatus === 'CLOSED' ? '流转状态保持已关闭。' : '' }}
    </p>

    <div v-if="pendingAlertHolds.length > 0 || visibleHolds?.manualFreezeHold" class="risk-holds" data-testid="risk-holds">
      <strong>仍未解除的风险事项：</strong>
      <ul>
        <li v-for="h in pendingAlertHolds" :key="h.alertId" data-testid="risk-hold-alert">
          持续超温告警 <RouterLink :to="`/app/alerts/${h.alertId}`" class="mono">{{ h.alertNo }}</RouterLink>
          ：需在告警中依据关联的检验结论形成放行结论
        </li>
        <li v-if="visibleHolds?.manualFreezeHold" data-testid="risk-hold-manual">
          人工风险冻结：{{ pendingAlertHolds.length > 0 ? '告警结论全部形成后' : '' }}由质量管理员填写原因人工解除
        </li>
      </ul>
      <p class="ent-muted">批次只有在全部风险事项都形成结论后才恢复正常；任何一个事项的结论都不会单独解除其他事项。</p>
    </div>

    <div v-if="holdsState === 'error'" class="ent-state error" data-testid="risk-holds-error">
      风险事项与召回通知加载失败{{ canReleaseBatch(user, batch) ? '：确认没有未处置告警前暂不提供人工解除冻结' : '' }}
      <button type="button" class="ent-button" data-testid="risk-holds-retry" @click="load">重试</button>
    </div>

    <div v-if="openRecallNotices.length > 0" class="ent-flash warning" role="status" data-testid="risk-recall-notices">
      <strong>上游模拟召回通知：</strong>
      <ul>
        <li v-for="n in openRecallNotices" :key="n.recallId" data-testid="risk-recall-notice" :data-recall-id="n.recallId">
          <RouterLink :to="`/app/recalls/${n.recallId}`" class="mono">{{ n.recallNo }}</RouterLink>
          （发起组织：{{ orgLabel(n.ownerOrgId) }}，通知时间 {{ formatIsoDateTime(n.notifiedAt) }}）
        </li>
      </ul>
      本批次已被上游模拟召回正向圈定。请质量管理员调查该批次：必要时风险冻结，或以上游召回为证据发起本组织的模拟召回。
    </div>

    <div v-if="!mode && (canFreeze || canRelease)" class="ent-actions">
      <button v-if="canFreeze" type="button" class="ent-button ent-danger" data-testid="risk-freeze-open" @click="open('FREEZE')">风险冻结</button>
      <button v-if="canRelease" type="button" class="ent-button ent-primary" data-testid="risk-release-open" @click="open('RELEASE')">解除冻结</button>
    </div>

    <RecallStartForm
      v-if="!mode && canRecall"
      :batch-ids="[batch.id]"
      :label="`批次 ${batch.traceBatchNo}`"
      :normal="batch.riskStatus === 'NORMAL'"
      @conflict="(message) => emit('conflict', message)"
    />

    <form v-if="mode" novalidate :data-mode="mode" data-testid="risk-form" @submit.prevent="next">
      <h3 class="form-title" data-testid="risk-form-title">{{ LABELS[mode] }}</h3>
      <label class="ent-field">
        <span>原因 <em>*</em></span>
        <textarea
          v-model="reason"
          rows="3"
          :maxlength="REASON_MAX"
          :disabled="confirming"
          :aria-invalid="Boolean(reasonError)"
          data-testid="field-risk-reason"
        />
        <small v-if="reasonError" class="field-error" data-testid="error-risk-reason">{{ reasonError }}</small>
      </label>
      <div v-if="!confirming" class="ent-actions">
        <button type="submit" class="ent-button ent-primary" data-testid="risk-next">下一步：确认</button>
        <button type="button" class="ent-button" data-testid="risk-cancel" @click="close">取消</button>
      </div>
      <div v-else class="ent-flash warning" role="alert" data-testid="risk-confirm-panel">
        {{ confirmText }}
        <div class="ent-actions">
          <button
            type="button"
            class="ent-button"
            :class="mode === 'FREEZE' ? 'ent-danger' : 'ent-primary'"
            :disabled="submitting"
            data-testid="risk-confirm"
            @click="submit"
          >
            {{ submitting ? '提交中…' : `确认${LABELS[mode]}` }}
          </button>
          <button type="button" class="ent-button" :disabled="submitting" data-testid="risk-back" @click="confirming = false">返回修改</button>
        </div>
      </div>
    </form>

    <p v-if="writeError" class="ent-flash error" role="alert" data-testid="risk-error">{{ writeError }}</p>

    <h3 class="form-title history-title">风险转换历史</h3>
    <div v-if="loadState === 'loading'" class="ent-state" data-testid="risk-history-loading">正在加载风险转换历史…</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-muted" data-testid="risk-history-forbidden">无权查看该批次的风险转换历史。</div>
    <div v-else-if="loadState === 'error'" class="ent-state error" data-testid="risk-history-error">
      风险转换历史加载失败
      <button type="button" class="ent-button" data-testid="risk-history-retry" @click="load">重试</button>
    </div>
    <p v-else-if="transitions.length === 0" class="ent-muted" data-testid="risk-history-empty">该批次暂无风险冻结或解除记录。</p>
    <ol v-else class="risk-history" data-testid="risk-history">
      <li v-for="t in transitions" :key="t.id" data-testid="risk-transition-row" :data-to-status="t.toStatus" :data-flow-status="t.flowStatus">
        <div class="risk-row-head">
          <strong>{{ transitionLabel(t) }}</strong>
          <span class="ent-muted">{{ formatIsoDateTime(t.occurredAt) }}</span>
        </div>
        <div>
          {{ formatRiskStatus(t.fromStatus).label }} → {{ formatRiskStatus(t.toStatus).label }}
          <span class="ent-muted">（流转状态：{{ formatFlowStatus(t.flowStatus).label }}，不变）</span>
        </div>
        <div data-testid="risk-transition-reason">原因：{{ t.reason }}</div>
        <div class="ent-muted" data-testid="risk-transition-source" :data-source-type="t.sourceType">
          {{ orgLabel(t.orgId) }} · {{ sourceLabel(t) }}
          <RouterLink v-if="t.sourceAlertId" :to="`/app/alerts/${t.sourceAlertId}`" data-testid="risk-transition-alert-link">查看告警</RouterLink>
          <RouterLink v-if="t.sourceRecallId" :to="`/app/recalls/${t.sourceRecallId}`" data-testid="risk-transition-recall-link">查看模拟召回</RouterLink>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.risk-dl {
  margin-bottom: 12px;
}
.form-title {
  margin: 12px 0;
  font-size: 15px;
}
.history-title {
  margin-top: 16px;
}
.risk-history {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-size: 13px;
}
.risk-holds {
  margin: 0 0 12px;
  font-size: 13px;
}
.risk-holds ul,
[data-testid='risk-recall-notices'] ul {
  margin: 4px 0;
  padding-left: 18px;
}
.risk-row-head {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: baseline;
}
</style>
