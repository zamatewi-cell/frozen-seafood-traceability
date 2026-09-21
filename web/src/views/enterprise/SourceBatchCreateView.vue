<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { createSourceBatch, submitBatch } from '@/api/batches'
import { ApiError } from '@/api/client'
import { listActiveProducts } from '@/api/directory'
import { IdempotencyKeyTracker } from '@/api/idempotency'
import { useSession } from '@/stores/session'
import { ORIGIN_TYPES, type CreateSourceBatchRequest, type OriginType, type Product } from '@/types/enterprise'
import { describeWriteError } from '@/utils/apiErrors'
import { formatOriginType } from '@/utils/formatters'
import { canManageSourceBatches } from '@/utils/permissions'
import { setBatchFlash } from './batchFlash'

type ProductLoadState = 'loading' | 'loaded' | 'error'
type FieldName = 'productId' | 'externalBatchNo' | 'quantity' | 'originType' | 'originText' | 'shelfLifeDays'

const QUANTITY_PATTERN = /^\d{1,15}(\.\d{1,3})?$/

const router = useRouter()
const { user } = useSession()
const allowed = computed(() => canManageSourceBatches(user.value))

const productState = ref<ProductLoadState>('loading')
const products = ref<Product[]>([])
const productError = ref('')

const form = reactive({
  productId: '',
  externalBatchNo: '',
  quantity: '',
  originType: '' as OriginType | '',
  originText: '',
  productionDate: '',
  captureDate: '',
  freezeDate: '',
  shelfLifeDays: ''
})
const fieldErrors = reactive<Partial<Record<FieldName, string>>>({})
const submitting = ref<'draft' | 'activate' | null>(null)
const submitError = ref('')
const keyTracker = new IdempotencyKeyTracker()

let productRequest: AbortController | null = null

async function loadProducts() {
  productRequest?.abort()
  const controller = new AbortController()
  productRequest = controller
  productState.value = 'loading'
  productError.value = ''
  try {
    const items = await listActiveProducts(controller.signal)
    if (controller.signal.aborted) return
    products.value = items
    productState.value = 'loaded'
  } catch (err: unknown) {
    if (controller.signal.aborted) return
    productState.value = 'error'
    productError.value = err instanceof ApiError
      ? `产品列表加载失败：${err.message}${err.requestId ? `（请求编号 ${err.requestId}）` : ''}`
      : '产品列表加载失败，请稍后重试'
  }
}

function clearFieldErrors() {
  for (const key of Object.keys(fieldErrors) as FieldName[]) delete fieldErrors[key]
}

/** 客户端预校验（与服务端规则一致）；通过时返回只含企业可填写字段的请求体。 */
function validate(): CreateSourceBatchRequest | null {
  clearFieldErrors()
  const productId = Number(form.productId)
  if (!form.productId || !products.value.some((p) => p.id === productId)) {
    fieldErrors.productId = '请选择一个启用中的产品'
  }
  const externalBatchNo = form.externalBatchNo.trim()
  if (externalBatchNo.length > 64) fieldErrors.externalBatchNo = '外部批号不能超过 64 个字符'

  const quantityText = form.quantity.trim()
  if (!quantityText) {
    fieldErrors.quantity = '请填写声明数量'
  } else if (!QUANTITY_PATTERN.test(quantityText) || Number(quantityText) <= 0) {
    fieldErrors.quantity = '数量必须大于 0，且最多保留 3 位小数'
  }

  if (!form.originType || !ORIGIN_TYPES.includes(form.originType)) fieldErrors.originType = '请选择来源类型'
  const originText = form.originText.trim()
  if (!originText) {
    fieldErrors.originText = '请填写来源说明'
  } else if (originText.length > 255) {
    fieldErrors.originText = '来源说明不能超过 255 个字符'
  }

  const shelfText = form.shelfLifeDays.trim()
  if (shelfText && (!/^\d+$/.test(shelfText) || Number(shelfText) < 1)) {
    fieldErrors.shelfLifeDays = '保质期必须是大于 0 的整数天数'
  }

  if (Object.keys(fieldErrors).length > 0) return null
  const request: CreateSourceBatchRequest = {
    productId,
    quantity: Number(quantityText),
    unitCode: 'kg',
    originType: form.originType as OriginType,
    originText
  }
  if (externalBatchNo) request.externalBatchNo = externalBatchNo
  if (form.productionDate) request.productionDate = form.productionDate
  if (form.captureDate) request.captureDate = form.captureDate
  if (form.freezeDate) request.freezeDate = form.freezeDate
  if (shelfText) request.shelfLifeDays = Number(shelfText)
  return request
}

