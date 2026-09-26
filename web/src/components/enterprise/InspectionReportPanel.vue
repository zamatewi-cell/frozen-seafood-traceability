<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { listInspectionReports, submitInspectionReport } from '@/api/inspectionReports'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { InspectionConclusion, InspectionReport } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatDataSource, formatInspectionConclusion, formatInspectionSubmitter, formatIsoDateTime } from '@/utils/formatters'
import { toLocalDateTimeInput } from '@/utils/datetime'

/**
 * 批次检验报告（Phase B PB4；统一业务契约 v1.1 §2.10 / §10.3）：结构化检验证据的列表与提交。
 * 报告只是证据——不自动放行、冻结或召回，也不核验检验机构的资质与真实性；可关联告警（放行依据关联告警的最新结论）。
 * 提交入口由父组件按角色判定显示，服务端按批次行锁下的当前事实最终判定提交身份。
 */
const props = defineProps<{
  batchId: number
  /** 关联告警（在告警详情中提交时）；批次必须是该告警的受影响批次。 */
  alertId?: number
  canSubmit: boolean
  /** 只展示关联该告警的报告。 */
  onlyAlert?: boolean
}>()

const emit = defineEmits<{
  submitted: [report: InspectionReport]
}>()

type LoadState = 'loading' | 'loaded' | 'forbidden' | 'error'
const loadState = ref<LoadState>('loading')
const reports = ref<InspectionReport[]>([])
const open = ref(false)
const confirming = ref(false)
const busy = ref(false)
const error = ref('')
const form = reactive({
  reportNo: '',
  institutionName: '',
  inspectedAt: toLocalDateTimeInput(new Date()),
  itemsSummary: '',
  conclusion: '' as '' | InspectionConclusion,
  dataSource: 'SIMULATED' as 'MANUAL' | 'SIMULATED'
})
const writer = useIdempotentWrite()
let controller: AbortController | null = null

const visible = computed(() => (props.onlyAlert && props.alertId
  ? reports.value.filter((r) => r.alertId === props.alertId)
  : reports.value))

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await listInspectionReports(props.batchId, current.signal)
    if (current.signal.aborted) return
    reports.value = result
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    reports.value = []
    loadState.value = err instanceof ApiError && err.status === 403 ? 'forbidden' : 'error'
  }
}

function start() {
  open.value = true
  confirming.value = false
  error.value = ''
  form.reportNo = ''
  form.institutionName = ''
  form.inspectedAt = toLocalDateTimeInput(new Date())
  form.itemsSummary = ''
  form.conclusion = ''
  form.dataSource = 'SIMULATED'
}

function validate(): string {
  if (!form.reportNo.trim() || form.reportNo.trim().length > 64) return '请填写报告编号（最多 64 个字符）'
  if (!form.institutionName.trim() || form.institutionName.trim().length > 128) return '请填写检验机构名称（最多 128 个字符）'
  if (!form.itemsSummary.trim() || form.itemsSummary.trim().length > 500) return '请填写检测项目摘要（最多 500 个字符）'
  if (!form.inspectedAt || Number.isNaN(new Date(form.inspectedAt).getTime())) return '请选择检验完成时间'
  if (new Date(form.inspectedAt).getTime() > Date.now() + 5 * 60_000) return '检验完成时间不能晚于当前时间'
  if (form.conclusion !== 'PASS' && form.conclusion !== 'FAIL') return '请选择检验结论'
  return ''
}

function next() {
  error.value = validate()
  if (!error.value) confirming.value = true
}

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  const payload = {
    reportNo: form.reportNo.trim(),
    institutionName: form.institutionName.trim(),
    inspectedAt: new Date(form.inspectedAt).toISOString(),
    itemsSummary: form.itemsSummary.trim(),
    conclusion: form.conclusion as InspectionConclusion,
    dataSource: form.dataSource,
    ...(props.alertId ? { alertId: props.alertId } : {})
  }
  try {
    const report = await writer.run(
      `inspection:${props.batchId}:${JSON.stringify(payload)}`,
      () => payload,
      (body, key) => submitInspectionReport(props.batchId, body, key)
    )
    open.value = false
    confirming.value = false
    await load()
    emit('submitted', report)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    error.value = describeWriteError(err, '提交检验报告')
    confirming.value = false
  } finally {
    busy.value = false
  }
}

