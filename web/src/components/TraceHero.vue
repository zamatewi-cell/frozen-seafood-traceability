<script setup lang="ts">
import { computed } from 'vue'
import AppIcons from '@/components/icons/AppIcons.vue'
import type { PublicTrace } from '@/types/trace'
import { formatFlowStatus, formatPublicRiskStatus, formatPublicTraceStatus } from '@/utils/formatters'

const props = defineProps<{
  trace: PublicTrace
}>()

const statusInfo = computed(() => formatPublicTraceStatus(props.trace.flowStatus, props.trace.riskStatus))
const flowInfo = computed(() => formatFlowStatus(props.trace.flowStatus))
const riskInfo = computed(() => formatPublicRiskStatus(props.trace.riskStatus))
</script>

<template>
  <section class="consumer-hero-card" aria-label="批次概述与状态">
    <div class="hero-top-row">
      <div class="status-badge" :class="statusInfo.tone" role="status" data-testid="public-status-badge">
        <AppIcons v-if="statusInfo.tone === 'success'" name="check-circle" size="14" />
        <AppIcons v-else-if="statusInfo.tone === 'danger'" name="alert-triangle" size="14" />
        <AppIcons v-else-if="statusInfo.tone === 'warning'" name="alert-triangle" size="14" />
        <AppIcons v-else name="info" size="14" />
        <span>{{ statusInfo.label }}</span>
      </div>
      <span class="status-note" data-testid="public-status-note">{{ statusInfo.description }}</span>
    </div>

    <p class="dual-status-row" aria-label="批次双维状态">
      <span>流转状态：<strong data-testid="public-flow-status">{{ flowInfo.label }}</strong></span>
      <span class="divider">·</span>
      <span>风险状态：<strong data-testid="public-risk-status">{{ riskInfo.label }}</strong></span>
    </p>

    <h1 class="product-title">{{ trace.product.name }}</h1>

    <div class="product-meta">
      <span class="spec-item">规格：{{ trace.product.specification }}</span>
      <span class="divider">·</span>
      <span class="batch-item">
        公开批次号：<strong class="mono">{{ trace.batch.publicBatchNo }}</strong>
      </span>
    </div>

    <div class="trace-id-badge">
      <span class="trace-id-label">公开追溯码：</span>
      <code class="trace-id-code mono" data-testid="public-trace-id">{{ trace.publicTraceId }}</code>
    </div>
  </section>
</template>

<style scoped>
.consumer-hero-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 20px;
  margin-bottom: 14px;
  box-shadow: var(--shadow-sm);
}
.hero-top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.dual-status-row {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--color-text-muted);
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dual-status-row strong {
  color: var(--color-text-body);
}
.status-note {
  font-size: 11px;
  color: var(--color-text-muted);
}
.product-title {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 700;
  color: var(--color-text-title);
  line-height: 1.3;
}
.product-meta {
  font-size: 13px;
  color: var(--color-text-body);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.divider {
  color: var(--color-border-dark);
}
.trace-id-badge {
  margin-top: 14px;
  padding: 8px 12px;
  background-color: #f8fafc;
  border: 1px dashed var(--color-border-dark);
  border-radius: var(--radius-sm);
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}
.trace-id-label {
  color: var(--color-text-muted);
}
.trace-id-code {
  color: var(--color-ocean-hover);
  font-weight: 600;
  letter-spacing: 0.05em;
}
@media (max-width: 480px) {
  .consumer-hero-card { padding: 16px; }
  .product-title { font-size: 19px; }
}
</style>
