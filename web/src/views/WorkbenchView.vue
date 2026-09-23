<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  getCachedUser,
  fetchCurrentUser,
  logout,
  clearCachedUser,
  type CurrentUser
} from '@/api/auth'
import {
  listPurchaseOrders,
  listSalesOrders,
  createPurchaseOrder,
  createSalesOrder,
  approvePurchaseOrder,
  rejectPurchaseOrder,
  schedulePurchaseOrder,
  completePurchaseDelivery,
  receivePurchaseOrder,
  requestCancelOrder,
  approveCancelRequest,
  rejectCancelRequest,
  withdrawCancelRequest,
  updateSalesStatus,
  addOrderNote,
  listOrderNotes,
  allocateBatch,
  listAllocations,
  removeAllocation,
  type PurchaseOrder,
  type SalesOrder,
  type OrderItem,
  type OrderNote,
  type BatchAllocation
} from '@/api/orders'
import { listMyInventory, type InventoryBatch } from '@/api/inventory'
import {
  listBatches,
  createBatch,
  submitBatch,
  listTransfers,
  createTransfer,
  submitTransfer,
  acceptTransfer,
  supplementTraceEvent,
  bindBatchToPublicCode,
  directStockIn,
  processMaterials,
  repackBatch,
  type BatchItem,
  type Transfer
} from '@/api/batches'
import { listProducts, type Product } from '@/api/products'
import {
  listInspections,
  createInspection,
  submitInspectionResult,
  listPendingInspections,
  listInspectionsByOrder,
  updateChecklistItem,
  passInspection,
  submitQualityReview,
  parseChecklist,
  type QualityInspection,
  type ChecklistItemState
} from '@/api/quality'
import { fetchAdminOverview, type AdminOverview } from '@/api/admin'

const router = useRouter()

const user = ref<CurrentUser | null>(null)
const loading = ref(true)
const toast = ref('')
let toastTimer: number | undefined

const products = [
  { id: 1, name: '东海带鱼' },
  { id: 2, name: '南美白对虾' },
  { id: 3, name: '挪威冰鲜三文鱼' },
  { id: 4, name: '冷冻扇贝柱' },
  { id: 10, name: '东海野生带鱼(原料)' },
  { id: 11, name: '南海野生对虾(原料)' },
  { id: 12, name: '北海捕捞鳕鱼(原料)' },
  { id: 13, name: '渤海湾扇贝(原料)' },
  { id: 14, name: '东海捕捞鲅鱼(原料)' },
  { id: 20, name: '冷冻带鱼段(成品)' },
  { id: 21, name: '去壳对虾仁(成品)' },
  { id: 22, name: '鳕鱼柳(成品)' },
  { id: 23, name: '干贝柱(成品)' },
  { id: 24, name: '鲅鱼丸(成品)' },
  { id: 25, name: '海鲜大礼包(成品)' }
]
const orgs = [
  { id: 1, name: '蓝海远洋捕捞舰队' },
  { id: 2, name: '深海鲜冻加工厂' },
  { id: 3, name: '南海冷链分装批发中心' },
  { id: 4, name: '鲜活优选连锁超市' },
  { id: 5, name: '亿家生鲜线上商家' },
  { id: 6, name: '极速冷链物流' }
]

function orgName(id: number): string {
  return orgs.find((o) => o.id === id)?.name || `org#${id}`
}

const isAdmin = computed(() => !!user.value?.roles?.includes('ROLE_SYS_ADMIN'))
const isQA = computed(
  () => !!user.value?.roles?.includes('ROLE_ORG_QA') || !!user.value?.roles?.includes('QUALITY_MANAGER')
)
const isOperator = computed(() => !!user.value?.roles?.includes('OPERATOR'))
// 终端(零售/电商)只采购不入库出货,不显示销售单栏目
const isTerminal = computed(() => user.value?.orgType === 'RETAILER')
// 捕捞船长:可自捕自产直接入库
const isFishingCaptain = computed(() => user.value?.orgType === 'SOURCE')
// 加工厂:可对原料批次进行加工转换
const isProcessor = computed(() => user.value?.orgType === 'PROCESSOR')
// 批发分装:可对加工批次进行分拣分装,产出 DISTRIBUTION 批次
const isDistributor = computed(() => user.value?.orgType === 'DISTRIBUTOR')

const tabs = computed(() => {
  const t: { key: string; label: string }[] = []
  if (isOperator.value) {
    t.push({ key: 'purchase', label: '采购单' })
    if (!isTerminal.value) {
      t.push({ key: 'sales', label: '销售单' })
    }
    t.push({ key: 'batch', label: '库存' })
    if (isFishingCaptain.value) {
      t.push({ key: 'stockIn', label: '入库登记' })
    }
  }
  if (isQA.value) t.push({ key: 'quality', label: '质量质检' })
  if (isAdmin.value) t.push({ key: 'admin', label: '系统管理' })
  return t
})

const activeTab = ref('purchase')
const loaded = ref<Record<string, boolean>>({})

function switchTab(key: string) {
  activeTab.value = key
  if (!loaded.value[key]) {
    loaded.value[key] = true
    loaders[key]?.()
  }
}

function showToast(msg: string) {
  toast.value = msg
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => (toast.value = ''), 2600)
}

async function ensureAuth() {
  let u = getCachedUser()
  if (!u) u = await fetchCurrentUser()
  if (!u) {
    await router.replace('/login')
    return false
  }
  user.value = u
  const t = tabs.value
  if (t.length > 0) activeTab.value = t[0].key
  return true
}

// ==================== 采购单 ====================
const purchases = ref<PurchaseOrder[]>([])
const purchaseError = ref('')
const showCreatePurchase = ref(false)
const newPurchase = ref({
  sellerOrgId: 2,
  orderType: 'FINISHED_GOODS',
  productId: 1,
  quantity: 50,
  unitPrice: 28,
  note: '',
  buyerContactName: '',
  buyerContactPhone: '',
  buyerContactAddress: ''
})
const rejectTarget = ref<{ id: number; reason: string } | null>(null)
const acting = ref<Record<string, boolean>>({})

async function loadPurchases() {
  purchaseError.value = ''
  try {
    purchases.value = await listPurchaseOrders()
    await loadPoNotesAndAllocations()
    await loadMyInventory()
    for (const p of purchases.value) {
      await loadOrderQc(p.id)
    }
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '采购单加载失败'
  }
}

async function handleCreatePurchase() {
  try {
    await createPurchaseOrder({
      sellerOrgId: newPurchase.value.sellerOrgId,
      orderType: newPurchase.value.orderType,
      note: newPurchase.value.note || undefined,
      buyerContactName: newPurchase.value.buyerContactName || undefined,
      buyerContactPhone: newPurchase.value.buyerContactPhone || undefined,
      buyerContactAddress: newPurchase.value.buyerContactAddress || undefined,
      items: [
        {
          productId: newPurchase.value.productId,
          quantity: newPurchase.value.quantity,
          unitPrice: newPurchase.value.unitPrice
        }
      ]
    })
    showCreatePurchase.value = false
    showToast('采购单已提交，待供货方审批')
    await loadPurchases()
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '创建失败'
  }
}

function isBuyer(po: PurchaseOrder): boolean {
  return po.buyerOrgId === user.value?.orgId
}

async function doReject() {
  if (!rejectTarget.value || !rejectTarget.value.reason.trim()) {
    purchaseError.value = '请填写驳回原因'
    return
  }
  const id = rejectTarget.value.id
  await approveAct(`reject-${id}`, () => rejectPurchaseOrder(id, rejectTarget.value!.reason))
  rejectTarget.value = null
}

const cancelTarget = ref<{ id: number; reason: string } | null>(null)

async function doCancel() {
  if (!cancelTarget.value || !cancelTarget.value.reason.trim()) {
    if (activeTab.value === 'sales') {
      salesError.value = '请填写取消原因'
    } else {
      purchaseError.value = '请填写取消原因'
    }
    return
  }
  const id = cancelTarget.value.id
  const reason = cancelTarget.value.reason
  cancelTarget.value = null
  if (activeTab.value === 'sales') {
    if (salesActing.value[`cancel-${id}`]) return
    salesActing.value[`cancel-${id}`] = true
    try {
      await requestCancelOrder(id, reason)
      showToast('取消请求已提交')
      await loadSales()
    } catch (e) {
      salesError.value = e instanceof Error ? e.message : '操作失败'
    } finally {
      salesActing.value[`cancel-${id}`] = false
    }
  } else {
    await approveAct(`cancel-${id}`, () => requestCancelOrder(id, reason))
  }
}

async function doWithdrawCancel(poId: number) {
  await approveAct(`cw-${poId}`, () => withdrawCancelRequest(poId))
}

async function doReceive(poId: number) {
  await approveAct(`recv-${poId}`, () => receivePurchaseOrder(poId))
}

async function approveAct(key: string, fn: () => Promise<unknown>) {
  if (acting.value[key]) return
  acting.value[key] = true
  try {
    await fn()
    showToast('操作成功')
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    acting.value[key] = false
    await loadPurchases()
    await loadPoNotesAndAllocations()
  }
}

// ==================== 订单备注 + 批次分配 + 库存 ====================
const poNotes = ref<Record<number, OrderNote[]>>({})
const poAllocations = ref<Record<number, BatchAllocation[]>>({})
const myInventory = ref<InventoryBatch[]>([])
const noteFormOpen = ref<number | null>(null)
const allocFormOpen = ref<number | null>(null)
const newNote = ref({ text: '' })

// 按批次维度选择:每行对应一个具体批次,含编号/产品名/余量
interface AllocLine {
  batchId: number
  batchNo: string
  productId: number
  productName: string
  quantity: number
  unitCode: string
  available: number
}
const allocDraft = ref<AllocLine[]>([])
const allocShortage = ref('')
const allocSaving = ref(false)

// 可选批次:当前库存里有可用余量的每个批次,按 batchId 列出(不按产品合并)
const allocProducts = computed(() => {
  return myInventory.value
    .filter((b) => (Number(b.availableQuantity) || 0) > 0)
    .map((b) => ({
      batchId: b.batchId,
      batchNo: b.batchNo,
      productId: b.productId,
      productName: b.productName || '#' + b.productId,
      available: Number(b.availableQuantity) || 0,
      unitCode: b.unitCode || 'kg'
    }))
})

function newAllocLine(): AllocLine {
  const first = allocProducts.value[0]
  return {
    batchId: first ? first.batchId : 0,
    batchNo: first ? first.batchNo : '',
    productId: first ? first.productId : 0,
    productName: first ? first.productName : '',
    quantity: 0,
    unitCode: first ? first.unitCode : 'kg',
    available: first ? first.available : 0
  }
}

