<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'
import type { BatchProjection, ProductProjection } from '@/types/trace'
import { formatDate, formatOriginType, formatProductCategory } from '@/utils/formatters'

defineProps<{
  product: ProductProjection
  batch: BatchProjection
}>()
</script>

<template>
  <section class="consumer-card product-card" aria-label="产品与产地属性">
    <h2 class="consumer-card-title">
      <AppIcons name="package" size="18" color="#0284c7" />
      <span>产品与溯源产地</span>
    </h2>

    <dl class="attribute-list">
      <div class="attr-row">
        <dt class="attr-name">产品品名</dt>
        <dd class="attr-val">{{ product.name }}</dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">水产大类</dt>
        <dd class="attr-val">{{ formatProductCategory(product.category) }}</dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">规格描述</dt>
        <dd class="attr-val">{{ product.specification }}</dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">来源类型</dt>
        <dd class="attr-val">
          <span class="tag-origin">{{ formatOriginType(batch.originType) }}</span>
        </dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">溯源产地/海域</dt>
        <dd class="attr-val">
          <span class="masked-origin-text">{{ batch.maskedOrigin }}</span>
          <span class="desensitize-hint">（已脱敏）</span>
        </dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">生产加工日期</dt>
        <dd class="attr-val mono">{{ formatDate(batch.productionDate) }}</dd>
      </div>

      <div class="attr-row">
        <dt class="attr-name">公开批次编号</dt>
        <dd class="attr-val mono">{{ batch.publicBatchNo }}</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.attribute-list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
}
.attr-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  border-bottom: 1px dashed #f1f5f9;
  padding-bottom: 8px;
}
.attr-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}
.attr-name {
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.attr-val {
  margin: 0;
  color: var(--color-text-title);
  text-align: right;
  font-weight: 500;
}
.tag-origin {
  padding: 2px 8px;
  background-color: var(--color-ocean-subtle);
  color: var(--color-ocean-hover);
  border-radius: 4px;
  font-size: 12px;
  border: 1px solid var(--color-ocean-border);
}
.desensitize-hint {
  font-size: 11px;
  color: var(--color-text-light);
  margin-left: 4px;
}
</style>
