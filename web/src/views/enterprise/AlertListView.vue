<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { listAlerts } from '@/api/alerts'
import { ApiError } from '@/api/client'
import { useSession } from '@/stores/session'
import { ALERT_STATUSES, type Alert, type AlertStatus } from '@/types/enterprise'
import { formatAlertStatus, formatAlertType, formatDurationSeconds, formatIsoDateTime } from '@/utils/formatters'

/**
 * 告警列表（Phase B PB3 起）：本组织归属的告警（发货方，负责处置），以及本组织作为运输任务接收方 / 承运方的告警（只读）。
 * 告警由系统按运输途中登记的温度记录判定持续超温后自动创建（教学演示数据），不代表真实监管结论。
 */
const route = useRoute()
const router = useRouter()
const { user } = useSession()

const status = computed<AlertStatus | undefined>(() => {
  const s = String(route.query.status ?? '')
  return (ALERT_STATUSES as readonly string[]).includes(s) ? (s as AlertStatus) : undefined
})

const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref('')
const alerts = ref<Alert[]>([])
let controller: AbortController | null = null

function relation(a: Alert): string {
  const orgId = user.value?.orgId
  if (a.orgId === orgId) return '本组织负责处置'
  if (a.receiverOrgId === orgId) return '本组织为接收方（只读）'
  if (a.carrierOrgId === orgId) return '本组织为承运方（只读）'
  return '只读'
}

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await listAlerts({ status: status.value }, current.signal)
    if (current.signal.aborted) return
    alerts.value = result
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '告警加载失败，请稍后重试'
  }
}

function setStatus(value: string) {
  router.push({ path: '/app/alerts', query: value ? { status: value } : {} })
}

watch(() => route.fullPath, load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="alert-list-page">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">告警</h1>
        <p class="ent-page-subtitle">
          在途温度连续越界达到规则允许时长后，系统自动创建运输任务级告警并冻结受影响批次；单点越界不会形成告警。
          本页数据为教学演示中人工 / 模拟登记的温度，不代表真实监管结论。
        </p>
      </div>
    </div>

    <div class="ent-tabs" role="group" aria-label="告警状态">
      <select
        class="ent-button status-filter"
        :value="status ?? ''"
        data-testid="alert-status-filter"
        aria-label="按告警状态筛选"
        @change="setStatus(($event.target as HTMLSelectElement).value)"
      >
        <option value="">全部状态</option>
        <option v-for="s in ALERT_STATUSES" :key="s" :value="s">{{ formatAlertStatus(s).label }}</option>
      </select>
    </div>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="alerts-loading">正在加载告警…</div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>
    <div v-else-if="alerts.length === 0" class="ent-card ent-state" data-testid="alerts-empty">暂无告警。</div>
    <div v-else class="ent-card ent-table-scroll">
      <table class="ent-table" data-testid="alert-table">
        <thead>
          <tr><th>告警编号</th><th>状态</th><th>类型</th><th>运输任务</th><th>达到持续超温</th><th>已持续</th><th>与本组织关系</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in alerts" :key="a.id" data-testid="alert-row" :data-alert-id="a.id" :data-status="a.status">
            <td class="mono"><RouterLink :to="`/app/alerts/${a.id}`" :data-testid="`open-alert-${a.id}`">{{ a.alertNo }}</RouterLink></td>
            <td><StatusBadge :info="formatAlertStatus(a.status)" dimension="告警" /></td>
            <td>{{ formatAlertType(a.alertType) }}</td>
            <td class="mono"><RouterLink :to="`/app/shipments/${a.shipmentId}`">{{ a.shipmentNo }}</RouterLink></td>
            <td>{{ formatIsoDateTime(a.sustainedAt) }}</td>
            <td>{{ formatDurationSeconds(a.durationSeconds) }}</td>
            <td>{{ relation(a) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.status-filter {
  min-width: 140px;
}
</style>