function onAllocBatchChange(line: AllocLine) {
  const b = allocProducts.value.find((x) => x.batchId === line.batchId)
  if (b) {
    line.batchNo = b.batchNo
    line.productId = b.productId
    line.productName = b.productName
    line.unitCode = b.unitCode
    line.available = b.available
  }
}

function addAllocLine() {
  allocDraft.value.push(newAllocLine())
}

function removeAllocLine(idx: number) {
  allocDraft.value.splice(idx, 1)
}

function initAllocDraft() {
  // 默认给一行空行,用户可点+继续加
  allocDraft.value = allocProducts.value.length ? [newAllocLine()] : []
  allocShortage.value = ''
}

// 校验某产品的总可用量是否满足需要(跨多批次聚合)
function productAvailable(productId: number): number {
  return myInventory.value
    .filter((b) => b.productId === productId)
    .reduce((s, b) => s + (Number(b.availableQuantity) || 0), 0)
}

function openAllocForm(poId: number) {
  allocFormOpen.value = allocFormOpen.value === poId ? null : poId
  if (allocFormOpen.value !== null) initAllocDraft()
}

function formatTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function loadPoNotesAndAllocations(poId?: number) {
  if (poId) {
    try {
      const [notes, allocs] = await Promise.all([
        listOrderNotes('PURCHASE', poId),
        listAllocations(poId)
      ])
      poNotes.value[poId] = notes
      poAllocations.value[poId] = allocs
    } catch {
      // 忽略单条失败
    }
    return
  }
  const ids = purchases.value.map((p) => p.id)
  for (const id of ids) {
    try {
      const [notes, allocs] = await Promise.all([
        listOrderNotes('PURCHASE', id),
        listAllocations(id)
      ])
      poNotes.value[id] = notes
      poAllocations.value[id] = allocs
    } catch {
      // 忽略单条失败
    }
  }
}

async function loadMyInventory() {
  try {
    myInventory.value = await listMyInventory()
  } catch {
    myInventory.value = []
  }
}

// ==================== 库存筛选 ====================
const invFilter = ref({
  availability: 'ALL' as 'ALL' | 'HAS' | 'NONE',
  keyword: '',
  sort: 'NEW' as 'NEW' | 'OLD'
})

const filteredInventory = computed(() => {
  let list = myInventory.value.slice()
  const kw = invFilter.value.keyword.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (b) =>
        (b.productName || '').toLowerCase().includes(kw) ||
        b.batchNo.toLowerCase().includes(kw)
    )
  }
  if (invFilter.value.availability === 'HAS') {
    list = list.filter((b) => Number(b.availableQuantity) > 0)
  } else if (invFilter.value.availability === 'NONE') {
    list = list.filter((b) => Number(b.availableQuantity) <= 0)
  }
  list.sort((a, b) => {
    // 入库时间:优先 production_date(真实生产/入库日期,有区分度)
    // 旧批次 created_at 同秒无区分度,故以 production_date 为主、created_at 兜底
    const tOf = (x: InventoryBatch) => {
      const pd = x.productionDate ? new Date(x.productionDate).getTime() : 0
      const ca = x.createdAt ? new Date(x.createdAt).getTime() : 0
      return pd || ca || 0
    }
    const ta = tOf(a)
    const tb = tOf(b)
    return invFilter.value.sort === 'NEW' ? tb - ta : ta - tb
  })
  return list
})

// ==================== 捕捞船长直接入库 ====================
const allProducts = ref<Product[]>([])
const stockInForm = ref({
  productId: 0,
  quantity: 0,
  batchNo: '',
  originText: '',
  captureDate: ''
})
const stockInError = ref('')
const stockInSuccess = ref('')

async function loadAllProducts() {
  try {
    allProducts.value = await listProducts()
  } catch {
    allProducts.value = []
  }
}

// 原料商品(捕捞船长入库时选择)
const rawMaterialProducts = computed(() => allProducts.value.filter((p) => p.productType === 'RAW_MATERIAL'))
// 加工成品(加工厂产出时选择)
const finishedGoodsProducts = computed(() => allProducts.value.filter((p) => p.productType === 'FINISHED_GOODS'))

async function doDirectStockIn() {
  stockInError.value = ''
  stockInSuccess.value = ''
  if (!stockInForm.value.productId) {
    stockInError.value = '请选择原料商品'
    return
  }
  if (!stockInForm.value.quantity || stockInForm.value.quantity <= 0) {
    stockInError.value = '入库数量必须大于 0'
    return
  }
  try {
    await directStockIn({
      productId: stockInForm.value.productId,
      quantity: stockInForm.value.quantity,
      batchNo: stockInForm.value.batchNo || undefined,
      originText: stockInForm.value.originText || undefined,
      captureDate: stockInForm.value.captureDate || undefined
    })
    stockInSuccess.value = '入库成功'
    stockInForm.value = { productId: 0, quantity: 0, batchNo: '', originText: '', captureDate: '' }
    await loadMyInventory()
  } catch (e) {
    stockInError.value = e instanceof Error ? e.message : '入库失败'
  }
}

// ==================== 加工厂加工 ====================
const processFormOpen = ref<number | null>(null)
const processForm = ref({
  sourceBatchId: 0,
  consumedQuantity: 0,
  outputProductId: 0,
  outputQuantity: 0
})
const processError = ref('')

function openProcessForm(batchId: number) {
  processFormOpen.value = batchId
  processForm.value = {
    sourceBatchId: batchId,
    consumedQuantity: 0,
    outputProductId: 0,
    outputQuantity: 0
  }
  processError.value = ''
}

async function doProcess() {
  processError.value = ''
  if (!processForm.value.consumedQuantity || processForm.value.consumedQuantity <= 0) {
    processError.value = '消耗数量必须大于 0'
    return
  }
  if (!processForm.value.outputProductId) {
    processError.value = '请选择产出成品'
    return
  }
  if (!processForm.value.outputQuantity || processForm.value.outputQuantity <= 0) {
    processError.value = '产出数量必须大于 0'
    return
  }
  try {
    await processMaterials({
      sourceBatchId: processForm.value.sourceBatchId,
      consumedQuantity: processForm.value.consumedQuantity,
      outputProductId: processForm.value.outputProductId,
      outputQuantity: processForm.value.outputQuantity
    })
    showToast('加工完成，成品已入库')
    processFormOpen.value = null
    await loadMyInventory()
  } catch (e) {
    processError.value = e instanceof Error ? e.message : '加工失败'
  }
}

// ==================== 批发分拣分装 ====================
const repackFormOpen = ref<number | null>(null)
const repackForm = ref({ consumedQuantity: 0, outputQuantity: 0 })
const repackError = ref('')

function openRepackForm(batchId: number) {
  repackFormOpen.value = batchId
  repackForm.value = { consumedQuantity: 0, outputQuantity: 0 }
  repackError.value = ''
}

async function doRepack() {
  repackError.value = ''
  if (!repackForm.value.consumedQuantity || repackForm.value.consumedQuantity <= 0) {
    repackError.value = '消耗数量必须大于 0'
    return
  }
  if (!repackForm.value.outputQuantity || repackForm.value.outputQuantity <= 0) {
    repackError.value = '产出数量必须大于 0'
    return
  }
  try {
    await repackBatch({
      sourceBatchId: repackFormOpen.value!,
      consumedQuantity: repackForm.value.consumedQuantity,
      outputQuantity: repackForm.value.outputQuantity
    })
    showToast('分拣分装完成，分销批次已入库')
    repackFormOpen.value = null
    await loadMyInventory()
  } catch (e) {
    repackError.value = e instanceof Error ? e.message : '分拣分装失败'
  }
}

async function doAddNote(poId: number) {
  if (!newNote.value.text.trim()) {
    purchaseError.value = '请填写备注内容'
    return
  }
  try {
    await addOrderNote('PURCHASE', poId, {
      noteText: newNote.value.text,
      statusAt: 'PROCESSING'
    })
    newNote.value.text = ''
    showToast('备注已添加')
    await loadPoNotesAndAllocations()
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '添加失败'
  }
}

// 查找订单明细(采购单/销售单通用),用于智能分配匹配
function findOrderItems(orderId: number): OrderItem[] {
  const po = purchases.value.find((p) => p.id === orderId)
  if (po) return po.items
  const so = sales.value.find((s) => s.id === orderId)
  if (so) return so.items
  return []
}

// 一键智能分配:先判断每个订单明细的产品库存是否充足
// 不足 -> 提示"库存不足无法分配"且不展开批次选择
// 足够 -> 展开表单并按批次贪心填入(每行一个批次,数量=从该批次取走量)
function smartAllocate(orderId: number) {
  const items = findOrderItems(orderId)
  const shortages: string[] = []
  for (const it of items) {
    const need = Number(it.quantity) || 0
    const have = productAvailable(it.productId)
    if (have < need) {
      shortages.push(`${it.productName} 需 ${need}${it.unitCode}，库存仅 ${have}${it.unitCode}`)
    }
  }
  if (shortages.length) {
    // 库存不足:不展开,仅提示
    allocFormOpen.value = null
    salesAllocOpen.value = null
    allocDraft.value = []
    allocShortage.value = '库存不足无法分配：' + shortages.join('；')
    return
  }
  // 库存充足:展开并按批次贪心填入(每个被选中的批次一行)
  initAllocDraft()
  const lines: AllocLine[] = []
  for (const it of items) {
    let remaining = Number(it.quantity) || 0
    // 按入库时间早的优先(FIFO),与提交逻辑一致
    const batches = allocProducts.value
      .filter((b) => b.productId === it.productId)
      .slice()
      .sort((a, b) => a.batchNo.localeCompare(b.batchNo))
    for (const b of batches) {
      if (remaining <= 0) break
      const take = Math.min(remaining, b.available)
      if (take <= 0) continue
      lines.push({
        batchId: b.batchId,
        batchNo: b.batchNo,
        productId: b.productId,
        productName: b.productName,
        quantity: take,
        unitCode: b.unitCode,
        available: b.available
      })
      remaining -= take
    }
  }
  allocDraft.value = lines
  allocShortage.value = ''
}

// 提交时直接按每行所选 batchId 分配(下拉已按批次选择,无需再拆分)
async function allocateByProductLines(lines: AllocLine[], errorRef: { value: string }) {
  const tasks: { batchId: number; allocatedQuantity: number }[] = []
  for (const line of lines) {
    if (!line.batchId || !line.quantity || line.quantity <= 0) continue
    if (line.quantity > line.available) {
      errorRef.value = `${line.batchNo} 余量不足：需 ${line.quantity}${line.unitCode}，仅剩 ${line.available}${line.unitCode}`
      return null
    }
    tasks.push({ batchId: line.batchId, allocatedQuantity: line.quantity })
  }
  if (!tasks.length) {
    errorRef.value = '请填写分配数量'
    return null
  }
  return tasks
}

