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
  updateSalesStatus,
  addOrderNote,
  listOrderNotes,
  allocateBatch,
  listAllocations,
  removeAllocation,
  type PurchaseOrder,
  type SalesOrder,
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
  type BatchItem,
  type Transfer
} from '@/api/batches'
import { listProducts, type Product } from '@/api/products'
import {
  listInspections,
  createInspection,
  submitInspectionResult,
  type QualityInspection
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
    purchaseError.value = '请填写取消原因'
    return
  }
  const id = cancelTarget.value.id
  await approveAct(`cancel-${id}`, () => requestCancelOrder(id, cancelTarget.value!.reason))
  cancelTarget.value = null
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
const newAlloc = ref({ batchId: 0, quantity: 0 })

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

async function doAllocate(poId: number) {
  if (!newAlloc.value.batchId || !newAlloc.value.quantity) {
    purchaseError.value = '请选择批次并填写分配数量'
    return
  }
  try {
    await allocateBatch(poId, {
      batchId: newAlloc.value.batchId,
      allocatedQuantity: newAlloc.value.quantity
    })
    newAlloc.value = { batchId: 0, quantity: 0 }
    showToast('批次已分配')
    await loadMyInventory()
    await loadPoNotesAndAllocations()
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '分配失败'
  }
}

// 质检快捷入口:在采购单卡片上直接查看分配批次的质检状态并一键通过
const qcQuickOpen = ref<number | null>(null)
const poBatchQc = ref<Record<number, { batchId: number; batchNo: string; passed: boolean }[]>>({})

async function loadPoBatchQc(poId: number) {
  const allocs = poAllocations.value[poId] || []
  const result: { batchId: number; batchNo: string; passed: boolean }[] = []
  for (const a of allocs) {
    try {
      const inspections = await listInspections(a.batchId)
      const passed = inspections.some((i) => i.result === 'PASS')
      result.push({ batchId: a.batchId, batchNo: a.batchNo, passed })
    } catch {
      result.push({ batchId: a.batchId, batchNo: a.batchNo, passed: false })
    }
  }
  poBatchQc.value[poId] = result
}

async function toggleQcQuick(poId: number) {
  if (qcQuickOpen.value === poId) {
    qcQuickOpen.value = null
    return
  }
  qcQuickOpen.value = poId
  await loadPoBatchQc(poId)
}

async function quickPassQc(poId: number, batchId: number) {
  try {
    const created = await createInspection(batchId, {
      inspectionType: 'OUTGOING',
      summary: '出厂抽样检验-快捷通过'
    })
    await submitInspectionResult(created.id, { result: 'PASS', summary: '合格放行' })
    showToast('质检已通过')
    await loadPoBatchQc(poId)
  } catch (e) {
    purchaseError.value = e instanceof Error ? e.message : '质检操作失败'
  }
}

function canDeliver(po: PurchaseOrder): boolean {
  const batches = poBatchQc.value[po.id]
  if (!batches || batches.length === 0) return false
  return batches.every((b) => b.passed)
}

// ==================== 销售单 ====================
const sales = ref<SalesOrder[]>([])
const salesError = ref('')

// ==================== 销售单tab:下游采购单的备注/分配/质检操作 ====================
const salesNoteOpen = ref<number | null>(null)
const salesAllocOpen = ref<number | null>(null)
const salesQcOpen = ref<number | null>(null)
const salesAllocs = ref<Record<number, BatchAllocation[]>>({})
const salesBatchQc = ref<Record<number, { batchId: number; batchNo: string; passed: boolean }[]>>({})
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
  const allocs = salesAllocs.value[poId] || []
  const result: { batchId: number; batchNo: string; passed: boolean }[] = []
  for (const a of allocs) {
    try {
      const inspections = await listInspections(a.batchId)
      const passed = inspections.some((i) => i.result === 'PASS')
      result.push({ batchId: a.batchId, batchNo: a.batchNo, passed })
    } catch {
      result.push({ batchId: a.batchId, batchNo: a.batchNo, passed: false })
    }
  }
  salesBatchQc.value[poId] = result
}

