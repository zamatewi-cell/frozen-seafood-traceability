<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { activatePublicTraceCode, consumerTracePath, disablePublicTraceCode, getPublicTraceCode } from '@/api/publicTraceCodes'
import { ApiError } from '@/api/client'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import type { Batch, CurrentUser, PublicTraceCode } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatIsoDateTime, formatPublicTraceCodeStatus } from '@/utils/formatters'
import { canActivatePublicTraceCode, canManagePublicTraceCode } from '@/utils/permissions'

/**
 * 公开追溯码管理（统一业务契约 v1.1 §9.2 / §12 第 18 步）：批次当前责任组织查看、激活、复制消费者入口与停用。
 * 一批一码，交接不换码；已激活的码在批次关闭后继续可查询；停用是终态，不能重新激活或更换。
 * 公开追溯码只是消费者查询入口，不是防伪或认证凭证。服务端是最终权限边界。
 */
const props = defineProps<{
  batch: Batch
  user: CurrentUser | null
}>()

const emit = defineEmits<{
  changed: [code: PublicTraceCode]
}>()

type LoadState = 'loading' | 'loaded' | 'error'
const loadState = ref<LoadState>('loading')
const code = ref<PublicTraceCode | null>(null)
const writing = ref(false)
const writeError = ref('')
const confirmDisable = ref(false)
const copyMessage = ref('')
const writer = useIdempotentWrite()
let controller: AbortController | null = null

const canManage = computed(() => canManagePublicTraceCode(props.user, props.batch))
const canActivate = computed(() => canActivatePublicTraceCode(props.user, props.batch))
const consumerPath = computed(() => (code.value ? consumerTracePath(code.value.publicId) : ''))
const consumerUrl = computed(() => (consumerPath.value ? `${window.location.origin}${consumerPath.value}` : ''))
const queryable = computed(() => Boolean(code.value && code.value.status !== 'DISABLED'))

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await getPublicTraceCode(props.batch.id, current.signal)
    if (current.signal.aborted) return
    code.value = result
    loadState.value = 'loaded'
  } catch {
    if (current.signal.aborted) return
    loadState.value = 'error'
  }
}

async function activate() {
  if (writing.value) return
  writing.value = true
  writeError.value = ''
  try {
    const result = await writer.run(
      `public-trace-code:activate:${props.batch.id}`,
      () => ({}),
      (_payload, key) => activatePublicTraceCode(props.batch.id, key)
    )
    code.value = result
    emit('changed', result)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    writeError.value = describeWriteError(err, '激活公开追溯码')
    if (err instanceof ApiError && (err.status === 409 || err.status === 422)) await load()
  } finally {
    writing.value = false
  }
}

async function disable() {
  if (writing.value || !code.value) return
  writing.value = true
  writeError.value = ''
  try {
    const result = await writer.run(
      `public-trace-code:disable:${props.batch.id}`,
      () => ({}),
      (_payload, key) => disablePublicTraceCode(props.batch.id, key)
    )
    code.value = result
    confirmDisable.value = false
    emit('changed', result)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    writeError.value = describeWriteError(err, '停用公开追溯码')
    if (err instanceof ApiError && (err.status === 409 || err.status === 422)) await load()
  } finally {
    writing.value = false
  }
}

async function copyLink() {
  copyMessage.value = ''
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(consumerUrl.value)
    copyMessage.value = '已复制消费者查询链接。'
  } catch {
    copyMessage.value = '无法访问剪贴板，请手动复制下方链接。'
  }
}