async function doAllocate(poId: number) {
  purchaseError.value = ''
  const tasks = await allocateByProductLines(allocDraft.value, purchaseError)
  if (!tasks) return
  allocSaving.value = true
  try {
    for (const t of tasks) {
      await allocateBatch(poId, t)
    }
    showToast(`已分配 ${tasks.length} 个批次`)
    allocFormOpen.value = null
    await loadMyInventory()
    await loadPoNotesAndAllocations()
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '分配失败'
  } finally {
    allocSaving.value = false
  }
}

// ==================== 销售单 ====================
const sales = ref<SalesOrder[]>([])
const salesError = ref('')

// ==================== 销售单tab:下游采购单的备注/分配/质检操作 ====================
const salesNoteOpen = ref<number | null>(null)
const salesAllocOpen = ref<number | null>(null)
const salesAllocs = ref<Record<number, BatchAllocation[]>>({})
const newNoteText = ref('')

async function loadPoAllocForSales(poId: number) {
  try {
    const allocs = await listAllocations(poId)
    salesAllocs.value[poId] = allocs
    // 同时加载备注
    await loadPoNotesAndAllocations(poId)
  } catch {
    salesAllocs.value[poId] = []
  }
}

async function loadSalesBatchQc(poId: number) {
  // 保留函数签名但不再使用,质检流程已改为环节质检审核
  void poId
}

async function doAddNoteForSales(poId: number) {
  if (!newNoteText.value.trim()) {
    salesError.value = '请填写备注内容'
    return
  }
  try {
    await addOrderNote('PURCHASE', poId, {
      noteText: newNoteText.value.trim(),
      statusAt: 'PROCESSING'
    })
    newNoteText.value = ''
    showToast('备注已添加')
    await loadPoNotesAndAllocations(poId)
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '添加备注失败'
  }
}

function openSalesAllocForm(poId: number) {
  salesAllocOpen.value = salesAllocOpen.value === poId ? null : poId
  if (salesAllocOpen.value !== null) {
    initAllocDraft()
    loadPoAllocForSales(poId)
  }
}

async function doAllocateForSales(poId: number) {
  salesError.value = ''
  const tasks = await allocateByProductLines(allocDraft.value, salesError)
  if (!tasks) return
  allocSaving.value = true
  try {
    for (const t of tasks) {
      await allocateBatch(poId, t)
    }
    showToast(`已分配 ${tasks.length} 个批次`)
    salesAllocOpen.value = null
    await loadMyInventory()
    await loadPoAllocForSales(poId)
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '分配失败'
  } finally {
    allocSaving.value = false
  }
}

async function quickPassQcForSales(poId: number, batchId: number) {
  // 保留函数签名但不再使用,质检流程已改为环节质检审核
  void poId
  void batchId
}

const showCreateSales = ref(false)
const newSales = ref({
  customerName: '',
  customerPhone: '',
  deliveryAddress: '',
  productId: 1,
  quantity: 5,
  unitPrice: 45,
  note: ''
})
const saleDemoAccounts = ['王女士', '李先生', '陈先生', '周小姐']

async function loadSales() {
  salesError.value = ''
  try {
    sales.value = await listSalesOrders()
    // 为下游采购单加载备注和分配记录
    const downstreamIds = sales.value
      .filter((s) => s.orderNo.startsWith('PO'))
      .map((s) => s.id)
    for (const id of downstreamIds) {
      try {
        const [notes, allocs] = await Promise.all([
          listOrderNotes('PURCHASE', id),
          listAllocations(id)
        ])
        poNotes.value[id] = notes
        poAllocations.value[id] = allocs
        salesAllocs.value[id] = allocs
      } catch {
        // 忽略单条失败
      }
      await loadOrderQc(id)
    }
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '销售单加载失败'
  }
}

async function handleCreateSales() {
  try {
    const created = await createSalesOrder({
      customerName: newSales.value.customerName,
      customerPhone: newSales.value.customerPhone || undefined,
      deliveryAddress: newSales.value.deliveryAddress,
      note: newSales.value.note || undefined,
      items: [
        {
          productId: newSales.value.productId,
          quantity: newSales.value.quantity,
          unitPrice: newSales.value.unitPrice
        }
      ]
    })
    showCreateSales.value = false
    showToast('销售单已创建，收货入库后将生成溯源码')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '创建失败'
  }
}

async function advanceSales(id: number, status: string) {
  try {
    const updated = await updateSalesStatus(id, status)
    showToast(
      updated.packedPackageNo
        ? `已收货入库，溯源码：${updated.packedPackageNo}`
        : '销售单状态已推进'
    )
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '操作失败'
  }
}

// 下游采购单(本组织作为卖方)在销售单tab里的操作:复用采购单API
function isDownstreamOrder(so: SalesOrder): boolean {
  return so.orderNo.startsWith('PO')
}

const salesActing = ref<Record<string, boolean>>({})

async function salesApprove(poId: number) {
  if (salesActing.value[`approve-${poId}`]) return
  salesActing.value[`approve-${poId}`] = true
  try {
    await approvePurchaseOrder(poId)
    showToast('已审批通过')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '审批失败'
  } finally {
    salesActing.value[`approve-${poId}`] = false
  }
}

async function salesSchedule(poId: number) {
  if (salesActing.value[`schedule-${poId}`]) return
  salesActing.value[`schedule-${poId}`] = true
  try {
    await schedulePurchaseOrder(poId)
    showToast('已排产，进入处理中')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '排产失败'
  } finally {
    salesActing.value[`schedule-${poId}`] = false
  }
}

async function salesDeliver(poId: number) {
  if (salesActing.value[`deliver-${poId}`]) return
  salesActing.value[`deliver-${poId}`] = true
  try {
    await completePurchaseDelivery(poId)
    showToast('已确认出货')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '出货失败'
  } finally {
    salesActing.value[`deliver-${poId}`] = false
  }
}

async function salesApproveCancel(poId: number) {
  if (salesActing.value[`capp-${poId}`]) return
  salesActing.value[`capp-${poId}`] = true
  try {
    await approveCancelRequest(poId)
    showToast('已同意取消')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '同意取消失败'
  } finally {
    salesActing.value[`capp-${poId}`] = false
  }
}

async function salesRejectCancel(poId: number) {
  if (salesActing.value[`crej-${poId}`]) return
  salesActing.value[`crej-${poId}`] = true
  try {
    await rejectCancelRequest(poId)
    showToast('已拒绝取消')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '拒绝取消失败'
  } finally {
    salesActing.value[`crej-${poId}`] = false
  }
}

async function salesWithdrawCancel(poId: number) {
  if (salesActing.value[`cw-${poId}`]) return
  salesActing.value[`cw-${poId}`] = true
  try {
    await withdrawCancelRequest(poId)
    showToast('已撤回取消请求')
    await loadSales()
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '撤回失败'
  } finally {
    salesActing.value[`cw-${poId}`] = false
  }
}

// ==================== 批次 / 交接 ====================
const batches = ref<BatchItem[]>([])
const transfers = ref<Transfer[]>([])
const batchError = ref('')
const showCreateBatch = ref(false)
const newBatch = ref({
  productId: 1,
  quantity: 100,
  unitCode: 'kg',
  originType: 'DOMESTIC_CAPTURE',
  originText: '东海捕捞母港',
  captureDate: ''
})
const showCreateTransfer = ref(false)
const newTransfer = ref({ batchId: 0, receiverOrgId: 0 })
const acceptTarget = ref<{
  id: number
  unitCode: string
  receivedQuantity: number
  expectedVersion: number
  differenceReason: string
} | null>(null)

async function loadBatches() {
  batchError.value = ''
  try {
    const [bs, tfs] = await Promise.all([listBatches(), listTransfers()])
    batches.value = bs
    transfers.value = tfs
    await loadMyInventory()
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '批次加载失败'
  }
}

async function handleCreateBatch() {
  try {
    const capture = newBatch.value.captureDate ? newBatch.value.captureDate : undefined
    await createBatch({
      batchNo: `B${Date.now()}${Math.floor(Math.random() * 9000 + 1000)}`,
      productId: newBatch.value.productId,
      batchType: 'PRODUCTION',
      quantity: newBatch.value.quantity,
      unitCode: newBatch.value.unitCode,
      originType: newBatch.value.originType,
      originText: newBatch.value.originText,
      captureDate: capture
    })
    showCreateBatch.value = false
    showToast('批次草稿已创建')
    await loadBatches()
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '创建失败'
  }
}

async function doSubmitBatch(b: BatchItem) {
  try {
    await submitBatch(b.id, b.version)
    showToast('批次已提交激活')
    await loadBatches()
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '操作失败'
  }
}

async function handleCreateTransfer() {
  try {
    await createTransfer(newTransfer.value.batchId, newTransfer.value.receiverOrgId)
    showCreateTransfer.value = false
    showToast('交接草稿已创建')
    await loadBatches()
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '创建失败'
  }
}

async function doSubmitTransfer(t: Transfer) {
  try {
    await submitTransfer(t.id, t.version)
    showToast('交接已提交发货')
    await loadBatches()
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '操作失败'
  }
}

// 中间环节补充溯源事件：捕捞/加工/分装/质检等环节随时补记一笔履历
const eventFormOpen = ref<number | null>(null)
const newEvent = ref({ eventType: 'PROCESS', summary: '' })
const EVENT_TYPES: { value: string; label: string }[] = [
  { value: 'SOURCE', label: '原料采收/捕捞' },
  { value: 'PROCESS', label: '加工生产' },
  { value: 'PACK', label: '分装与包装' },
  { value: 'WAREHOUSE_IN', label: '冷库入库' },
  { value: 'WAREHOUSE_OUT', label: '冷库出库' },
  { value: 'ARRIVAL', label: '到货验收' }
]
async function doSupplement(b: BatchItem) {
  const summary = newEvent.value.summary.trim()
  if (!summary) {
    batchError.value = '请填写事件补充说明'
    return
  }
  try {
    await supplementTraceEvent(b.id, {
      eventType: newEvent.value.eventType,
      occurredAt: new Date().toISOString(),
      siteId: null,
      dataSource: 'MANUAL',
      summary
    })
    newEvent.value.summary = ''
    eventFormOpen.value = null
    showToast('已补充一笔溯源事件')
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '补充失败'
  }
}

// 将批次聚合绑定到某个公开溯源码（一码多批）
const bindFormOpen = ref<number | null>(null)
const bindCode = ref('')
async function doBind(b: BatchItem) {
  const publicId = bindCode.value.trim()
  if (!publicId) {
    batchError.value = '请输入要聚合的 26 位溯源码'
    return
  }
  try {
    const code = await bindBatchToPublicCode(publicId, b.id, 'DELIVERY')
    bindCode.value = ''
    bindFormOpen.value = null
    showToast(`批次已聚合到溯源码：${code.publicId}`)
  } catch (e) {
    batchError.value = e instanceof Error ? e.message : '绑定失败'
  }
}