function canDeliverSales(so: SalesOrder): boolean {
  const batches = salesBatchQc.value[so.id]
  if (!batches || batches.length === 0) return false
  return batches.every((b) => b.passed)
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

async function doAllocateForSales(poId: number) {
  if (!newAlloc.value.batchId || !newAlloc.value.quantity) {
    salesError.value = '请选择批次并填写分配数量'
    return
  }
  try {
    await allocateBatch(poId, {
      batchId: newAlloc.value.batchId,
      allocatedQuantity: newAlloc.value.quantity
    })
    newAlloc.value = { batchId: 0, quantity: 0 }
    showToast('批次已分配')
    await loadMyInventory()
    await loadPoAllocForSales(poId)
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '分配失败'
  }
}

async function quickPassQcForSales(poId: number, batchId: number) {
  try {
    const created = await createInspection(batchId, {
      inspectionType: 'OUTGOING',
      summary: '出厂抽样检验-快捷通过'
    })
    await submitInspectionResult(created.id, { result: 'PASS', summary: '合格放行' })
    showToast('质检已通过')
    await loadSalesBatchQc(poId)
  } catch (e) {
    salesError.value = e instanceof Error ? e.message : '质检操作失败'
  }
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

// ==================== 质检 ====================
const qcBatches = ref<BatchItem[]>([])
const inspectionMap = ref<Record<number, QualityInspection[]>>({})
const qcError = ref('')
const selectedQcBatch = ref<number | null>(null)
const qcSummary = ref('')

async function loadQuality() {
  qcError.value = ''
  try {
    const bs = await listBatches()
    qcBatches.value = bs
    if (bs.length > 0) selectQcBatch(bs[0].id)
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '质检数据加载失败'
  }
}

async function selectQcBatch(id: number) {
  selectedQcBatch.value = id
  try {
    inspectionMap.value[id] = await listInspections(id)
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '质检单加载失败'
  }
}

function qcInspections(): QualityInspection[] {
  return selectedQcBatch.value ? inspectionMap.value[selectedQcBatch.value] || [] : []
}

async function addOutgoingInspection() {
  if (!selectedQcBatch.value) return
  try {
    await createInspection(selectedQcBatch.value, {
      inspectionType: 'OUTGOING',
      summary: qcSummary.value || '出厂抽样检验'
    })
    qcSummary.value = ''
    showToast('质检单已创建（待判定）')
    await selectQcBatch(selectedQcBatch.value)
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '创建失败'
  }
}

async function decideInsp(inp: QualityInspection, result: 'PASS' | 'FAIL') {
  try {
    await submitInspectionResult(inp.id, { result, summary: result === 'PASS' ? '合格放行' : '不合格' })
    showToast('质检判定已提交')
    if (selectedQcBatch.value) await selectQcBatch(selectedQcBatch.value)
  } catch (e) {
    qcError.value = e instanceof Error ? e.message : '判定失败'
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
  batch: loadMyInventory,
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
            <span v-else class="muted">等待对方审核</span>
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
              @click="allocFormOpen = allocFormOpen === po.id ? null : po.id"
            >{{ allocFormOpen === po.id ? '收起' : '分配批次' }}</button>
            <button
              v-if="po.status === 'PROCESSING' && poAllocations[po.id]?.length"
              class="ghost-btn"
              type="button"
              @click="toggleQcQuick(po.id)"
            >{{ qcQuickOpen === po.id ? '收起' : '质检快捷' }}</button>
            <button
              v-if="po.status === 'PROCESSING'"
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
              @click="approveAct(`recv-${po.id}`, () => doReceive(po.id))"
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

          <!-- 分配批次表单 -->
          <div v-if="allocFormOpen === po.id" class="inline-form">
            <p v-if="!myInventory.length" class="muted">库存为空，无法分配</p>
            <div v-else>
              <div class="row">
                <label>选择批次
                  <select v-model="newAlloc.batchId">
                    <option v-for="b in myInventory" :key="b.batchId" :value="b.batchId">
                      {{ b.batchNo }} · 可用 {{ b.availableQuantity }}{{ b.unitCode }}
                    </option>
                  </select>
                </label>
                <label>分配数量(kg)
                  <input v-model.number="newAlloc.quantity" type="number" min="0" step="0.001" />
                </label>
              </div>
              <div class="acts">
                <button class="pri-btn" type="button" @click="doAllocate(po.id)">分配</button>
              </div>
            </div>
          </div>

          <!-- 质检快捷面板 -->
          <div v-if="qcQuickOpen === po.id" class="inline-form">
            <p v-if="!poAllocations[po.id]?.length" class="muted">请先分配批次</p>
            <div v-else>
              <table class="tb qc-quick-tb">
                <thead>
                  <tr><th>批次号</th><th>质检状态</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-for="b in poBatchQc[po.id] || []" :key="b.batchId">
                    <td class="mono">{{ b.batchNo }}</td>
                    <td>
                      <span v-if="b.passed" class="badge success">已通过</span>
                      <span v-else class="badge warning">未质检</span>
                    </td>
                    <td>
                      <button v-if="!b.passed" class="pri-btn sm" type="button" @click="quickPassQc(po.id, b.batchId)">一键通过</button>
                      <span v-else class="muted">无需操作</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-if="!canDeliver(po)" class="note">所有分配批次需质检通过后方可确认出货</p>
              <p v-else class="note" style="color: #059669">质检全部通过，可以确认出货</p>
            </div>
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
              @click="salesAllocOpen = salesAllocOpen === so.id ? null : so.id; loadPoAllocForSales(so.id)"
            >{{ salesAllocOpen === so.id ? '收起' : '分配批次' }}</button>
            <button
              v-if="so.status === 'PROCESSING' && (salesAllocs[so.id]?.length)"
              class="ghost-btn"
              type="button"
              @click="salesQcOpen = salesQcOpen === so.id ? null : so.id; loadSalesBatchQc(so.id)"
            >{{ salesQcOpen === so.id ? '收起' : '质检快捷' }}</button>
            <button
              v-if="so.status === 'PROCESSING'"
              class="pri-btn"
              type="button"
              :disabled="salesActing[`deliver-${so.id}`]"
              @click="salesDeliver(so.id)"
            >确认出货</button>
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
          <!-- 分配批次表单 -->
          <div v-if="isDownstreamOrder(so) && salesAllocOpen === so.id" class="inline-form">
            <p v-if="!myInventory.length" class="muted">库存为空，无法分配</p>
            <div v-else class="row">
              <label>选择批次
                <select v-model.number="newAlloc.batchId">
                  <option :value="0">请选择</option>
                  <option v-for="b in myInventory" :key="b.batchId" :value="b.batchId">
                    {{ b.batchNo }}（可用 {{ b.availableQuantity }}{{ b.unitCode }}）
                  </option>
                </select>
              </label>
              <label>分配数量(kg)
                <input v-model.number="newAlloc.quantity" type="number" min="0.01" step="0.01" />
              </label>
              <div class="acts"><button class="pri-btn" type="button" @click="doAllocateForSales(so.id)">分配</button></div>
            </div>
          </div>
          <!-- 质检快捷面板 -->
          <div v-if="isDownstreamOrder(so) && salesQcOpen === so.id" class="inline-form">
            <p v-if="!salesAllocs[so.id]?.length" class="muted">请先分配批次</p>
            <div v-else>
              <table class="tb qc-quick-tb">
                <thead>
                  <tr><th>批次号</th><th>质检状态</th><th>操作</th></tr>
                </thead>
                <tbody>
                  <tr v-for="b in salesBatchQc[so.id] || []" :key="b.batchId">
                    <td class="mono">{{ b.batchNo }}</td>
                    <td>
                      <span v-if="b.passed" class="badge success">已通过</span>
                      <span v-else class="badge warning">未质检</span>
                    </td>
                    <td>
                      <button v-if="!b.passed" class="pri-btn sm" type="button" @click="quickPassQcForSales(so.id, b.batchId)">一键通过</button>
                      <span v-else class="muted">无需操作</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-if="!canDeliverSales(so)" class="note">所有分配批次需质检通过后方可确认出货</p>
              <p v-else class="note" style="color: #059669">质检全部通过，可以确认出货</p>
            </div>
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

        <!-- 库存汇总:每个角色看到自己组织名下的批次存货 -->
        <div v-if="myInventory.length" class="inv-summary">
          <table class="tb">
            <thead>
              <tr>
                <th>批次号</th><th>产品</th><th>总量</th><th>已分配</th><th>可用</th><th>状态</th><th>溯源记录</th>
                <th v-if="isProcessor">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="b in myInventory" :key="b.batchId">
                <td class="mono">{{ b.batchNo }}</td>
                <td>{{ b.productName || '#' + b.productId }}</td>
                <td>{{ b.totalQuantity }}{{ b.unitCode }}</td>
                <td>{{ b.allocatedQuantity }}{{ b.unitCode }}</td>
                <td class="avail">{{ b.availableQuantity }}{{ b.unitCode }}</td>
                <td><span class="badge" :class="statusBadge(b.status)">{{ b.status }}</span></td>
                <td>{{ b.traceEventCount }} 条</td>
                <td v-if="isProcessor">
                  <button
                    v-if="b.batchType === 'SOURCE' && b.status === 'ACTIVE' && b.availableQuantity > 0"
                    class="pri-btn sm"
                    type="button"
                    @click="openProcessForm(b.batchId)"
                  >加工</button>
                  <span v-else class="muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="muted">暂无库存批次</p>

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
          <h2>质检工作台</h2>
        </div>
        <p v-if="qcError" class="err">{{ qcError }}</p>

        <div class="qr-split">
          <div class="qr-list">
            <h3 class="subtitle">本企业批次</h3>
            <button
              v-for="b in qcBatches"
              :key="b.id"
              type="button"
              class="qr-item"
              :class="{ active: selectedQcBatch === b.id }"
              @click="selectQcBatch(b.id)"
            >
              <span class="mono">{{ b.batchNo }}</span>
              <span class="badge" :class="statusBadge(b.status)">{{ b.status }}</span>
            </button>
            <p v-if="qcBatches.length === 0" class="muted">暂无批次</p>

            <div class="acts" style="margin-top: 14px">
              <button
                v-if="selectedQcBatch"
                class="pri-btn"
                type="button"
                @click="addOutgoingInspection"
              >对选中批次创建出厂质检</button>
            </div>
            <input v-model="qcSummary" class="qc-summary" type="text" placeholder="质检说明（选填）" />
          </div>

          <div class="qr-detail">
            <h3 class="subtitle">质检记录</h3>
            <article v-for="inp in qcInspections()" :key="inp.id" class="item-card">
              <div class="item-head">
                <span class="mono">{{ inp.inspectionNo }}</span>
                <span class="badge" :class="statusBadge(inp.result)">{{ inp.result }}</span>
              </div>
              <div class="item-meta">
                <span>{{ inp.inspectionType }} · {{ inp.inspectorName || '待判定' }}</span>
                <span v-if="inp.summary" class="note">{{ inp.summary }}</span>
              </div>
              <div v-if="inp.result === 'INSPECTING'" class="item-acts">
                <button class="pri-btn" type="button" @click="decideInsp(inp, 'PASS')">判定合格</button>
                <button class="warn-btn" type="button" @click="decideInsp(inp, 'FAIL')">判定不合格</button>
              </div>
            </article>
            <p v-if="qcInspections().length === 0" class="muted">该批次暂无质检单</p>
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
.pri-btn.sm,
.warn-btn.sm,
.ghost-btn.sm {
  padding: 3px 8px;
  font-size: 12px;
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