watch(() => [props.batch.id, props.batch.version], () => {
  confirmDisable.value = false
  load()
}, { immediate: true })

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="ent-card" aria-labelledby="public-code-title" data-testid="public-code-panel">
    <h2 id="public-code-title" class="ent-card-title">公开追溯码</h2>
    <p class="section-note">
      消费者通过公开追溯码匿名查询本批次及其上游来源的公开信息。一批一码，交接不换码；批次关闭后已激活的码继续可查询。
      公开追溯码只是查询入口，不代表防伪或认证。
    </p>

    <div v-if="loadState === 'loading'" class="ent-state" data-testid="public-code-loading">正在加载公开追溯码…</div>
    <div v-else-if="loadState === 'error'" class="ent-state error" role="alert" data-testid="public-code-load-error">
      公开追溯码加载失败
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <template v-else-if="!code">
      <p class="ent-muted" data-testid="public-code-none">该批次尚未激活公开追溯码。</p>
      <div v-if="canActivate" class="ent-actions">
        <button type="button" class="ent-button ent-primary" :disabled="writing" data-testid="public-code-activate" @click="activate">
          {{ writing ? '激活中…' : '激活公开追溯码' }}
        </button>
        <small class="ent-muted">正常流程：接收交接后、终端销售前激活。</small>
      </div>
      <p v-else-if="canManage && batch.flowStatus === 'CLOSED'" class="ent-muted" data-testid="public-code-closed-none">
        批次已关闭：已激活的码会继续可查询，但关闭后不能首次激活公开追溯码。
      </p>
    </template>

    <template v-else>
      <dl class="ent-dl code-dl">
        <dt>状态</dt>
        <dd>
          <StatusBadge :info="formatPublicTraceCodeStatus(code.status)" dimension="公开追溯码" data-testid="public-code-status" :data-status="code.status" />
        </dd>
        <dt>公开追溯码</dt>
        <dd class="mono" data-testid="public-code-value">{{ code.publicId }}</dd>
        <dt>激活时间</dt>
        <dd>{{ formatIsoDateTime(code.activatedAt) }}</dd>
        <template v-if="code.disabledAt">
          <dt>停用时间</dt>
          <dd>{{ formatIsoDateTime(code.disabledAt) }}</dd>
        </template>
      </dl>

      <template v-if="queryable">
        <label class="ent-field">
          <span>消费者查询链接</span>
          <input :value="consumerUrl" readonly class="mono" data-testid="public-code-url" @focus="($event.target as HTMLInputElement).select()" />
        </label>
        <div class="ent-actions">
          <RouterLink :to="consumerPath" target="_blank" rel="noopener" class="ent-button ent-primary" data-testid="public-code-link">打开消费者查询页</RouterLink>
          <button type="button" class="ent-button" data-testid="public-code-copy" @click="copyLink">复制查询链接</button>
          <button
            v-if="canManage && code.status === 'ACTIVE' && !confirmDisable"
            type="button"
            class="ent-button"
            :disabled="writing"
            data-testid="public-code-disable"
            @click="confirmDisable = true"
          >
            停用
          </button>
        </div>
        <p v-if="copyMessage" class="ent-muted" role="status" data-testid="public-code-copy-message">{{ copyMessage }}</p>
        <div v-if="confirmDisable" class="ent-flash warning" role="alert" data-testid="public-code-disable-confirm-panel">
          停用是终态：停用后消费者查询将与未知码一样显示“未找到”，且不能重新激活或更换新码。确认停用？
          <div class="ent-actions">
            <button type="button" class="ent-button ent-danger" :disabled="writing" data-testid="public-code-disable-confirm" @click="disable">
              {{ writing ? '停用中…' : '确认停用' }}
            </button>
            <button type="button" class="ent-button" :disabled="writing" data-testid="public-code-disable-cancel" @click="confirmDisable = false">取消</button>
          </div>
        </div>
      </template>
      <p v-else class="ent-muted" data-testid="public-code-disabled-note">
        公开追溯码已停用（终态）：消费者查询显示未找到，不能重新激活或更换。
      </p>
    </template>

    <p v-if="writeError" class="ent-flash error" role="alert" data-testid="public-code-error">{{ writeError }}</p>
  </section>
</template>

<style scoped>
.section-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.code-dl {
  margin-bottom: 12px;
}
</style>