function applyServerFieldErrors(err: unknown) {
  if (!(err instanceof ApiError)) return
  for (const item of err.fieldErrors) {
    if (item.field in form) fieldErrors[item.field as FieldName] = item.message
  }
}

async function save(activate: boolean) {
  if (submitting.value) return
  submitError.value = ''
  const request = validate()
  if (!request) return

  submitting.value = activate ? 'activate' : 'draft'
  let created
  try {
    created = await createSourceBatch(request, keyTracker.keyFor(request))
  } catch (err: unknown) {
    submitting.value = null
    if (err instanceof ApiError && err.status === 401) return
    applyServerFieldErrors(err)
    submitError.value = describeWriteError(err, '保存来源批次草稿')
    return
  }

  // 草稿已落库：后续无论激活是否成功都不再复用本次幂等键
  keyTracker.reset()
  if (activate) {
    try {
      await submitBatch(created.id, created.version)
      setBatchFlash(created.id, { tone: 'success', message: '来源批次已激活，系统已自动生成 SOURCE 追溯事件。' })
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 401) return
      setBatchFlash(created.id, {
        tone: 'warning',
        message: `草稿已保存，但${describeWriteError(err, '提交激活')}。可在本页重新提交激活。`
      })
    }
  } else {
    setBatchFlash(created.id, { tone: 'success', message: '来源批次草稿已保存。' })
  }
  submitting.value = null
  await router.push(`/app/batches/${created.id}`)
}

onMounted(() => {
  if (allowed.value) loadProducts()
})
onBeforeUnmount(() => productRequest?.abort())
</script>

