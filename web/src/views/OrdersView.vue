<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchCurrentUser, getCachedUser, logout, clearCachedUser, type CurrentUser } from '@/api/auth'
import {
  listPurchaseOrders,
  listSalesOrders,
  createPurchaseOrder,
  createSalesOrder,
  type PurchaseOrder,
  type SalesOrder
} from '@/api/orders'

const router = useRouter()

const user = ref<CurrentUser | null>(null)
const purchaseOrders = ref<PurchaseOrder[]>([])
const salesOrders = ref<SalesOrder[]>([])
const loading = ref(true)
const errorMessage = ref('')

// 创建订单面板状态
const showCreate = ref<'PURCHASE' | 'SALES' | null>(null)
const createMessage = ref('')
const createError = ref('')
const createSaving = ref(false)

const newPurchase = ref({
  sellerOrgName: '深海鲜冻加工厂',
  orderType: 'FINISHED_GOODS',
  productId: 1,
  productName: '东海带鱼',
  quantity: 50,
  unitPrice: 28,
  note: ''
})

const newSales = ref({
  customerName: '',
  customerPhone: '',
  deliveryAddress: '',
  productId: 1,
  productName: '东海带鱼',
  quantity: 5,
  unitPrice: 45,
  note: ''
})

const products = [
  { id: 1, name: '东海带鱼' },
  { id: 2, name: '南美白对虾' },
  { id: 3, name: '挪威冰鲜三文鱼' },
  { id: 4, name: '冷冻扇贝柱' }
]

const saleDemoAccounts = ['王女士', '李先生', '陈先生', '周小姐']

async function ensureAuth() {
  let u = getCachedUser()
  if (!u) u = await fetchCurrentUser()
  if (!u) {
    await router.replace('/login')
    return false
  }
  user.value = u
  return true
}

function statusBadge(status: string): string {
  const map: Record<string, string> = {
    SUBMITTED: 'neutral',
    CONFIRMED: 'neutral',
    PROCESSING: 'warning',
    SHIPPED: 'warning',
    RECEIVED: 'success',
    CANCELED: 'danger',
    CANCELLED: 'danger',
    PLACED: 'neutral',
    DELIVERED: 'success'
  }
  return map[status] || 'neutral'
}

function orderTypeLabel(type: string): string {
  const map: Record<string, string> = {
    RAW_MATERIAL: '原料采购',
    MATERIAL: '原料/成品',
    FINISHED_GOODS: '成品进货'
  }
  return map[type] || type
}

