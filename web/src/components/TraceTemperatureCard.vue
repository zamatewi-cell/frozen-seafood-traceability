<script setup lang="ts">
import { computed } from 'vue'
import AppIcons from '@/components/icons/AppIcons.vue'
import type { TemperatureSummary } from '@/types/trace'
import { formatTemperatureResult } from '@/utils/formatters'

const props = defineProps<{
  temperatureSummary: TemperatureSummary
}>()

const resultInfo = computed(() => formatTemperatureResult(props.temperatureSummary.result))
</script>

<template>
  <section class="consumer-card temp-summary-card" aria-label="冷链温控摘要">
    <h2 class="consumer-card-title">
      <AppIcons name="thermometer" size="18" color="#0284c7" />
      <span>冷链温控履约摘要</span>
    </h2>

    <div class="temp-result-row">
      <div class="status-badge" :class="resultInfo.tone" role="status">
        <AppIcons v-if="resultInfo.tone === 'success'" name="check-circle" size="14" />
        <AppIcons v-else-if="resultInfo.tone === 'danger'" name="alert-triangle" size="14" />
        <AppIcons v-else name="info" size="14" />
        <span>{{ resultInfo.label }}</span>
      </div>
    </div>

    <p class="rule-note-text">
      {{ temperatureSummary.ruleNote }}
    </p>

    <div class="temp-disclaimer">
      <AppIcons name="info" size="14" color="#0369a1" />
      <span>
        本切片数据如实披露：当前未接入真实 IoT 测温硬件，不伪造温控合规结论或连续曲线。
      </span>
    </div>
  </section>
</template>

<style scoped>
.temp-summary-card {
  background-color: #f8fbff;
  border-color: #bae6fd;
}
.temp-result-row {
  margin-bottom: 10px;
}
.rule-note-text {
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text-body);
  margin: 0 0 12px;
}
.temp-disclaimer {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 11px;
  color: #0369a1;
  padding: 8px 10px;
  background-color: #e0f2fe;
  border-radius: var(--radius-sm);
  line-height: 1.4;
}
</style>