<template>
  <div class="source-batch-page">
    <RouterLink to="/app/batches" class="ent-button back-link">← 返回批次列表</RouterLink>

    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">新建来源批次</h1>
        <p class="ent-page-subtitle">
          批次类型固定为来源批次；追溯批次号、责任组织与状态均由服务端生成，保存后为草稿（DRAFT / NORMAL）。
        </p>
      </div>
    </div>

    <div v-if="!allowed" class="ent-card ent-state error" role="alert" data-testid="source-batch-forbidden">
      只有来源企业的操作员可以新建来源批次。
    </div>

    <form v-else class="ent-card source-form" novalidate data-testid="source-batch-form" @submit.prevent="save(false)">
      <div class="form-grid">
        <label class="form-field wide">
          <span>产品 <em>*</em></span>
          <div v-if="productState === 'loading'" class="field-state" data-testid="products-loading">正在加载启用中的产品…</div>
          <div v-else-if="productState === 'error'" class="field-state error" role="alert" data-testid="products-error">
            {{ productError }}
            <button type="button" class="ent-button" @click="loadProducts">重试</button>
          </div>
          <div v-else-if="products.length === 0" class="field-state" data-testid="products-empty">
            当前没有启用中的产品，请联系平台管理员维护产品主数据。
          </div>
          <select
            v-else
            id="source-product"
            v-model="form.productId"
            data-testid="field-product"
            :aria-invalid="Boolean(fieldErrors.productId)"
          >
            <option value="">请选择产品</option>
            <option v-for="product in products" :key="product.id" :value="String(product.id)">
              {{ product.publicName }}（{{ product.productCode }} · {{ product.specification }}）
            </option>
          </select>
          <small v-if="fieldErrors.productId" class="field-error" data-testid="error-productId">{{ fieldErrors.productId }}</small>
        </label>

        <label class="form-field">
          <span>外部批号（可选）</span>
          <input v-model="form.externalBatchNo" data-testid="field-external-batch-no" maxlength="64" placeholder="供应商或捕捞单据上的原始批号，可重复" />
          <small v-if="fieldErrors.externalBatchNo" class="field-error">{{ fieldErrors.externalBatchNo }}</small>
        </label>

        <label class="form-field">
          <span>声明数量 <em>*</em></span>
          <div class="quantity-input">
            <input
              v-model="form.quantity"
              data-testid="field-quantity"
              inputmode="decimal"
              placeholder="例如 1000"
              :aria-invalid="Boolean(fieldErrors.quantity)"
            />
            <span class="unit" data-testid="field-unit">kg</span>
          </div>
          <small v-if="fieldErrors.quantity" class="field-error" data-testid="error-quantity">{{ fieldErrors.quantity }}</small>
        </label>

        <label class="form-field">
          <span>来源类型 <em>*</em></span>
          <select v-model="form.originType" data-testid="field-origin-type" :aria-invalid="Boolean(fieldErrors.originType)">
            <option value="">请选择来源类型</option>
            <option v-for="type in ORIGIN_TYPES" :key="type" :value="type">{{ formatOriginType(type) }}（{{ type }}）</option>
          </select>
          <small v-if="fieldErrors.originType" class="field-error" data-testid="error-originType">{{ fieldErrors.originType }}</small>
        </label>

        <label class="form-field wide">
          <span>来源说明 <em>*</em></span>
          <input
            v-model="form.originText"
            data-testid="field-origin-text"
            maxlength="255"
            placeholder="例如：东海舟山渔场 / 进口报关单所载产地"
            :aria-invalid="Boolean(fieldErrors.originText)"
          />
          <small v-if="fieldErrors.originText" class="field-error" data-testid="error-originText">{{ fieldErrors.originText }}</small>
        </label>

        <label class="form-field">
          <span>生产日期（可选）</span>
          <input v-model="form.productionDate" type="date" data-testid="field-production-date" />
        </label>
        <label class="form-field">
          <span>捕捞 / 采收日期（可选）</span>
          <input v-model="form.captureDate" type="date" data-testid="field-capture-date" />
        </label>
        <label class="form-field">
          <span>速冻日期（可选）</span>
          <input v-model="form.freezeDate" type="date" data-testid="field-freeze-date" />
        </label>
        <label class="form-field">
          <span>保质期天数（可选）</span>
          <input v-model="form.shelfLifeDays" inputmode="numeric" data-testid="field-shelf-life-days" placeholder="例如 365" />
          <small v-if="fieldErrors.shelfLifeDays" class="field-error">{{ fieldErrors.shelfLifeDays }}</small>
        </label>
      </div>

      <p v-if="submitError" class="ent-state error submit-error" role="alert" data-testid="source-batch-error">{{ submitError }}</p>

      <div class="form-actions">
        <button
          type="submit"
          class="ent-button"
          data-testid="save-draft"
          :disabled="submitting !== null || productState !== 'loaded'"
        >
          {{ submitting === 'draft' ? '保存中…' : '保存草稿' }}
        </button>
        <button
          type="button"
          class="ent-button primary"
          data-testid="save-and-activate"
          :disabled="submitting !== null || productState !== 'loaded'"
          @click="save(true)"
        >
          {{ submitting === 'activate' ? '保存并激活中…' : '保存并激活' }}
        </button>
      </div>
      <p class="form-note">激活后系统会在同一事务中自动生成唯一的 SOURCE 追溯事件；草稿可在批次详情页再提交激活。</p>
    </form>
  </div>
</template>

<style scoped>
.back-link {
  margin-bottom: 14px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px 18px;
}
.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.form-field.wide {
  grid-column: 1 / -1;
}
.form-field em {
  color: var(--color-danger-text);
  font-style: normal;
}
.form-field input,
.form-field select {
  min-height: 36px;
  padding: 4px 8px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  background-color: #ffffff;
  color: var(--color-text-body);
  font-size: 14px;
}
.form-field [aria-invalid='true'] {
  border-color: var(--color-danger-border);
}
.quantity-input {
  display: flex;
  align-items: center;
  gap: 8px;
}
.quantity-input input {
  flex: 1;
}
.unit {
  font-weight: 600;
  color: var(--color-text-body);
}
.field-state {
  font-size: 13px;
  padding: 8px 0;
}
.field-state.error {
  color: var(--color-danger-text);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.field-error {
  color: var(--color-danger-text);
}
.submit-error {
  margin: 16px 0 0;
  padding: 12px;
  text-align: left;
}
.form-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 18px;
}
.ent-button.primary {
  background-color: var(--color-ocean);
  border-color: var(--color-ocean);
  color: #ffffff;
}
.ent-button.primary:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
  color: #ffffff;
}
.form-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
