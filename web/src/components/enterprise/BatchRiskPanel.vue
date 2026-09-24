<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { freezeBatch, listRiskTransitions, releaseBatch } from '@/api/batchRisk'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { Batch, BatchRiskTransition, CurrentUser } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatFlowStatus, formatIsoDateTime, formatRiskStatus } from '@/utils/formatters'
import { canFreezeBatch, canReleaseBatch } from '@/utils/permissions'

/**
 * 批次风险状态（Phase B PB1；统一业务契约 v1.1 §4.2 / §4.3 / §14）：当前责任组织的质量管理员填写原因并二次确认后
 * 风险冻结（NORMAL → FROZEN）或解除冻结（FROZEN → NORMAL），ACTIVE 与 CLOSED 批次均可。
 * 风险转换只改变风险状态：不改变数量、当前责任组织与流转状态，不生成追溯事件；冻结期间的业务阻断由服务端既有守卫执行。
 * 这是教学实训中的模拟质量处置，不代表真实的产品扣留、安全判定或监管措施。服务端是最终权限边界。
 */
type Action = 'FREEZE' | 'RELEASE'

const props = defineProps<{
  batch: Batch
  user: CurrentUser | null
  orgLabel: (orgId: number) => string
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

const canFreeze = computed(() => canFreezeBatch(props.user, props.batch))
const canRelease = computed(() => canReleaseBatch(props.user, props.batch))
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
  try {
    const result = await listRiskTransitions(props.batch.id, current.signal)
    if (current.signal.aborted) return
    transitions.value = result
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    transitions.value = []
    loadState.value = err instanceof ApiError && err.status === 403 ? 'forbidden' : 'error'
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
  return t.toStatus === 'FROZEN' ? '风险冻结' : '解除冻结'
}

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
    <p v-else-if="batch.riskStatus === 'RECALLED'" class="ent-muted" data-testid="risk-recalled-note">
      批次已进入模拟召回（风险终态），不能再风险冻结或解除冻结。
    </p>

    <div v-if="!mode && (canFreeze || canRelease)" class="ent-actions">
      <button v-if="canFreeze" type="button" class="ent-button ent-danger" data-testid="risk-freeze-open" @click="open('FREEZE')">风险冻结</button>
      <button v-if="canRelease" type="button" class="ent-button ent-primary" data-testid="risk-release-open" @click="open('RELEASE')">解除冻结</button>
    </div>

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
        <div class="ent-muted">
          {{ orgLabel(t.orgId) }} · {{ t.sourceType === 'MANUAL' ? '质量管理员人工处置' : t.sourceType }}
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
.risk-row-head {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: baseline;
}
</style>
