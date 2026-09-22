<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { listOrganizations, listSites } from '@/api/directory'
import { bindTransfer, createShipment } from '@/api/shipments'
import { listTransfers } from '@/api/transfers'
import { useDirectoryLabels } from '@/composables/useDirectoryLabels'
import { useIdempotentWrite } from '@/composables/useIdempotentWrite'
import { useSession } from '@/stores/session'
import type { OrganizationSummary, SiteSummary, Transfer } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatQuantity, formatSiteType } from '@/utils/formatters'
import { canShip } from '@/utils/permissions'
import { setPageFlash, takePageFlash, type PageFlash } from './pageFlash'

const route = useRoute()
const router = useRouter()
const { user } = useSession()
const directory = useDirectoryLabels()
const createWriter = useIdempotentWrite()

const allowed = computed(() => canShip(user.value))
const flash = ref<PageFlash | null>(takePageFlash('shipment-create'))

type LoadState = 'loading' | 'loaded' | 'error'
const loadState = ref<LoadState>('loading')
const loadError = ref('')
const drafts = ref<Transfer[]>([])
const carriers = ref<OrganizationSummary[]>([])
const originSites = ref<SiteSummary[]>([])
const destinationSites = ref<SiteSummary[]>([])
const destinationLoading = ref(false)

const form = reactive({
  carrierOrgId: '',
  originSiteId: '',
  destinationSiteId: '',
  vehicleOrContainerNo: ''
})
const selectedTransferIds = ref<number[]>([])
const errors = reactive<Record<string, string>>({})
const submitting = ref(false)
const submitError = ref('')

/** 同一运输任务中的交接必须具有相同接收方：由第一张选中的交接决定。 */
const receiverOrgId = computed<number | null>(() => {
  const first = drafts.value.find((t) => selectedTransferIds.value.includes(t.id))
  return first ? first.receiverOrgId : null
})

function selectable(t: Transfer): boolean {
  return receiverOrgId.value === null || t.receiverOrgId === receiverOrgId.value
}

let controller: AbortController | null = null

async function load() {
  if (!allowed.value || !user.value) {
    loadState.value = 'loaded'
    return
  }
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const [draftPage, carrierOrgs, ownSites] = await Promise.all([
      listTransfers({ direction: 'SENT', status: 'DRAFT', page: 1, size: 100 }, current.signal),
      listOrganizations('CARRIER', current.signal),
      listSites(user.value.orgId, current.signal)
    ])
    if (current.signal.aborted) return
    drafts.value = draftPage.items.filter((t) => !t.shipmentId)
    carriers.value = carrierOrgs.filter((org) => org.status === 'ACTIVE')
    originSites.value = ownSites
    directory.resolveOrganizations(drafts.value.map((t) => t.receiverOrgId))
    const preselected = Number(route.query.transferId)
    if (Number.isInteger(preselected) && drafts.value.some((t) => t.id === preselected)) {
      selectedTransferIds.value = [preselected]
    }
    if (originSites.value.length === 1) form.originSiteId = String(originSites.value[0].id)
    if (carriers.value.length === 1) form.carrierOrgId = String(carriers.value[0].id)
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '页面加载失败，请稍后重试'
  }
}

watch(receiverOrgId, async (orgId) => {
  form.destinationSiteId = ''
  destinationSites.value = []
  if (orgId === null) return
  destinationLoading.value = true
  try {
    destinationSites.value = await listSites(orgId)
    if (destinationSites.value.length === 1) form.destinationSiteId = String(destinationSites.value[0].id)
  } catch {
    destinationSites.value = []
  } finally {
    destinationLoading.value = false
  }
})

function toggle(t: Transfer) {
  if (selectedTransferIds.value.includes(t.id)) {
    selectedTransferIds.value = selectedTransferIds.value.filter((id) => id !== t.id)
  } else if (selectable(t)) {
    selectedTransferIds.value = [...selectedTransferIds.value, t.id]
  }
}

