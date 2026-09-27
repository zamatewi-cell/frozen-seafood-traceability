<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { startRecall } from '@/api/recalls'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { describeWriteError } from '@/utils/apiErrors'

/**
 * 发起模拟召回（Phase B PB5；统一业务契约 v1.1 §4.2 / §13 步骤 8–12 / §14）：当前责任组织的质量管理员填写原因并二次确认。
 * 服务端圈定反向上游与正向后续批次、快照库存 / 在途 / 已售事实，并把应召回批次转为 RECALLED（风险终态）；
 * NORMAL 批次需要证据（最新检验不合格或已被上游召回圈定）。成功后进入召回详情。这是教学演练，不代表真实法定召回。
 */
const props = defineProps<{
  batchIds: number[]
  /** 批次标识（用于确认文案）。 */
  label: string
  alertId?: number
  /** 批次当前风险正常：提示需要证据。 */
  normal?: boolean
}>()

const emit = defineEmits<{
  conflict: [message: string]
}>()

const router = useRouter()
const writer = useIdempotentWrite()
const open = ref(false)
const confirming = ref(false)
const reason = ref('')
const error = ref('')
const busy = ref(false)
const REASON_MAX = 500

function start() {
  open.value = true
  confirming.value = false
  reason.value = ''
  error.value = ''
}

function next() {
  const trimmed = reason.value.trim()
  error.value = !trimmed ? '请填写召回原因' : trimmed.length > REASON_MAX ? `召回原因不能超过 ${REASON_MAX} 个字符` : ''
  if (!error.value) confirming.value = true
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const payload = { batchIds: [...props.batchIds].sort((a, b) => a - b), reason: reason.value.trim(),
    ...(props.alertId ? { alertId: props.alertId } : {}) }
  try {
    const recall = await writer.run(
      `recall:${JSON.stringify(payload)}`,
      () => payload,
      (body, key) => startRecall(body, key)
    )
    await router.push(`/app/recalls/${recall.id}`)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    const message = describeWriteError(err, '发起模拟召回')
    confirming.value = false
    if (err instanceof ApiError && err.status === 409) {
      open.value = false
      emit('conflict', message)
    } else {
      error.value = message
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="recall-start" data-testid="recall-start">
    <div v-if="!open" class="ent-actions">
      <button type="button" class="ent-button ent-danger" data-testid="recall-start-open" @click="start">发起模拟召回</button>
    </div>
    <form v-else novalidate data-testid="recall-start-form" @submit.prevent="next">
      <p v-if="normal" class="ent-muted" data-testid="recall-evidence-hint">
        批次风险状态正常：只有最新检验报告不合格，或该批次已被上游召回圈定时，才能直接进入模拟召回；否则请先风险冻结并调查。
      </p>
      <label class="ent-field">
        <span>召回原因 <em>*</em></span>
        <textarea v-model="reason" rows="2" :maxlength="REASON_MAX" :disabled="confirming" data-testid="field-recall-reason" />
      </label>
      <div v-if="!confirming" class="ent-actions">
        <button type="submit" class="ent-button ent-danger" data-testid="recall-start-next">下一步：确认</button>
        <button type="button" class="ent-button" data-testid="recall-start-cancel" @click="open = false">取消</button>
      </div>
      <div v-else class="ent-flash warning" role="alert" data-testid="recall-start-confirm-panel">
        确认对 {{ label }} 发起模拟召回？系统将圈定上游与后续批次并记录库存、在途与已售事实；本组织持有的应召回批次转为“模拟召回”风险终态，
        之后不能再销售、交接、加工或解除；已售罄批次保持已关闭。模拟召回是教学演练，不代表真实法定召回。
        <div class="ent-actions">
          <button type="button" class="ent-button ent-danger" :disabled="busy" data-testid="recall-start-confirm" @click="submit">
            {{ busy ? '提交中…' : '确认发起模拟召回' }}
          </button>
          <button type="button" class="ent-button" :disabled="busy" data-testid="recall-start-back" @click="confirming = false">返回修改</button>
        </div>
      </div>
    </form>
    <p v-if="error" class="ent-flash error" role="alert" data-testid="recall-start-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.recall-start {
  margin-top: 8px;
}
</style>