async function doAccept() {
  if (!acceptTarget.value) return
  const a = acceptTarget.value
  await approveAct(`accept-${a.id}`, () =>
    acceptTransfer(a.id, {
      receivedQuantity: a.receivedQuantity,
      unitCode: a.unitCode,
      occurredAt: new Date().toISOString(),
      differenceReason: a.differenceReason || undefined,
      expectedVersion: a.expectedVersion
    })
  )
  acceptTarget.value = null
}

// ==================== 质检(环节质检审核) ====================
const qcError = ref('')
const pendingInspections = ref<QualityInspection[]>([])
const selectedQcInspection = ref<QualityInspection | null>(null)
const checklistItems = ref<ChecklistItemState[]>([])
const stageLabels: Record<string, string> = {
  CAPTURE_OUT: '捕捞出货质检',
  PROCESS_OUT: '加工出货质检',
  DIST_OUT: '批发分装出货质检'
}

// 订单级质检状态(供采购单/销售单tab显示)
const orderQcMap = ref<Record<number, QualityInspection[]>>({})

async function loadOrderQc(orderId: number) {
  try {
    orderQcMap.value[orderId] = await listInspectionsByOrder(orderId)
  } catch {
    orderQcMap.value[orderId] = []
  }
}

function qcStatusOf(orderId: number): string {
  const list = orderQcMap.value[orderId]
  if (!list || list.length === 0) return 'NONE'
  if (list.every((q) => q.result === 'PASS')) return 'PASS'
  if (list.some((q) => q.result === 'INSPECTING')) return 'INSPECTING'
  return 'FAIL'
}

function qcSubmitted(orderId: number): boolean {
  const list = orderQcMap.value[orderId]
  return !!list && list.length > 0
}

function qcAllPassed(orderId: number): boolean {
  const list = orderQcMap.value[orderId]
  return !!list && list.length > 0 && list.every((q) => q.result === 'PASS')
}

async function loadQuality() {
  qcError.value = ''
  try {
    pendingInspections.value = await listPendingInspections()
    if (pendingInspections.value.length > 0) {
      await selectQcInspection(pendingInspections.value[0])
    } else {
      selectedQcInspection.value = null
      checklistItems.value = []
    }
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '质检数据加载失败'
  }
}

async function selectQcInspection(inp: QualityInspection) {
  selectedQcInspection.value = inp
  checklistItems.value = parseChecklist(inp.checklistJson)
}

async function toggleChecklistItem(itemName: string, passed: boolean) {
  if (!selectedQcInspection.value) return
  try {
    const updated = await updateChecklistItem(selectedQcInspection.value.id, {
      itemName,
      passed
    })
    selectedQcInspection.value = updated
    checklistItems.value = parseChecklist(updated.checklistJson)
    if (passed) showToast(`"${itemName}" 已通过`)
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '更新失败'
  }
}

async function doPassInspection() {
  if (!selectedQcInspection.value) return
  try {
    await passInspection(selectedQcInspection.value.id)
    showToast('质检已通过，溯源已更新')
    await loadQuality()
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '质检通过失败'
  }
}

function isAllChecklistPassed(): boolean {
  return checklistItems.value.length > 0 && checklistItems.value.every((i) => i.passed)
}

async function submitQualityReviewForOrder(poId: number) {
  try {
    await submitQualityReview(poId)
    showToast('已提交质检审核，等待质检员确认')
    await loadOrderQc(poId)
    if (activeTab.value === 'sales') {
      await loadSales()
    } else {
      await loadPurchases()
    }
  } catch (e) {
    if (activeTab.value === 'sales') {
      salesError.value = e instanceof Error ? e.message : '提交质检审核失败'
    } else {
      purchaseError.value = e instanceof Error ? e.message : '提交质检审核失败'
    }
  }
}


// ==================== 系统管理 ====================
const adminData = ref<AdminOverview | null>(null)
const adminError = ref('')

async function loadAdmin() {
  adminError.value = ''
  try {
    adminData.value = await fetchAdminOverview()
  } catch (e) {
    adminError.value = e instanceof Error ? e.message : '管理数据加载失败'
  }
}

const loaders: Record<string, () => Promise<void>> = {
  purchase: loadPurchases,
  sales: loadSales,
  batch: async () => {
    await Promise.all([loadAllProducts(), loadMyInventory()])
  },
  stockIn: async () => {
    await Promise.all([loadAllProducts(), loadMyInventory()])
  },
  quality: loadQuality,
  admin: loadAdmin
}

async function handleLogout() {
  await logout()
  clearCachedUser()
  await router.replace('/login')
}

function statusBadge(status: string): string {
  const map: Record<string, string> = {
    SUBMITTED: 'neutral',
    CONFIRMED: 'info',
    PROCESSING: 'warning',
    SHIPPED: 'warning',
    RECEIVED: 'success',
    REJECTED: 'danger',
    CANCELLED: 'danger',
    PLACED: 'neutral',
    DELIVERED: 'success',
    DRAFT: 'neutral',
    ACTIVE: 'success',
    PENDING: 'warning',
    ACCEPTED: 'success',
    INSPECTING: 'warning',
    PASS: 'success',
    FAIL: 'danger'
  }
  return map[status] || 'neutral'
}

function orderTypeLabel(t: string): string {
  const map: Record<string, string> = { RAW_MATERIAL: '原料采购', MATERIAL: '原料/成品', FINISHED_GOODS: '成品进货' }
  return map[t] || t
}