function validate(): boolean {
  for (const key of Object.keys(errors)) delete errors[key]
  if (selectedTransferIds.value.length === 0) errors.transfers = '请至少选择一张交接草稿'
  if (!form.carrierOrgId) errors.carrierOrgId = '请选择承运企业'
  if (!form.originSiteId) errors.originSiteId = '请选择启运场所'
  if (!form.destinationSiteId) errors.destinationSiteId = '请选择目的场所'
  const vehicle = form.vehicleOrContainerNo.trim()
  if (!vehicle) errors.vehicleOrContainerNo = '请填写车牌号或冷藏集装箱编号'
  else if (vehicle.length > 64) errors.vehicleOrContainerNo = '不超过 64 个字符'
  return Object.keys(errors).length === 0
}

async function submit() {
  if (submitting.value || !validate()) return
  submitting.value = true
  submitError.value = ''
  const payload = {
    carrierOrgId: Number(form.carrierOrgId),
    originSiteId: Number(form.originSiteId),
    destinationSiteId: Number(form.destinationSiteId),
    vehicleOrContainerNo: form.vehicleOrContainerNo.trim()
  }
  try {
    const shipment = await createWriter.run(
      `create-shipment:${JSON.stringify(payload)}`,
      () => payload,
      (body, key) => createShipment(body, key)
    )
    // 逐张绑定选中的交接；单张失败不回滚已创建的运输任务，在详情页如实提示
    const failures: string[] = []
    for (const transferId of selectedTransferIds.value) {
      const transfer = drafts.value.find((t) => t.id === transferId)
      if (!transfer) continue
      try {
        await useIdempotentWrite().run(
          `bind:${shipment.id}:${transferId}`,
          () => ({ transferId, expectedTransferVersion: transfer.version }),
          (body, key) => bindTransfer(shipment.id, body, key)
        )
      } catch (err: unknown) {
        failures.push(`${transfer.transferNo}：${describeWriteError(err, '装载')}`)
      }
    }
    const bound = selectedTransferIds.value.length - failures.length
    setPageFlash(`shipment:${shipment.id}`, failures.length === 0
      ? { tone: 'success', message: `运输任务 ${shipment.shipmentNo} 已创建（PLANNED）并装载 ${bound} 张交接。下一步：逐张提交交接。` }
      : { tone: 'warning', message: `运输任务 ${shipment.shipmentNo} 已创建，但部分交接装载失败：${failures.join('；')}` })
    await router.push(`/app/shipments/${shipment.id}`)
  } catch (err: unknown) {
    if (err instanceof ApiError && err.status === 401) return
    submitError.value = describeWriteError(err, '创建运输任务')
  } finally {
    submitting.value = false
  }
}