watch(() => [props.batchId, props.alertId], load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="inspection-panel" data-testid="inspection-panel" :data-batch-id="batchId">
    <p class="section-note" data-testid="inspection-disclaimer">
      检验报告是教学演示中的结构化证据：结论不会自动放行、冻结或召回批次，本系统也不核验检验机构的资质与真实性。
    </p>
    <div v-if="loadState === 'loading'" class="ent-muted" data-testid="inspection-loading">正在加载检验报告…</div>
    <div v-else-if="loadState === 'forbidden'" class="ent-muted" data-testid="inspection-forbidden">无权查看该批次的检验报告。</div>
    <div v-else-if="loadState === 'error'" class="ent-state error" data-testid="inspection-error">
      检验报告加载失败
      <button type="button" class="ent-button" data-testid="inspection-retry" @click="load">重试</button>
    </div>
    <p v-else-if="visible.length === 0" class="ent-muted" data-testid="inspection-empty">暂无检验报告。</p>
    <ol v-else class="inspection-list">
      <li v-for="r in visible" :key="r.id" data-testid="inspection-row" :data-conclusion="r.conclusion">
        <div class="row-head">
          <StatusBadge :info="formatInspectionConclusion(r.conclusion)" dimension="检验" data-testid="inspection-conclusion" />
          <strong class="mono">{{ r.reportNo }}</strong>
          <span class="ent-muted">{{ formatIsoDateTime(r.inspectedAt) }}</span>
        </div>
        <div>{{ r.institutionName }} · {{ r.itemsSummary }}</div>
        <div class="ent-muted">{{ formatInspectionSubmitter(r.submitterRole) }} · {{ formatDataSource(r.dataSource) }}</div>
      </li>
    </ol>

    <div v-if="canSubmit && !open" class="ent-actions">
      <button type="button" class="ent-button" data-testid="inspection-open" @click="start">提交检验报告</button>
    </div>
    <form v-if="open" novalidate data-testid="inspection-form" @submit.prevent="next">
      <div class="ent-form-grid">
        <label class="ent-field">
          <span>报告编号 <em>*</em></span>
          <input v-model="form.reportNo" maxlength="64" :disabled="confirming" data-testid="field-inspection-no" />
        </label>
        <label class="ent-field">
          <span>检验机构 <em>*</em></span>
          <input v-model="form.institutionName" maxlength="128" :disabled="confirming" data-testid="field-inspection-institution" />
        </label>
        <label class="ent-field">
          <span>检验完成时间 <em>*</em></span>
          <input v-model="form.inspectedAt" type="datetime-local" step="1" :disabled="confirming" data-testid="field-inspection-at" />
        </label>
        <label class="ent-field">
          <span>检验结论 <em>*</em></span>
          <select v-model="form.conclusion" :disabled="confirming" data-testid="field-inspection-conclusion">
            <option value="">请选择</option>
            <option value="PASS">合格</option>
            <option value="FAIL">不合格</option>
          </select>
        </label>
        <label class="ent-field">
          <span>数据来源</span>
          <select v-model="form.dataSource" :disabled="confirming" data-testid="field-inspection-source">
            <option value="SIMULATED">教学模拟数据</option>
            <option value="MANUAL">人工登记</option>
          </select>
        </label>
        <label class="ent-field wide">
          <span>检测项目摘要 <em>*</em></span>
          <input v-model="form.itemsSummary" maxlength="500" :disabled="confirming" data-testid="field-inspection-items" />
        </label>
      </div>
      <div v-if="!confirming" class="ent-actions">
        <button type="submit" class="ent-button ent-primary" data-testid="inspection-next">下一步：确认</button>
        <button type="button" class="ent-button" data-testid="inspection-cancel" @click="open = false">取消</button>
      </div>
      <div v-else class="ent-flash warning" role="alert" data-testid="inspection-confirm-panel">
        确认提交检验报告？报告登记后不可修改或删除；{{ alertId ? '关联本告警的最新结论将作为发货方放行的依据。' : '结论不会自动改变批次状态。' }}
        <div class="ent-actions">
          <button type="button" class="ent-button ent-primary" :disabled="busy" data-testid="inspection-confirm" @click="submit">
            {{ busy ? '提交中…' : '确认提交' }}
          </button>
          <button type="button" class="ent-button" :disabled="busy" data-testid="inspection-back" @click="confirming = false">返回修改</button>
        </div>
      </div>
    </form>
    <p v-if="error" class="ent-flash error" role="alert" data-testid="inspection-submit-error">{{ error }}</p>
  </div>
</template>

<style scoped>
.section-note {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.inspection-list {
  margin: 0 0 8px;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
}
.row-head {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}
</style>