function currency(n: number): string {
  return '¥' + Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

onMounted(async () => {
  loading.value = false
  const ok = await ensureAuth()
  if (ok) switchTab(activeTab.value)
})
</script>

<template>
  <div class="wb">
    <header class="wb-header">
      <div class="wb-header-inner">
        <div class="wb-brand">
          <span class="wb-brand-dot"></span>
          <span class="wb-brand-text">冷冻海产品溯源 · 供应链协同工作台</span>
        </div>
        <div v-if="user" class="wb-user">
          <span class="wb-avatar">{{ user.displayName ? user.displayName.charAt(0) : 'U' }}</span>
          <span class="wb-user-name">{{ user.displayName }}</span>
          <span class="wb-user-org">{{ user.orgName }}</span>
          <span class="wb-user-roles mono">{{ (user.roles || []).join(', ') }}</span>
          <button class="wb-logout" type="button" @click="handleLogout">退出</button>
        </div>
      </div>
      <nav v-if="tabs.length" class="wb-tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="wb-tab"
          :class="{ active: activeTab === t.key }"
          @click="switchTab(t.key)"
        >
          {{ t.label }}
        </button>
      </nav>
    </header>

    <main class="wb-main">
      <div v-if="loading" class="card muted">加载中…</div>
      <p v-else-if="!user" class="card muted">请先登录</p>

      <!-- 采购单 -->
      <section v-else-if="activeTab === 'purchase'" class="card">
        <div class="sec-head">
          <h2>采购进货单</h2>
          <button class="pri-btn" type="button" @click="showCreatePurchase = !showCreatePurchase">
            {{ showCreatePurchase ? '收起' : '发起采购' }}
          </button>
        </div>

        <form v-if="showCreatePurchase" class="form" @submit.prevent="handleCreatePurchase">
          <div class="row">
            <label>供货方
              <select v-model.number="newPurchase.sellerOrgId">
                <option v-for="o in orgs" :key="o.id" :value="o.id">{{ o.name }}</option>
              </select>
            </label>
            <label>采购类型
              <select v-model="newPurchase.orderType">
                <option value="RAW_MATERIAL">原料采购</option>
                <option value="FINISHED_GOODS">成品进货</option>
              </select>
            </label>
            <label>商品
              <select v-model.number="newPurchase.productId">
                <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </label>
            <label>数量(kg)<input v-model.number="newPurchase.quantity" type="number" min="1" /></label>
            <label>单价(元)<input v-model.number="newPurchase.unitPrice" type="number" min="0" step="0.01" /></label>
            <label>备注<input v-model="newPurchase.note" type="text" /></label>
          </div>
          <div class="row">
            <label>联系人姓名<input v-model="newPurchase.buyerContactName" type="text" placeholder="如：张采购" /></label>
            <label>联系电话<input v-model="newPurchase.buyerContactPhone" type="text" placeholder="如：138xxxx1234" /></label>
            <label class="grow">收货地址<input v-model="newPurchase.buyerContactAddress" type="text" placeholder="如：上海市浦东新区xx路xx号" /></label>
          </div>
          <div class="acts"><button class="pri-btn" type="submit">提交采购单</button></div>
        </form>

        <p v-if="purchaseError" class="err">{{ purchaseError }}</p>

        <article v-for="po in purchases" :key="po.id" class="item-card">
          <div class="item-head">
            <span class="mono">{{ po.orderNo }}</span>
            <span class="badge" :class="statusBadge(po.status)">{{ po.status }}</span>
          </div>
          <div class="item-meta">
            <span>{{ orderTypeLabel(po.orderType) }} · 供方 {{ orgName(po.sellerOrgId) }}</span>
            <span>金额 {{ currency(po.amountTotal) }}</span>
          </div>
          <div v-if="po.buyerContactName || po.buyerContactPhone || po.buyerContactAddress" class="contact-info">
            <span v-if="po.buyerContactName">联系人：{{ po.buyerContactName }}</span>
            <span v-if="po.buyerContactPhone">电话：{{ po.buyerContactPhone }}</span>
            <span v-if="po.buyerContactAddress">地址：{{ po.buyerContactAddress }}</span>
          </div>
          <ul class="items">
            <li v-for="it in po.items" :key="it.id">
              {{ it.productName }} × {{ it.quantity }}{{ it.unitCode }} = {{ currency(it.rowAmount) }}
            </li>
          </ul>
          <p v-if="po.rejectReason" class="note">驳回原因：{{ po.rejectReason }}</p>

          <!-- 订单进度备注时间线（买卖双方实时可见） -->
          <div v-if="poNotes[po.id]?.length" class="note-timeline">
            <h4>进度备注</h4>
            <ul>
              <li v-for="n in poNotes[po.id]" :key="n.id">
                <span class="mono">{{ formatTime(n.createdAt) }}</span>
                <span class="note-text">{{ n.noteText }}</span>
              </li>
            </ul>
          </div>

          <!-- 已分配批次（树状图分支） -->
          <div v-if="poAllocations[po.id]?.length" class="alloc-list">
            <h4>已分配批次</h4>
            <ul>
              <li v-for="a in poAllocations[po.id]" :key="a.id">
                {{ a.batchNo }} · {{ a.allocatedQuantity }}{{ a.unitCode }}
                <span v-if="a.productName">（{{ a.productName }}）</span>
              </li>
            </ul>
          </div>

          <div v-if="po.publicTraceId" class="trace-code-box">
            溯源码: <span class="mono">{{ po.publicTraceId }}</span>
          </div>

          <div v-if="po.cancelRequestStatus === 'PENDING'" class="cancel-notice">
            {{ po.cancelRequestRole === 'BUYER' ? '买方' : '卖方' }}发起取消: {{ po.cancelRequestReason }}
            <span v-if="isBuyer(po) ? po.cancelRequestRole !== 'BUYER' : po.cancelRequestRole !== 'SELLER'">
              <button class="warn-btn sm" type="button" @click="approveAct(`capp-${po.id}`, () => approveCancelRequest(po.id))">同意取消</button>
              <button class="ghost-btn sm" type="button" @click="approveAct(`crej-${po.id}`, () => rejectCancelRequest(po.id))">拒绝取消</button>
            </span>
            <span v-else>
              <button class="ghost-btn sm" type="button" :disabled="acting[`cw-${po.id}`]" @click="doWithdrawCancel(po.id)">撤回取消</button>
              <span class="muted">等待对方审核</span>
            </span>
          </div>

          <!-- 质检状态 -->
          <div v-if="qcSubmitted(po.id)" class="qc-status">
            <span class="badge" :class="statusBadge(qcStatusOf(po.id))">
              {{ qcStatusOf(po.id) === 'PASS' ? '质检已通过' : qcStatusOf(po.id) === 'INSPECTING' ? '质检中' : '质检未通过' }}
            </span>
            <ul>
              <li v-for="qc in orderQcMap[po.id]" :key="qc.id">
                {{ qc.inspectionNo }}
                <span v-if="qc.inspectionStage"> · {{ stageLabels[qc.inspectionStage] }}</span>
                · {{ qc.result === 'PASS' ? '已通过' : qc.result === 'INSPECTING' ? '待质检' : '未通过' }}
              </li>
            </ul>
          </div>

          <div v-if="!isBuyer(po)" class="item-acts">
            <button
              v-if="po.status === 'SUBMITTED'"
              class="pri-btn"
              type="button"
              :disabled="acting[`approve-${po.id}`]"
              @click="approveAct(`approve-${po.id}`, () => approvePurchaseOrder(po.id))"
            >审批通过</button>
            <button
              v-if="po.status === 'SUBMITTED'"
              class="warn-btn"
              type="button"
              @click="rejectTarget = { id: po.id, reason: '' }"
            >驳回</button>
            <button
              v-if="po.status === 'CONFIRMED'"
              class="pri-btn"
              type="button"
              :disabled="acting[`schedule-${po.id}`]"
              @click="approveAct(`schedule-${po.id}`, () => schedulePurchaseOrder(po.id))"
            >按单排产</button>
            <button
              v-if="po.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="noteFormOpen = noteFormOpen === po.id ? null : po.id"
            >{{ noteFormOpen === po.id ? '收起' : '备注流程' }}</button>
            <button
              v-if="po.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="openAllocForm(po.id)"
            >{{ allocFormOpen === po.id ? '收起' : '分配批次' }}</button>
            <button
              v-if="po.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="allocFormOpen = po.id; smartAllocate(po.id)"
            >智能分配</button>
            <button
              v-if="po.status === 'PROCESSING' && poAllocations[po.id]?.length && !qcSubmitted(po.id)"
              class="ghost-btn"
              type="button"
              @click="submitQualityReviewForOrder(po.id)"
            >提交质检审核</button>
            <button
              v-if="po.status === 'PROCESSING' && qcAllPassed(po.id)"
              class="pri-btn"
              type="button"
              :disabled="acting[`deliver-${po.id}`]"
              @click="approveAct(`deliver-${po.id}`, () => completePurchaseDelivery(po.id))"
            >确认出货</button>
            <button
              v-if="po.cancelRequestStatus !== 'PENDING' && !['RECEIVED','CANCELLED','REJECTED'].includes(po.status)"
              class="warn-btn"
              type="button"
              @click="cancelTarget = { id: po.id, reason: '' }"
            >发起取消</button>
          </div>

          <div v-if="isBuyer(po)" class="item-acts">
            <button
              v-if="po.status === 'SHIPPED'"
              class="pri-btn"
              type="button"
              :disabled="acting[`recv-${po.id}`]"
              @click="doReceive(po.id)"
            >收货入库</button>
            <button
              v-if="po.cancelRequestStatus !== 'PENDING' && !['RECEIVED','CANCELLED','REJECTED'].includes(po.status)"
              class="warn-btn"
              type="button"
              @click="cancelTarget = { id: po.id, reason: '' }"
            >发起取消</button>
          </div>

          <!-- 备注流程表单 -->
          <div v-if="noteFormOpen === po.id" class="inline-form">
            <div class="row">
              <label class="grow">备注内容
                <input v-model="newNote.text" type="text" placeholder="如：正在调货、运输中" />
              </label>
            </div>
            <div class="acts">
              <button class="pri-btn" type="button" @click="doAddNote(po.id)">添加备注</button>
            </div>
          </div>

          <!-- 分配批次表单:产品级多行,加减号增删行 -->
          <div v-if="allocFormOpen === po.id" class="inline-form">
            <p v-if="!allocProducts.length" class="muted">库存为空，无法分配</p>
            <div v-else>
              <p v-if="allocShortage" class="err">{{ allocShortage }}</p>
              <div class="alloc-list">
                <div class="row alloc-head">
                  <span class="prod-col">批次</span>
                  <span class="avail-col">库存余量</span>
                  <span class="qty-col">分配数量</span>
                  <span class="op-col">操作</span>
                </div>
                <div
                  v-for="(l, idx) in allocDraft"
                  :key="idx"
                  class="row alloc-row"
                >
                  <span class="prod-col">
                    <select v-model="l.batchId" @change="onAllocBatchChange(l)">
                      <option v-for="p in allocProducts" :key="p.batchId" :value="p.batchId">{{ p.batchNo }} · {{ p.productName }}（余{{ p.available }}{{ p.unitCode }}）</option>
                    </select>
                  </span>
                  <span class="avail-col">{{ l.available }}{{ l.unitCode }}</span>
                  <span class="qty-col">
                    <input
                      v-model.number="l.quantity"
                      type="number"
                      min="0"
                      :max="l.available"
                      step="0.001"
                      placeholder="输入数量"
                    />
                  </span>
                  <span class="op-col">
                    <button class="mini-btn" type="button" @click="removeAllocLine(idx)" title="删除该行">−</button>
                    <button class="mini-btn add" type="button" @click="addAllocLine" title="新增一行">+</button>
                  </span>
                </div>
              </div>
              <div class="acts">
                <button class="ghost-btn" type="button" @click="smartAllocate(po.id)">智能分配</button>
                <button class="pri-btn" type="button" :disabled="allocSaving" @click="doAllocate(po.id)">
                  {{ allocSaving ? '分配中…' : '分配所选' }}
                </button>
              </div>
            </div>
          </div>

          <!-- 质检提示 -->
          <div v-if="po.status === 'PROCESSING' && poAllocations[po.id]?.length && !qcSubmitted(po.id)" class="inline-form">
            <p class="note">提交质检审核后，本环节质检员将审核清单，全部通过后方可确认出货</p>
          </div>
          <div v-if="po.status === 'PROCESSING' && qcAllPassed(po.id)" class="inline-form">
            <p class="note">质检已全部通过，可确认出货</p>
          </div>
          <div v-if="po.status === 'PROCESSING' && qcSubmitted(po.id) && !qcAllPassed(po.id)" class="inline-form">
            <p class="note">质检审核中，等待质检员通过清单</p>
          </div>
        </article>
        <p v-if="purchases.length === 0" class="muted">暂无采购单</p>
      </section>

      <!-- 销售单 -->
      <section v-else-if="activeTab === 'sales'" class="card">
        <div class="sec-head">
          <h2>销售订单</h2>
          <button class="pri-btn" type="button" @click="showCreateSales = !showCreateSales">
            {{ showCreateSales ? '收起' : '发起销售' }}
          </button>
        </div>

        <form v-if="showCreateSales" class="form" @submit.prevent="handleCreateSales">
          <div class="row">
            <label>客户称谓<input v-model="newSales.customerName" type="text" list="cust-options" /></label>
            <label>电话<input v-model="newSales.customerPhone" type="text" /></label>
            <label>商品
              <select v-model.number="newSales.productId">
                <option v-for="p in products" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </label>
            <label>数量(kg)<input v-model.number="newSales.quantity" type="number" min="1" /></label>
            <label>单价(元)<input v-model.number="newSales.unitPrice" type="number" min="0" step="0.01" /></label>
          </div>
          <datalist id="cust-options">
            <option v-for="c in saleDemoAccounts" :key="c" :value="c" />
          </datalist>
          <label>收货地址<input v-model="newSales.deliveryAddress" type="text" /></label>
          <div class="acts"><button class="pri-btn" type="submit">提交销售单</button></div>
        </form>

        <p v-if="salesError" class="err">{{ salesError }}</p>

        <article v-for="so in sales" :key="so.id" class="item-card">
          <div class="item-head">
            <span class="mono">{{ so.orderNo }}</span>
            <span class="badge" :class="statusBadge(so.status)">{{ so.status }}</span>
          </div>
          <div class="item-meta">
            <span>客户：{{ so.customerName }}<span v-if="so.customerPhone"> · {{ so.customerPhone }}</span></span>
            <span>金额 {{ currency(so.amountTotal) }}</span>
          </div>
          <div v-if="so.deliveryAddress" class="contact-info">
            <span>地址：{{ so.deliveryAddress }}</span>
          </div>
          <ul class="items">
            <li v-for="it in so.items" :key="it.id">
              {{ it.productName }} × {{ it.quantity }}{{ it.unitCode }} = {{ currency(it.rowAmount) }}
            </li>
          </ul>
          <!-- B2C 销售单操作 -->
          <div v-if="!isDownstreamOrder(so)" class="item-acts">
            <button
              v-if="so.status === 'PLACED'"
              class="pri-btn"
              type="button"
              @click="advanceSales(so.id, 'CONFIRMED')"
            >确认订单</button>
            <button
              v-if="so.status === 'CONFIRMED'"
              class="pri-btn"
              type="button"
              @click="advanceSales(so.id, 'DELIVERED')"
            >收货入库</button>
          </div>
          <!-- 下游采购单操作:上游在销售单tab直接审批/排产/出货 -->
          <div v-else class="item-acts">
            <span class="muted" style="font-size: 12px">下游采购订单</span>
            <button
              v-if="so.status === 'SUBMITTED'"
              class="pri-btn"
              type="button"
              :disabled="salesActing[`approve-${so.id}`]"
              @click="salesApprove(so.id)"
            >审批通过</button>
            <button
              v-if="so.status === 'CONFIRMED'"
              class="pri-btn"
              type="button"
              :disabled="salesActing[`schedule-${so.id}`]"
              @click="salesSchedule(so.id)"
            >按单排产</button>
            <button
              v-if="so.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="salesNoteOpen = salesNoteOpen === so.id ? null : so.id"
            >{{ salesNoteOpen === so.id ? '收起' : '备注流程' }}</button>
            <button
              v-if="so.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="openSalesAllocForm(so.id)"
            >{{ salesAllocOpen === so.id ? '收起' : '分配批次' }}</button>
            <button
              v-if="so.status === 'PROCESSING'"
              class="ghost-btn"
              type="button"
              @click="salesAllocOpen = so.id; smartAllocate(so.id)"
            >智能分配</button>
            <button
              v-if="so.status === 'PROCESSING' && (salesAllocs[so.id]?.length) && !qcSubmitted(so.id)"
              class="ghost-btn"
              type="button"
              @click="submitQualityReviewForOrder(so.id)"
            >提交质检审核</button>
            <button
              v-if="so.status === 'PROCESSING' && qcAllPassed(so.id)"
              class="pri-btn"
              type="button"
              :disabled="salesActing[`deliver-${so.id}`]"
              @click="salesDeliver(so.id)"
            >确认出货</button>
            <button
              v-if="so.cancelRequestStatus !== 'PENDING' && !['RECEIVED','CANCELLED','REJECTED'].includes(so.status)"
              class="warn-btn"
              type="button"
              @click="cancelTarget = { id: so.id, reason: '' }"
            >发起取消</button>
          </div>
          <!-- 下游采购单取消请求 -->
          <div v-if="isDownstreamOrder(so) && so.cancelRequestStatus === 'PENDING'" class="cancel-notice">
            {{ so.cancelRequestRole === 'BUYER' ? '买方' : '卖方' }}发起取消: {{ so.cancelRequestReason }}
            <span v-if="so.cancelRequestRole !== 'SELLER'">
              <button class="warn-btn sm" type="button" :disabled="salesActing[`capp-${so.id}`]" @click="salesApproveCancel(so.id)">同意取消</button>
              <button class="ghost-btn sm" type="button" :disabled="salesActing[`crej-${so.id}`]" @click="salesRejectCancel(so.id)">拒绝取消</button>
            </span>
            <span v-else>
              <button class="ghost-btn sm" type="button" :disabled="salesActing[`cw-${so.id}`]" @click="salesWithdrawCancel(so.id)">撤回取消</button>
              <span class="muted">等待对方审核</span>
            </span>
          </div>

          <!-- 下游采购单质检状态 -->
          <div v-if="isDownstreamOrder(so) && qcSubmitted(so.id)" class="qc-status">
            <span class="badge" :class="statusBadge(qcStatusOf(so.id))">
              {{ qcStatusOf(so.id) === 'PASS' ? '质检已通过' : qcStatusOf(so.id) === 'INSPECTING' ? '质检中' : '质检未通过' }}
            </span>
            <ul>
              <li v-for="qc in orderQcMap[so.id]" :key="qc.id">
                {{ qc.inspectionNo }}
                <span v-if="qc.inspectionStage"> · {{ stageLabels[qc.inspectionStage] }}</span>
                · {{ qc.result === 'PASS' ? '已通过' : qc.result === 'INSPECTING' ? '待质检' : '未通过' }}
              </li>
            </ul>
          </div>

          <!-- 下游采购单进度备注时间线 -->
          <div v-if="isDownstreamOrder(so) && (poNotes[so.id]?.length)" class="note-timeline">
            <h4>进度备注</h4>
            <ul>
              <li v-for="n in poNotes[so.id]" :key="n.id">
                <span class="mono">{{ formatTime(n.createdAt) }}</span>
                <span class="badge" :class="statusBadge(n.statusAt)">{{ n.statusAt }}</span>
                {{ n.noteText }}
              </li>
            </ul>
          </div>
          <!-- 下游采购单已分配批次 -->
          <div v-if="isDownstreamOrder(so) && (salesAllocs[so.id]?.length)" class="alloc-list">
            <h4>已分配批次</h4>
            <ul>
              <li v-for="a in salesAllocs[so.id]" :key="a.batchId">
                {{ a.batchNo }} → {{ a.allocatedQuantity }}{{ a.unitCode }}
              </li>
            </ul>
          </div>
          <!-- 备注流程表单 -->
          <div v-if="isDownstreamOrder(so) && salesNoteOpen === so.id" class="inline-form">
            <div class="row">
              <label class="grow">备注内容
                <input v-model="newNoteText" type="text" placeholder="如：正在分拣打包，预计明天发出" />
              </label>
            </div>
            <div class="acts"><button class="pri-btn" type="button" @click="doAddNoteForSales(so.id)">添加备注</button></div>
          </div>
          <!-- 分配批次表单:产品级多行,加减号增删行 -->
          <div v-if="isDownstreamOrder(so) && salesAllocOpen === so.id" class="inline-form">
            <p v-if="!allocProducts.length" class="muted">库存为空，无法分配</p>
            <div v-else>
              <p v-if="allocShortage" class="err">{{ allocShortage }}</p>
              <div class="alloc-list">
                <div class="row alloc-head">
                  <span class="prod-col">批次</span>
                  <span class="avail-col">库存余量</span>
                  <span class="qty-col">分配数量</span>
                  <span class="op-col">操作</span>
                </div>
                <div
                  v-for="(l, idx) in allocDraft"
                  :key="idx"
                  class="row alloc-row"
                >
                  <span class="prod-col">
                    <select v-model="l.batchId" @change="onAllocBatchChange(l)">
                      <option v-for="p in allocProducts" :key="p.batchId" :value="p.batchId">{{ p.batchNo }} · {{ p.productName }}（余{{ p.available }}{{ p.unitCode }}）</option>
                    </select>
                  </span>
                  <span class="avail-col">{{ l.available }}{{ l.unitCode }}</span>
                  <span class="qty-col">
                    <input
                      v-model.number="l.quantity"
                      type="number"
                      min="0"
                      :max="l.available"
                      step="0.001"
                      placeholder="输入数量"
                    />
                  </span>
                  <span class="op-col">
                    <button class="mini-btn" type="button" @click="removeAllocLine(idx)" title="删除该行">−</button>
                    <button class="mini-btn add" type="button" @click="addAllocLine" title="新增一行">+</button>
                  </span>
                </div>
              </div>
              <div class="acts">
                <button class="ghost-btn" type="button" @click="smartAllocate(so.id)">智能分配</button>
                <button class="pri-btn" type="button" :disabled="allocSaving" @click="doAllocateForSales(so.id)">
                  {{ allocSaving ? '分配中…' : '分配所选' }}
                </button>
              </div>
            </div>
          </div>
          <!-- 质检提示 -->
          <div v-if="isDownstreamOrder(so) && so.status === 'PROCESSING' && (salesAllocs[so.id]?.length) && !qcSubmitted(so.id)" class="inline-form">
            <p class="note">提交质检审核后，本环节质检员将审核清单，全部通过后方可确认出货</p>
          </div>
          <div v-if="isDownstreamOrder(so) && so.status === 'PROCESSING' && qcAllPassed(so.id)" class="inline-form">
            <p class="note">质检已全部通过，可确认出货</p>
          </div>
          <div v-if="isDownstreamOrder(so) && so.status === 'PROCESSING' && qcSubmitted(so.id) && !qcAllPassed(so.id)" class="inline-form">
            <p class="note">质检审核中，等待质检员通过清单</p>
          </div>
          <p v-if="so.packedPackageNo" class="note mono">溯源码：{{ so.packedPackageNo }}</p>
        </article>
        <p v-if="sales.length === 0" class="muted">暂无销售单</p>
      </section>

      <!-- 库存 -->
      <section v-else-if="activeTab === 'batch'" class="card">
        <div class="sec-head">
          <h2>我的库存</h2>
        </div>

        <!-- 库存筛选:余量/名字/入库时间(需求4) -->
        <div class="inv-filter">
          <select v-model="invFilter.availability">
            <option value="ALL">全部余量</option>
            <option value="HAS">有余量</option>
            <option value="NONE">无余量</option>
          </select>
          <input v-model="invFilter.keyword" type="text" placeholder="按产品名/批次号筛选" />
          <select v-model="invFilter.sort">
            <option value="NEW">入库时间 新→旧</option>
            <option value="OLD">入库时间 旧→新</option>
          </select>
        </div>

        <!-- 库存汇总:每个角色看到自己组织名下的批次存货 -->
        <div v-if="myInventory.length" class="inv-summary">
          <table class="tb">
            <thead>
              <tr>
                <th>批次号</th><th>产品</th><th>总量</th><th>已分配</th><th>可用</th><th>入库时间</th><th>状态</th><th>溯源记录</th>
                <th v-if="isProcessor || isDistributor">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in filteredInventory" :key="b.batchId">
                <td class="mono">{{ b.batchNo }}</td>
                <td>{{ b.productName || '#' + b.productId }}</td>
                <td>{{ b.totalQuantity }}{{ b.unitCode }}</td>
                <td>{{ b.allocatedQuantity }}{{ b.unitCode }}</td>
                <td class="avail">{{ b.availableQuantity }}{{ b.unitCode }}</td>
                <td class="mono sm">{{ b.productionDate || (b.createdAt ? formatTime(b.createdAt) : '—') }}</td>
                <td><span class="badge" :class="statusBadge(b.status)">{{ b.status }}</span></td>
                <td>{{ b.traceEventCount }} 条</td>
                <td v-if="isProcessor || isDistributor">
                  <button
                    v-if="isProcessor && b.batchType === 'SOURCE' && b.status === 'ACTIVE' && b.availableQuantity > 0"
                    class="pri-btn sm"
                    type="button"
                    @click="openProcessForm(b.batchId)"
                  >加工</button>
                  <button
                    v-else-if="isDistributor && b.batchType === 'PROCESSED' && b.status === 'ACTIVE' && b.availableQuantity > 0"
                    class="pri-btn sm"
                    type="button"
                    @click="openRepackForm(b.batchId)"
                  >分拣分装</button>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-if="!filteredInventory.length" class="muted" style="margin-top: 12px">没有符合筛选条件的批次</p>
        </div>
        <p v-else class="muted">暂无库存批次</p>

        <!-- 批发分拣分装表单(需求5) -->
        <div v-if="repackFormOpen !== null" class="modal-overlay" @click.self="repackFormOpen = null">
          <div class="modal-box">
            <h3>分拣分装</h3>
            <p class="note">消耗上游加工批次，产出同产品分销批次(DISTRIBUTION)，产出后可在溯源树看到分拣环节</p>
            <p v-if="repackError" class="err">{{ repackError }}</p>
            <div class="form-row">
              <label>消耗数量(kg)</label>
              <input v-model.number="repackForm.consumedQuantity" type="number" step="0.001" min="0.001" placeholder="输入消耗加工批次数量" />
            </div>
            <div class="form-row">
              <label>产出数量(kg)</label>
              <input v-model.number="repackForm.outputQuantity" type="number" step="0.001" min="0.001" placeholder="输入产出分销批次数量" />
            </div>
            <div class="acts">
              <button class="pri-btn" type="button" @click="doRepack">确认分装</button>
              <button type="button" @click="repackFormOpen = null">取消</button>
            </div>
          </div>
        </div>

        <!-- 加工厂加工表单 -->
        <div v-if="processFormOpen !== null" class="modal-overlay" @click.self="processFormOpen = null">
          <div class="modal-box">
            <h3>原料加工</h3>
            <p v-if="processError" class="err">{{ processError }}</p>
            <div class="form-row">
              <label>消耗数量(kg)</label>
              <input v-model.number="processForm.consumedQuantity" type="number" step="0.001" min="0.001" placeholder="输入消耗原料数量" />
            </div>
            <div class="form-row">
              <label>产出成品</label>
              <select v-model="processForm.outputProductId">
                <option :value="0">请选择成品</option>
                <option v-for="p in finishedGoodsProducts" :key="p.id" :value="p.id">{{ p.publicName }}</option>
              </select>
            </div>
            <div class="form-row">
              <label>产出数量(kg)</label>
              <input v-model.number="processForm.outputQuantity" type="number" step="0.001" min="0.001" placeholder="输入产出成品数量" />
            </div>
            <div class="acts">
              <button class="pri-btn" type="button" @click="doProcess">确认加工</button>
              <button type="button" @click="processFormOpen = null">取消</button>
            </div>
          </div>
        </div>
      </section>

      <!-- 入库登记(捕捞船长自捕自产) -->
      <section v-else-if="activeTab === 'stockIn'" class="card">
        <div class="sec-head">
          <h2>入库登记</h2>
          <span class="note">捕捞船长自捕自产，直接创建原料批次入库</span>
        </div>
        <p v-if="stockInError" class="err">{{ stockInError }}</p>
        <p v-if="stockInSuccess" class="note" style="color: #059669">{{ stockInSuccess }}</p>
        <div class="form-grid">
          <div class="form-row">
            <label>原料商品</label>
            <select v-model="stockInForm.productId">
              <option :value="0">请选择原料</option>
              <option v-for="p in rawMaterialProducts" :key="p.id" :value="p.id">{{ p.publicName }}</option>
            </select>
          </div>
          <div class="form-row">
            <label>入库数量(kg)</label>
            <input v-model.number="stockInForm.quantity" type="number" step="0.001" min="0.001" placeholder="输入入库数量" />
          </div>
          <div class="form-row">
            <label>批次号(可选)</label>
            <input v-model="stockInForm.batchNo" type="text" placeholder="留空自动生成" />
          </div>
          <div class="form-row">
            <label>捕捞日期</label>
            <input v-model="stockInForm.captureDate" type="date" />
          </div>
          <div class="form-row" style="grid-column: 1 / -1">
            <label>产地说明</label>
            <input v-model="stockInForm.originText" type="text" placeholder="如：东海舟山渔场" />
          </div>
        </div>
        <div class="acts">
          <button class="pri-btn" type="button" @click="doDirectStockIn">确认入库</button>
        </div>

        <div v-if="myInventory.length" style="margin-top: 24px">
          <h3 class="subtitle">当前库存</h3>
          <table class="tb">
            <thead>
              <tr><th>批次号</th><th>产品</th><th>总量</th><th>可用</th><th>状态</th></tr>
            </thead>
            <tbody>
              <tr v-for="b in myInventory" :key="b.batchId">
                <td class="mono">{{ b.batchNo }}</td>
                <td>{{ b.productName || '#' + b.productId }}</td>
                <td>{{ b.totalQuantity }}{{ b.unitCode }}</td>
                <td class="avail">{{ b.availableQuantity }}{{ b.unitCode }}</td>
                <td><span class="badge" :class="statusBadge(b.status)">{{ b.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 质检 -->
      <section v-else-if="activeTab === 'quality'" class="card">
        <div class="sec-head">
          <h2>质检审核工作台</h2>
        </div>
        <p v-if="qcError" class="err">{{ qcError }}</p>

        <div class="qr-split">
          <div class="qr-list">
            <h3 class="subtitle">待审质检单</h3>
            <button
              v-for="inp in pendingInspections"
              :key="inp.id"
              type="button"
              class="qr-item"
              :class="{ active: selectedQcInspection?.id === inp.id }"
              @click="selectQcInspection(inp)"
            >
              <span class="mono">{{ inp.inspectionNo }}</span>
              <span class="badge warning">{{ stageLabels[inp.inspectionStage || ''] || inp.inspectionStage }}</span>
            </button>
            <p v-if="pendingInspections.length === 0" class="muted">暂无待审质检单</p>
          </div>

          <div class="qr-detail">
            <div v-if="selectedQcInspection">
              <h3 class="subtitle">
                {{ stageLabels[selectedQcInspection.inspectionStage || ''] || '质检单' }}
                · 批次#{{ selectedQcInspection.batchId }}
              </h3>
              <p class="item-meta">
                <span class="mono">{{ selectedQcInspection.inspectionNo }}</span>
                <span v-if="selectedQcInspection.checklistPassedAt" class="note" style="color: #059669">
                  清单已全部通过
                </span>
              </p>

              <div class="checklist">
                <label
                  v-for="(item, idx) in checklistItems"
                  :key="idx"
                  class="checklist-item"
                  :class="{ passed: item.passed }"
                >
                  <input
                    type="checkbox"
                    :checked="item.passed"
                    @change="toggleChecklistItem(item.itemName, !item.passed)"
                  />
                  <span class="cl-name">{{ item.itemName }}</span>
                  <span v-if="item.itemDesc" class="cl-desc">{{ item.itemDesc }}</span>
                  <span v-if="item.passed && item.checkedBy" class="cl-checked">
                    {{ item.checkedBy }} · {{ item.checkedAt?.slice(0, 19).replace('T', ' ') }}
                  </span>
                </label>
              </div>

              <div class="acts" style="margin-top: 14px">
                <button
                  class="pri-btn"
                  type="button"
                  :disabled="!isAllChecklistPassed()"
                  @click="doPassInspection"
                >质检通过</button>
                <p v-if="!isAllChecklistPassed()" class="note">请先完成所有清单项打钩</p>
              </div>
            </div>
            <p v-else class="muted">请从左侧选择待审质检单</p>
          </div>
        </div>
      </section>

      <!-- 系统管理 -->
      <section v-else-if="activeTab === 'admin'" class="card">
        <div class="sec-head"><h2>系统管理概览</h2></div>
        <p v-if="adminError" class="err">{{ adminError }}</p>
        <div v-if="adminData" class="stats">
          <div class="stat"><span class="stat-n">{{ adminData.orgCount }}</span><span class="stat-l">组织</span></div>
          <div class="stat"><span class="stat-n">{{ adminData.userCount }}</span><span class="stat-l">账号</span></div>
          <div class="stat"><span class="stat-n">{{ adminData.roleCount }}</span><span class="stat-l">角色</span></div>
        </div>

        <h3 class="subtitle">组织列表</h3>
        <table v-if="adminData" class="tb">
          <thead><tr><th>ID</th><th>编码</th><th>名称</th><th>类型</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="o in adminData.orgs" :key="o.id">
              <td>{{ o.id }}</td><td class="mono">{{ o.orgNo }}</td><td>{{ o.name }}</td><td>{{ o.orgType }}</td><td>{{ o.status }}</td>
            </tr>
          </tbody>
        </table>

        <h3 class="subtitle">账号列表</h3>
        <table v-if="adminData" class="tb">
          <thead><tr><th>ID</th><th>用户名</th><th>姓名/岗位</th><th>岗位类型</th><th>所属组织</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="u in adminData.users" :key="u.id">
              <td>{{ u.id }}</td><td class="mono">{{ u.username }}</td><td>{{ u.displayName }}</td><td>{{ u.jobType }}</td><td>{{ orgName(u.orgId) }}</td><td>{{ u.status }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </main>

    <!-- 驳回面板 -->
    <div v-if="rejectTarget" class="mask">
      <div class="pop">
        <h3>驳回采购单</h3>
        <textarea v-model="rejectTarget.reason" rows="3" placeholder="请填写驳回原因（必填）"></textarea>
        <p v-if="purchaseError" class="err">{{ purchaseError }}</p>
        <div class="acts">
          <button class="ghost-btn" type="button" @click="rejectTarget = null">取消</button>
          <button class="warn-btn" type="button" @click="doReject">确认驳回</button>
        </div>
      </div>
    </div>

    <!-- 取消订单面板 -->
    <div v-if="cancelTarget" class="mask">
      <div class="pop">
        <h3>发起取消订单</h3>
        <p class="muted">取消请求需对方或管理员审核同意后生效</p>
        <textarea v-model="cancelTarget.reason" rows="3" placeholder="请填写取消原因（必填）"></textarea>
        <p v-if="purchaseError" class="err">{{ purchaseError }}</p>
        <div class="acts">
          <button class="ghost-btn" type="button" @click="cancelTarget = null">取消</button>
          <button class="warn-btn" type="button" @click="doCancel">提交取消请求</button>
        </div>
      </div>
    </div>

    <!-- 收货面板 -->
    <div v-if="acceptTarget" class="mask">
      <div class="pop">
        <h3>到货验收收货</h3>
        <label class="fld">实收数量
          <input v-model.number="acceptTarget.receivedQuantity" type="number" min="0.001" step="0.001" />
        </label>
        <label class="fld">差异原因（数量不符时必填）
          <input v-model="acceptTarget.differenceReason" type="text" />
        </label>
        <p v-if="batchError" class="err">{{ batchError }}</p>
        <div class="acts">
          <button class="ghost-btn" type="button" @click="acceptTarget = null">取消</button>
          <button class="pri-btn" type="button" @click="doAccept">确认收货</button>
        </div>
      </div>
    </div>

    <div v-if="toast" class="toast">{{ toast }}</div>
  </div>
</template>

<style scoped>
.wb {
  max-width: 1080px;
  margin: 0 auto;
}
.wb-header {
  position: sticky;
  top: 0;
  z-index: 50;
  background: var(--color-navy, #0f2a43);
  color: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}
.wb-header-inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 14px 20px;
}
.wb-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
}
.wb-brand-dot {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #38bdf8;
}
.wb-brand-text {
  font-size: 15px;
  letter-spacing: 0.02em;
}
.wb-user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}
.wb-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--color-ocean);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}
.wb-user-name {
  font-weight: 600;
}
.wb-user-org {
  opacity: 0.8;
}
.wb-user-roles {
  opacity: 0.7;
  font-size: 11px;
}
.wb-logout {
  border: 1px solid rgba(255, 255, 255, 0.4);
  background: transparent;
  color: #fff;
  border-radius: var(--radius-sm);
  padding: 5px 12px;
  cursor: pointer;
}
.wb-tabs {
  display: flex;
  gap: 4px;
  padding: 0 20px 12px;
}
.wb-tab {
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: #cfe3f7;
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
}
.wb-tab.active {
  background: var(--color-ocean);
  color: #fff;
  font-weight: 600;
}
.wb-main {
  margin-top: 16px;
}
.card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  box-shadow: var(--shadow-sm);
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.sec-head h2 {
  margin: 0;
  font-size: 18px;
  color: var(--color-text-title);
}
.subtitle {
  margin: 4px 0 10px;
  font-size: 14px;
  color: var(--color-text-body);
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border: 1px dashed var(--color-border-dark);
  border-radius: var(--radius-md);
  margin-bottom: 12px;
  background: var(--color-ocean-subtle);
}
.row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1 1 120px;
  font-size: 12px;
  color: var(--color-text-muted);
}
input,
select,
textarea {
  padding: 8px 10px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--color-text-title);
}
.acts {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.pri-btn {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-ocean);
  color: #fff;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.pri-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.warn-btn {
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-warning, #f6c344);
  color: #5b4300;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.ghost-btn {
  border: 1px solid var(--color-border);
  background: var(--color-card);
  border-radius: var(--radius-sm);
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
}
.inline-form {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  background-color: #fbfdff;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.inline-form .grow {
  flex: 1 1 auto;
}
.item-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  margin-bottom: 10px;
}
.item-head,
.item-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.item-head {
  margin-bottom: 6px;
}
.item-meta {
  font-size: 13px;
  color: var(--color-text-muted);
}
.items {
  margin: 8px 0 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--color-text-body);
}
.note {
  font-size: 12px;
  color: var(--color-text-muted);
}
.item-acts {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.badge {
  font-size: 11px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 999px;
}
.badge.neutral {
  background: #eef2f7;
  color: #475569;
}
.badge.info {
  background: #e0f2fe;
  color: #0369a1;
}
.badge.success {
  background: #dcfce7;
  color: #15803d;
}
.badge.warning {
  background: #fef3c7;
  color: #b45309;
}
.badge.danger {
  background: #fee2e2;
  color: #b91c1c;
}
.err {
  color: var(--color-danger-text);
  font-size: 13px;
}
.muted {
  color: var(--color-text-muted);
}
.mono {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.qr-split {
  display: grid;
  grid-template-columns: 1fr 1.5fr;
  gap: 18px;
}
.qr-list {
  border-right: 1px solid var(--color-border);
  padding-right: 16px;
}
.qr-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 9px 12px;
  margin-bottom: 6px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-card);
  cursor: pointer;
  text-align: left;
}
.qr-item.active {
  border-color: var(--color-ocean);
  background: var(--color-ocean-subtle);
}
.qc-summary {
  margin-top: 10px;
  width: 100%;
  box-sizing: border-box;
}
.stats {
  display: flex;
  gap: 14px;
  margin-bottom: 18px;
}
.stat {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-ocean-subtle);
}
.stat-n {
  font-size: 28px;
  font-weight: 800;
  color: var(--color-ocean);
}
.stat-l {
  font-size: 13px;
  color: var(--color-text-muted);
}
.tb {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-bottom: 18px;
}
.tb th,
.tb td {
  border: 1px solid var(--color-border);
  padding: 7px 9px;
  text-align: left;
}
.tb th {
  background: var(--color-ocean-subtle);
  color: var(--color-text-title);
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.pop {
  width: 380px;
  max-width: 92vw;
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: var(--shadow-lg);
}
.pop h3 {
  margin: 0;
  font-size: 16px;
  color: var(--color-text-title);
}
.fld {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
}
.toast {
  position: fixed;
  left: 50%;
  bottom: 28px;
  transform: translateX(-50%);
  background: var(--color-navy, #0f2a43);
  color: #fff;
  padding: 10px 20px;
  border-radius: var(--radius-md);
  font-size: 14px;
  box-shadow: var(--shadow-lg);
  z-index: 200;
}
@media (max-width: 760px) {
  .qr-split {
    grid-template-columns: 1fr;
  }
  .qr-list {
    border-right: none;
    padding-right: 0;
  }
}

.note-timeline,
.alloc-list {
  margin: 8px 0;
  padding: 8px 12px;
  background: #f8fafc;
  border-left: 3px solid #3b82f6;
  border-radius: 4px;
}
/* 分配表单:产品级多行 grid 布局 */
.alloc-list .row.alloc-head,
.alloc-list .row.alloc-row {
  display: grid;
  grid-template-columns: 1.6fr 1fr 1fr 0.7fr;
  gap: 8px;
  align-items: center;
  padding: 4px 0;
}
.alloc-list .row.alloc-head {
  font-size: 12px;
  font-weight: 700;
  color: #475569;
  border-bottom: 1px solid #e2e8f0;
}
.alloc-list .row.alloc-row {
  border-bottom: 1px dashed #e2e8f0;
}
.alloc-list .row.alloc-row .qty-col input,
.alloc-list .row.alloc-row .prod-col select {
  width: 100%;
  padding: 3px 6px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  font-size: 13px;
}
.alloc-list .row.alloc-row .avail-col {
  font-size: 13px;
  color: #059669;
  font-weight: 600;
}
.mini-btn {
  width: 26px;
  height: 26px;
  line-height: 1;
  border: 1px solid #cbd5e1;
  border-radius: 5px;
  background: #fff;
  color: #334155;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  margin-right: 4px;
  transition: all 0.15s;
}
.mini-btn:hover {
  border-color: #2563eb;
  color: #2563eb;
}
.mini-btn.add:hover {
  border-color: #059669;
  color: #059669;
}
.contact-info {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin: 6px 0;
  padding: 6px 12px;
  background: #f0f9ff;
  border-radius: 4px;
  font-size: 12px;
  color: #0369a1;
}
.qc-quick-tb {
  font-size: 13px;
  margin-bottom: 6px;
}
.qc-quick-tb th {
  background: #f1f5f9;
  padding: 6px 10px;
  text-align: left;
}
.qc-quick-tb td {
  padding: 6px 10px;
  border-bottom: 1px solid #e2e8f0;
}
.checklist {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.checklist-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  cursor: pointer;
  background: #fff;
  transition: border-color 0.15s;
}
.checklist-item:hover {
  border-color: #94a3b8;
}
.checklist-item.passed {
  border-color: #10b981;
  background: #ecfdf5;
}
.checklist-item input[type='checkbox'] {
  margin-top: 3px;
  width: 16px;
  height: 16px;
  cursor: pointer;
}
.cl-name {
  font-size: 14px;
  font-weight: 500;
  color: #1e293b;
}
.cl-desc {
  font-size: 12px;
  color: #64748b;
  margin-left: 8px;
}
.cl-checked {
  font-size: 11px;
  color: #059669;
  margin-left: auto;
  white-space: nowrap;
}
.trace-code-box {
  margin: 8px 0;
  padding: 8px 12px;
  background: #ecfdf5;
  border-left: 3px solid #10b981;
  border-radius: 4px;
  font-size: 13px;
  color: #065f46;
}
.trace-code-box .mono {
  font-family: monospace;
  font-weight: 700;
  letter-spacing: 1px;
}
.cancel-notice {
  margin: 8px 0;
  padding: 8px 12px;
  background: #fef3c7;
  border-left: 3px solid #f59e0b;
  border-radius: 4px;
  font-size: 13px;
  color: #92400e;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.cancel-notice .muted {
  color: #78716c;
  font-size: 12px;
}
.qc-status {
  margin: 8px 0;
  padding: 8px 12px;
  background: #eff6ff;
  border-left: 3px solid #3b82f6;
  border-radius: 4px;
  font-size: 13px;
  color: #1e40af;
}
.qc-status ul {
  margin: 4px 0 0 0;
  padding-left: 16px;
}
.qc-status li {
  font-size: 12px;
  color: #475569;
}
.pri-btn.sm,
.warn-btn.sm,
.ghost-btn.sm {
  padding: 3px 8px;
  font-size: 12px;
}
.inv-filter {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
  margin: 0 0 12px 0;
}
.inv-filter select,
.inv-filter input {
  padding: 5px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
}
.inv-filter input {
  flex: 1;
  min-width: 180px;
}
.mono.sm {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}
.inv-summary {
  margin: 10px 0 16px 0;
  overflow-x: auto;
}
.inv-summary .tb {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.inv-summary .tb th {
  background: #f1f5f9;
  padding: 6px 10px;
  text-align: left;
  color: #475569;
  border-bottom: 2px solid #e2e8f0;
  white-space: nowrap;
}
.inv-summary .tb td {
  padding: 6px 10px;
  border-bottom: 1px solid #f1f5f9;
}
.inv-summary .tb td.avail {
  color: #059669;
  font-weight: 700;
}
.note-timeline h4,
.alloc-list h4 {
  margin: 0 0 6px 0;
  font-size: 13px;
  color: #475569;
}
.note-timeline ul,
.alloc-list ul {
  list-style: none;
  padding: 0;
  margin: 0;
}
.note-timeline li,
.alloc-list li {
  font-size: 13px;
  padding: 2px 0;
  color: #334155;
}
.note-timeline .mono {
  color: #94a3b8;
  margin-right: 8px;
  font-size: 12px;
}
.note-timeline .note-text {
  color: #1e293b;
}

.inline-form {
  margin: 8px 0;
  padding: 10px 12px;
  background: #f1f5f9;
  border: 1px dashed #cbd5e1;
  border-radius: 4px;
}
.inline-form .row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  flex-wrap: wrap;
}
.inline-form label {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #64748b;
}
.inline-form label.grow {
  flex: 1;
  min-width: 200px;
}
.inline-form input,
.inline-form select {
  margin-top: 4px;
  padding: 4px 8px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  font-size: 14px;
}
.inline-form .acts {
  margin-top: 8px;
}

/* 加工弹窗 + 入库表单 */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-box {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  min-width: 360px;
  max-width: 480px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.2);
}
.modal-box h3 {
  margin: 0 0 16px;
  font-size: 18px;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.form-row {
  display: flex;
  flex-direction: column;
}
.form-row label {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 4px;
}
.form-row input,
.form-row select {
  padding: 6px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  font-size: 14px;
}
.pri-btn.sm {
  padding: 2px 10px;
  font-size: 12px;
}
</style>