watch(() => route.query.transferId, load, { immediate: true })
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="shipment-create-page">
    <RouterLink to="/app/shipments" class="ent-button ent-back-link">← 返回运输任务</RouterLink>

    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">新建运输任务</h1>
        <p class="ent-page-subtitle">
          运输任务表达物理冷链运输，不改变批次责任组织。创建后为计划中（PLANNED），可装载多张交接；
          同一运输任务中的交接必须具有相同接收方、起点与终点。
        </p>
      </div>
    </div>

    <p v-if="flash" class="ent-flash" :class="flash.tone" role="status" data-testid="shipment-create-flash">{{ flash.message }}</p>

    <div v-if="!allowed" class="ent-card ent-state error" role="alert" data-testid="shipment-create-forbidden">
      只有非承运企业的操作员可以作为发货方创建运输任务（Demo MVP 阶段承运方为独立承运企业）。
    </div>
    <div v-else-if="loadState === 'loading'" class="ent-card ent-state">正在加载…</div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>

    <form v-else class="ent-card" novalidate data-testid="shipment-create-form" @submit.prevent="submit">
      <h2 class="ent-card-title">1. 选择要装载的交接草稿</h2>
      <div v-if="drafts.length === 0" class="ent-state" data-testid="no-draft-transfers">
        本组织没有未装载的交接草稿。请先在批次详情页“发起交接”。
      </div>
      <div v-else class="ent-table-scroll">
        <table class="ent-table" data-testid="draft-transfer-table">
          <thead>
            <tr><th>装载</th><th>交接单号</th><th>追溯批次号</th><th>数量</th><th>接收企业</th></tr>
          </thead>
          <tbody>
            <tr v-for="t in drafts" :key="t.id" :data-transfer-id="t.id">
              <td>
                <input
                  type="checkbox"
                  :checked="selectedTransferIds.includes(t.id)"
                  :disabled="!selectable(t) && !selectedTransferIds.includes(t.id)"
                  :data-testid="`select-transfer-${t.id}`"
                  :aria-label="`装载交接 ${t.transferNo}`"
                  @change="toggle(t)"
                />
              </td>
              <td class="mono">{{ t.transferNo }}</td>
              <td class="mono">{{ t.traceBatchNo }}</td>
              <td>{{ formatQuantity(t.quantity, t.unitCode) }}</td>
              <td>{{ directory.organizationLabel(t.receiverOrgId) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <small v-if="errors.transfers" class="field-error" data-testid="error-transfers">{{ errors.transfers }}</small>

      <h2 class="ent-card-title section-gap">2. 运输安排</h2>
      <div class="ent-form-grid">
        <label class="ent-field">
          <span>承运企业 <em>*</em></span>
          <select v-model="form.carrierOrgId" data-testid="field-carrier">
            <option value="">请选择承运企业</option>
            <option v-for="org in carriers" :key="org.id" :value="String(org.id)">{{ org.name }}（{{ org.orgNo }}）</option>
          </select>
          <small v-if="errors.carrierOrgId" class="field-error">{{ errors.carrierOrgId }}</small>
        </label>
        <label class="ent-field">
          <span>车牌号 / 冷藏集装箱编号 <em>*</em></span>
          <input v-model="form.vehicleOrContainerNo" maxlength="64" data-testid="field-vehicle" placeholder="例如 浙A·12345" />
          <small v-if="errors.vehicleOrContainerNo" class="field-error">{{ errors.vehicleOrContainerNo }}</small>
        </label>
        <label class="ent-field">
          <span>启运场所（本企业） <em>*</em></span>
          <select v-model="form.originSiteId" data-testid="field-origin-site">
            <option value="">请选择启运场所</option>
            <option v-for="site in originSites" :key="site.id" :value="String(site.id)">{{ site.name }}（{{ formatSiteType(site.siteType) }}）</option>
          </select>
          <small v-if="errors.originSiteId" class="field-error">{{ errors.originSiteId }}</small>
          <small v-else-if="originSites.length === 0">本企业没有启用的场所，请联系平台管理员维护。</small>
        </label>
        <label class="ent-field">
          <span>目的场所（接收企业） <em>*</em></span>
          <select v-model="form.destinationSiteId" data-testid="field-destination-site" :disabled="receiverOrgId === null">
            <option value="">{{ receiverOrgId === null ? '请先选择交接草稿' : '请选择目的场所' }}</option>
            <option v-for="site in destinationSites" :key="site.id" :value="String(site.id)">{{ site.name }}（{{ formatSiteType(site.siteType) }}）</option>
          </select>
          <small v-if="errors.destinationSiteId" class="field-error">{{ errors.destinationSiteId }}</small>
          <small v-else-if="destinationLoading">正在加载接收企业场所…</small>
          <small v-else-if="receiverOrgId !== null">接收企业：{{ directory.organizationLabel(receiverOrgId) }}</small>
        </label>
      </div>

      <p v-if="submitError" class="ent-flash error" role="alert" data-testid="shipment-create-error">{{ submitError }}</p>
      <div class="ent-actions">
        <button type="submit" class="ent-button ent-primary" :disabled="submitting || drafts.length === 0" data-testid="create-shipment">
          {{ submitting ? '创建中…' : '创建运输任务并装载' }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.field-error {
  color: var(--color-danger-text);
}
.section-gap {
  margin-top: 18px;
}
</style>