function currency(amount: number): string {
  return '¥' + Number(amount).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

async function loadOrders() {
  loading.value = true
  errorMessage.value = ''
  try {
    const [po, so] = await Promise.all([listPurchaseOrders(), listSalesOrders()])
    purchaseOrders.value = po
    salesOrders.value = so
  } catch (err) {
    errorMessage.value = err instanceof Error ? err.message : '订单加载失败'
  } finally {
    loading.value = false
  }
}

function openCreate(type: 'PURCHASE' | 'SALES') {
  showCreate.value = type
  createMessage.value = ''
  createError.value = ''
}

async function handleCreatePurchase() {
  const p = newPurchase.value
  if (!p.sellerOrgName || !p.orderType) {
    createError.value = '请填写供货方与采购类型'
    return
  }
  createSaving.value = true
  createError.value = ''
  try {
    const sellerId = sellerOrgIdByName(p.sellerOrgName)
    await createPurchaseOrder({
      sellerOrgId: sellerId,
      orderType: p.orderType,
      note: p.note || undefined,
      items: [{ productId: p.productId, quantity: p.quantity, unitPrice: p.unitPrice }]
    })
    createMessage.value = '采购单创建成功'
    showCreate.value = null
    await loadOrders()
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '创建失败'
  } finally {
    createSaving.value = false
  }
}

async function handleCreateSales() {
  const s = newSales.value
  if (!s.customerName || !s.deliveryAddress) {
    createError.value = '请填写客户称谓与收货地址'
    return
  }
  createSaving.value = true
  createError.value = ''
  try {
    await createSalesOrder({
      customerName: s.customerName,
      customerPhone: s.customerPhone || undefined,
      deliveryAddress: s.deliveryAddress,
      note: s.note || undefined,
      items: [{ productId: s.productId, quantity: s.quantity, unitPrice: s.unitPrice }]
    })
    createMessage.value = '销售订单创建成功'
    showCreate.value = null
    await loadOrders()
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '创建失败'
  } finally {
    createSaving.value = false
  }
}

async function handleLogout() {
  await logout()
  clearCachedUser()
  await router.replace('/login')
}

// 演示用：把前端展示的供货方名称映射为后端 org_id
function sellerOrgIdByName(name: string): number {
  const map: Record<string, number> = {
    '蓝海远洋捕捞舰队': 1,
    '深海鲜冻加工厂': 2,
    '南海冷链分装批发中心': 3,
    '鲜活优选连锁超市': 4,
    '亿家生鲜线上商家': 5,
    '极速冷链物流': 6
  }
  return map[name] || 2
}

onMounted(async () => {
  const ok = await ensureAuth()
  if (ok) await loadOrders()
})
</script>

<template>
  <div class="orders-container">
    <!-- 登录状态条 -->
    <div v-if="user" class="user-banner consumer-card">
      <div class="user-info">
        <span class="avatar">{{ user.displayName ? user.displayName.charAt(0) : 'U' }}</span>
        <div class="user-text">
          <span class="user-name">{{ user.displayName }}</span>
          <span class="user-org">{{ user.orgName }}（{{ user.orgNo }}）</span>
          <span class="user-roles">{{ (user.roles || []).join(' / ') }}</span>
        </div>
      </div>
      <div class="banner-actions">
        <a class="nav-link" href="/trace" target="_blank">消费者溯源</a>
        <button type="button" class="logout-btn" @click="handleLogout">退出登录</button>
      </div>
    </div>

    <!-- 创建面板 -->
    <div v-if="showCreate" class="create-panel consumer-card">
      <h2 class="consumer-card-title">
        发起{{ showCreate === 'PURCHASE' ? '采购进货单' : '销售订单' }}
      </h2>

      <!-- 采购单表单 -->
      <form v-if="showCreate === 'PURCHASE'" class="create-form" @submit.prevent="handleCreatePurchase">
        <div class="form-row">
          <label>
            <span>供货方企业</span>
            <select v-model="newPurchase.sellerOrgName">
              <option>蓝海远洋捕捞舰队</option>
              <option>深海鲜冻加工厂</option>
              <option>南海冷链分装批发中心</option>
              <option>鲜活优选连锁超市</option>
              <option>亿家生鲜线上商家</option>
            </select>
          </label>
          <label>
            <span>采购类型</span>
            <select v-model="newPurchase.orderType">
              <option value="RAW_MATERIAL">原料采购</option>
              <option value="FINISHED_GOODS">成品进货</option>
            </select>
          </label>
        </div>
        <div class="form-row">
          <label>
            <span>商品</span>
            <select v-model.number="newPurchase.productId">
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </label>
          <label>
            <span>数量 (kg)</span>
            <input v-model.number="newPurchase.quantity" type="number" min="1" step="1" />
          </label>
          <label>
            <span>单价 (元/kg)</span>
            <input v-model.number="newPurchase.unitPrice" type="number" min="0" step="0.01" />
          </label>
        </div>
        <label>
          <span>备注</span>
          <input v-model="newPurchase.note" type="text" placeholder="选填" />
        </label>
        <p v-if="createError" class="error-text">{{ createError }}</p>
        <div class="form-actions">
          <button type="button" class="cancel-btn" @click="showCreate = null">取消</button>
          <button type="submit" class="primary-btn" :disabled="createSaving">
            {{ createSaving ? '提交中…' : '提交采购单' }}
          </button>
        </div>
      </form>

      <!-- 销售单表单 -->
      <form v-else class="create-form" @submit.prevent="handleCreateSales">
        <div class="form-row">
          <label>
            <span>客户称谓</span>
            <input v-model="newSales.customerName" type="text" list="customer-options" placeholder="输入客户名称" />
          </label>
          <label>
            <span>客户电话</span>
            <input v-model="newSales.customerPhone" type="text" placeholder="选填" />
          </label>
        </div>
        <datalist id="customer-options">
          <option v-for="c in saleDemoAccounts" :key="c" :value="c" />
        </datalist>
        <label>
          <span>收货地址</span>
          <input v-model="newSales.deliveryAddress" type="text" placeholder="省市区街道门牌" />
        </label>
        <div class="form-row">
          <label>
            <span>商品</span>
            <select v-model.number="newSales.productId">
              <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
            </select>
          </label>
          <label>
            <span>数量 (kg)</span>
            <input v-model.number="newSales.quantity" type="number" min="1" step="1" />
          </label>
          <label>
            <span>单价 (元/kg)</span>
            <input v-model.number="newSales.unitPrice" type="number" min="0" step="0.01" />
          </label>
        </div>
        <p v-if="createError" class="error-text">{{ createError }}</p>
        <div class="form-actions">
          <button type="button" class="cancel-btn" @click="showCreate = null">取消</button>
          <button type="submit" class="primary-btn" :disabled="createSaving">
            {{ createSaving ? '提交中…' : '提交销售订单' }}
          </button>
        </div>
      </form>
    </div>

    <p v-if="createMessage" class="success-message">{{ createMessage }}</p>

    <!-- 订单列表 -->
    <div v-if="loading" class="consumer-card"><p class="muted">订单加载中…</p></div>
    <p v-else-if="errorMessage" class="error-text">{{ errorMessage }}</p>
    <template v-else>
      <section class="order-section">
        <div class="section-head">
          <h2 class="section-title">采购进货单</h2>
          <button type="button" class="create-btn" @click="openCreate('PURCHASE')">发起采购</button>
        </div>
        <div v-if="purchaseOrders.length === 0" class="consumer-card empty"><p class="muted">暂无采购单</p></div>
        <article v-for="po in purchaseOrders" :key="po.id" class="order-card consumer-card">
          <div class="order-head">
            <span class="order-no mono">{{ po.orderNo }}</span>
            <span class="status-badge" :class="statusBadge(po.status)">{{ po.status }}</span>
          </div>
          <div class="order-meta">
            <span><b>{{ orderTypeLabel(po.orderType) }}</b> · 供方 org#{{ po.sellerOrgId }}</span>
            <span>金额 {{ currency(po.amountTotal) }}</span>
          </div>
          <ul class="item-list">
            <li v-for="it in po.items" :key="it.id">
              {{ it.productName }} × {{ it.quantity }}{{ it.unitCode }} @ {{ it.unitPrice }} 元/kg = {{ currency(it.rowAmount) }}
            </li>
          </ul>
          <p v-if="po.note" class="order-note mono">{{ po.note }}</p>
        </article>
      </section>

      <section class="order-section">
        <div class="section-head">
          <h2 class="section-title">销售订单</h2>
          <button type="button" class="create-btn" @click="openCreate('SALES')">发起销售</button>
        </div>
        <div v-if="salesOrders.length === 0" class="consumer-card empty"><p class="muted">暂无销售单</p></div>
        <article v-for="so in salesOrders" :key="so.id" class="order-card consumer-card">
          <div class="order-head">
            <span class="order-no mono">{{ so.orderNo }}</span>
            <span class="status-badge" :class="statusBadge(so.status)">{{ so.status }}</span>
          </div>
          <div class="order-meta">
            <span>客户：{{ so.customerName }} · {{ so.deliveryAddress }}</span>
            <span>金额 {{ currency(so.amountTotal) }}</span>
          </div>
          <ul class="item-list">
            <li v-for="it in so.items" :key="it.id">
              {{ it.productName }} × {{ it.quantity }}{{ it.unitCode }} @ {{ it.unitPrice }} 元/kg = {{ currency(it.rowAmount) }}
            </li>
          </ul>
          <p v-if="so.note" class="order-note mono">{{ so.note }}</p>
        </article>
      </section>
    </template>
  </div>
</template>

<style scoped>
.orders-container {
  max-width: 980px;
  margin: 0 auto;
}
.user-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
}
.avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background-color: var(--color-ocean);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}
.user-text {
  display: flex;
  flex-direction: column;
}
.user-name {
  font-weight: 700;
  color: var(--color-text-title);
}
.user-org {
  font-size: 13px;
  color: var(--color-text-muted);
}
.user-roles {
  font-size: 12px;
  color: var(--color-ocean);
}
.banner-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-link {
  color: var(--color-ocean);
  font-size: 13px;
  text-decoration: none;
}
.logout-btn {
  padding: 7px 16px;
  border: 1px solid var(--color-danger-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-card);
  color: var(--color-danger-text);
  font-size: 13px;
}
.logout-btn:hover {
  background-color: var(--color-danger-bg);
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 20px 0 10px;
}
.section-title {
  margin: 0;
  font-size: 17px;
  color: var(--color-text-title);
}
.create-btn {
  padding: 7px 16px;
  border: none;
  border-radius: var(--radius-sm);
  background-color: var(--color-ocean);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.create-btn:hover {
  background-color: var(--color-ocean-hover);
}
.order-card {
  padding: 14px 16px;
}
.order-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.order-no {
  font-size: 13px;
  color: var(--color-text-title);
  font-weight: 600;
}
.order-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--color-text-muted);
}
.item-list {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--color-text-body);
}
.order-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
.empty {
  color: var(--color-text-muted);
}
.muted {
  color: var(--color-text-muted);
}
.error-text {
  color: var(--color-danger-text);
  font-size: 13px;
}
.success-message {
  color: var(--color-success-text);
  font-size: 14px;
  font-weight: 600;
}
.create-panel {
  margin-top: 14px;
}
.create-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.form-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
label span {
  font-size: 12px;
  color: var(--color-text-muted);
}
input,
select {
  padding: 8px 10px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--color-text-title);
  background-color: #fff;
}
input:focus,
select:focus {
  outline: 2px solid var(--color-ocean);
  outline-offset: 1px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
.cancel-btn {
  padding: 8px 18px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-card);
  font-size: 14px;
}
.primary-btn {
  padding: 8px 22px;
  border: none;
  border-radius: var(--radius-sm);
  background-color: var(--color-ocean);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}
.primary-btn:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
}
.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>