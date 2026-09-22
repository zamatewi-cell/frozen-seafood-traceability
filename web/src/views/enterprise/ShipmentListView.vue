<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { listShipments } from '@/api/shipments'
import { useSession } from '@/stores/session'
import { SHIPMENT_STATUSES, type Shipment, type ShipmentRole, type ShipmentStatus } from '@/types/enterprise'
import { formatIsoDateTime, formatShipmentStatus } from '@/utils/formatters'
import { canShip, isCarrierOperator } from '@/utils/permissions'

const route = useRoute()
const router = useRouter()
const { user } = useSession()

const ROLE_TABS: Array<{ role: ShipmentRole; label: string }> = [
  { role: 'SENDER', label: '我发起的' },
  { role: 'CARRIER', label: '我承运的' },
  { role: 'RECEIVER', label: '发往我的' }
]

const defaultRole = computed<ShipmentRole>(() => (isCarrierOperator(user.value) ? 'CARRIER' : 'SENDER'))
const role = computed<ShipmentRole>(() => {
  const r = String(route.query.role ?? '')
  return ROLE_TABS.some((t) => t.role === r) ? (r as ShipmentRole) : defaultRole.value
})
const status = computed<ShipmentStatus | undefined>(() => {
  const s = String(route.query.status ?? '')
  return (SHIPMENT_STATUSES as readonly string[]).includes(s) ? (s as ShipmentStatus) : undefined
})

const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref('')
const shipments = ref<Shipment[]>([])
const total = ref(0)

let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await listShipments({ role: role.value, status: status.value, page: 1, size: 50 }, current.signal)
    if (current.signal.aborted) return
    shipments.value = result.items
    total.value = result.page.totalElements
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '运输任务加载失败，请稍后重试'
  }
}

function setQuery(next: { role?: ShipmentRole; status?: string }) {
  router.push({ path: '/app/shipments', query: { role: next.role ?? role.value, status: next.status ?? status.value } })
}

watch(() => route.fullPath, load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="shipment-list-page">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">运输任务</h1>
        <p class="ent-page-subtitle">运输任务表达物理冷链运输；发货方、承运方与接收方都可以查看自己参与的运输任务。</p>
      </div>
      <RouterLink v-if="canShip(user)" to="/app/shipments/new" class="ent-button ent-primary" data-testid="new-shipment">新建运输任务</RouterLink>
    </div>

    <div class="ent-tabs" role="group" aria-label="运输任务视角">
      <button
        v-for="tab in ROLE_TABS"
        :key="tab.role"
        type="button"
        class="ent-button"
        :aria-pressed="role === tab.role"
        :data-testid="`shipment-tab-${tab.role}`"
        @click="setQuery({ role: tab.role, status: '' })"
      >
        {{ tab.label }}
      </button>
      <select
        class="ent-button status-filter"
        :value="status ?? ''"
        data-testid="shipment-status-filter"
        aria-label="按运输状态筛选"
        @change="setQuery({ status: ($event.target as HTMLSelectElement).value })"
      >
        <option value="">全部状态</option>
        <option v-for="s in SHIPMENT_STATUSES" :key="s" :value="s">{{ formatShipmentStatus(s).label }}</option>
      </select>
    </div>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="shipments-loading">正在加载运输任务…</div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>
    <div v-else-if="shipments.length === 0" class="ent-card ent-state" data-testid="shipments-empty">暂无运输任务。</div>
    <div v-else class="ent-card ent-table-scroll">
      <table class="ent-table" data-testid="shipment-table">
        <thead>
          <tr><th>运输单号</th><th>状态</th><th>发货方</th><th>承运方</th><th>接收方</th><th>交接数</th><th>更新时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in shipments" :key="s.id" data-testid="shipment-row" :data-shipment-id="s.id">
            <td class="mono"><RouterLink :to="`/app/shipments/${s.id}`" :data-testid="`open-shipment-${s.id}`">{{ s.shipmentNo }}</RouterLink></td>
            <td><StatusBadge :info="formatShipmentStatus(s.status)" dimension="运输" /></td>
            <td>{{ s.senderOrg.name }}</td>
            <td>{{ s.carrierOrg.name }}</td>
            <td>{{ s.receiverOrg.name }}</td>
            <td>{{ s.transfers.length }}</td>
            <td>{{ formatIsoDateTime(s.updatedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p class="ent-muted total">共 {{ total }} 条</p>
    </div>
  </div>
</template>

<style scoped>
.status-filter {
  min-width: 140px;
}
.total {
  margin: 10px 0 0;
  font-size: 12px;
}
</style>
