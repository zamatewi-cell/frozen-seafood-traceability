<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TraceSearchForm from '@/components/TraceSearchForm.vue'
import TraceHero from '@/components/TraceHero.vue'
import TraceRecallAlert from '@/components/TraceRecallAlert.vue'
import TraceProductCard from '@/components/TraceProductCard.vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import TraceTemperatureCard from '@/components/TraceTemperatureCard.vue'
import TraceSkeleton from '@/components/TraceSkeleton.vue'
import TraceNotFound from '@/components/TraceNotFound.vue'
import TraceError from '@/components/TraceError.vue'
import TraceDisclosure from '@/components/TraceDisclosure.vue'
import { fetchPublicTrace } from '@/api/trace'
import { ApiError, NotFoundError } from '@/api/client'
import type { PublicTrace, TraceViewState } from '@/types/trace'
import { normalizePublicTraceId } from '@/utils/validation'

const route = useRoute()
const router = useRouter()

const viewState = ref<TraceViewState>('INITIAL')
const traceData = ref<PublicTrace | null>(null)
const queriedCode = ref('')
const errorMessage = ref('')
const errorRequestId = ref<string | undefined>()

let activeRequest: AbortController | null = null
let requestSequence = 0

async function executeQuery(rawId: string) {
  const code = normalizePublicTraceId(rawId || '')
  queriedCode.value = code

  activeRequest?.abort()
  activeRequest = null
  const sequence = ++requestSequence

  if (!code) {
    viewState.value = 'INITIAL'
    traceData.value = null
    return
  }

  viewState.value = 'LOADING'
  errorMessage.value = ''
  errorRequestId.value = undefined

  const controller = new AbortController()
  activeRequest = controller

  try {
    const data = await fetchPublicTrace(code, controller.signal)
    if (sequence !== requestSequence) return
    traceData.value = data
    viewState.value = 'SUCCESS'
  } catch (err: unknown) {
    if (controller.signal.aborted || sequence !== requestSequence) return
    traceData.value = null
    if (err instanceof NotFoundError) {
      viewState.value = 'NOT_FOUND'
    } else if (err instanceof ApiError) {
      viewState.value = 'ERROR'
      errorMessage.value = err.status === 408
        ? '查询超时，请检查网络连接后重试'
        : err.status === 0
          ? '网络连接失败，请稍后重试'
          : '查询服务暂时不可用，请稍后重试'
      errorRequestId.value = err.requestId
    } else {
      viewState.value = 'ERROR'
      errorMessage.value = '网络请求失败，请稍后重试'
    }
  } finally {
    if (sequence === requestSequence) activeRequest = null
  }
}

function handleSearch(code: string) {
  router.push(`/trace/${encodeURIComponent(code)}`)
}

function handleReset() {
  router.push('/trace')
}

watch(
  () => route.params.publicTraceId,
  (newId) => {
    if (typeof newId === 'string' && newId.trim()) {
      executeQuery(newId)
    } else {
      activeRequest?.abort()
      activeRequest = null
      requestSequence += 1
      viewState.value = 'INITIAL'
      traceData.value = null
      queriedCode.value = ''
    }
  },
  { immediate: true }
)
</script>

<template>
  <div class="consumer-trace-container">
    <TraceSearchForm
      :initial-value="queriedCode"
      :loading="viewState === 'LOADING'"
      @search="handleSearch"
    />

    <TraceSkeleton v-if="viewState === 'LOADING'" />

    <TraceNotFound
      v-else-if="viewState === 'NOT_FOUND'"
      :trace-id="queriedCode"
      @retry="handleReset"
    />

    <TraceError
      v-else-if="viewState === 'ERROR'"
      :message="errorMessage"
      :request-id="errorRequestId"
      @retry="() => executeQuery(queriedCode)"
    />

    <div v-else-if="viewState === 'SUCCESS' && traceData" class="trace-content-grid">
      <TraceRecallAlert
        v-if="traceData.batchStatus === 'RECALLED'"
        :notice="traceData.recallNotice"
      />

      <div class="desktop-layout-row">
        <div class="main-column">
          <TraceHero :trace="traceData" />
          <TraceProductCard :product="traceData.product" :batch="traceData.batch" />
          <TraceTemperatureCard :temperature-summary="traceData.temperatureSummary" />
          <TraceDisclosure
            :queried-at="traceData.queriedAt"
            :disclosure="traceData.disclosure"
          />
        </div>

        <div class="timeline-column">
          <TraceTimeline :timeline="traceData.timeline" />
        </div>
      </div>
    </div>

    <div v-else class="initial-guidance-card">
      <h2 class="guidance-hero-title">欢迎使用冷冻海产品溯源服务</h2>
      <p class="guidance-hero-desc">
        请在上方搜索框输入商品包装标签上的 26 位公开追溯标识码，查验批次电子履历、产地信息与冷链温控摘要。
      </p>
      <div class="feature-bullets">
        <div class="bullet-item">
          <strong>白名单安全脱敏</strong>
          <span>严格保护各节点商业机密，仅展示合规脱敏信息</span>
        </div>
        <div class="bullet-item">
          <strong>全链条事件留痕</strong>
          <span>捕捞、加工、冷库、冷链物流全流程时间记录</span>
        </div>
        <div class="bullet-item">
          <strong>诚实透明披露</strong>
          <span>如实标识教学演练与仿真模拟数据，不作虚假背书</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.consumer-trace-container {
  max-width: 980px;
  margin: 0 auto;
}
.desktop-layout-row {
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
}
@media (min-width: 860px) {
  .desktop-layout-row {
    grid-template-columns: 1.1fr 1fr;
    align-items: start;
  }
}
.initial-guidance-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 32px 24px;
  text-align: center;
  box-shadow: var(--shadow-sm);
}
.guidance-hero-title {
  margin: 0 0 10px;
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text-title);
}
.guidance-hero-desc {
  max-width: 580px;
  margin: 0 auto 24px;
  font-size: 14px;
  color: var(--color-text-muted);
  line-height: 1.6;
}
.feature-bullets {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  margin-top: 16px;
  text-align: left;
}
.bullet-item {
  padding: 14px;
  background-color: #f8fafc;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.bullet-item strong {
  font-size: 13px;
  color: var(--color-ocean-hover);
}
.bullet-item span {
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
