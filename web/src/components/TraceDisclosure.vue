<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'
import { formatIsoDateTime } from '@/utils/formatters'

defineProps<{
  queriedAt: string
  disclosure: string
}>()
</script>

<template>
  <aside class="consumer-card trace-disclosure-card" aria-label="查询时间与信息披露声明">
    <div class="disclosure-time">
      <AppIcons name="clock" size="15" color="#0369a1" />
      <span>查询时间：<time :datetime="queriedAt">{{ formatIsoDateTime(queriedAt) }}</time></span>
    </div>
    <p class="disclosure-text">{{ disclosure }}</p>
  </aside>
</template>

<style scoped>
.trace-disclosure-card {
  background: var(--color-ocean-subtle);
  border-color: var(--color-ocean-border);
}

.disclosure-time {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-ocean-hover);
  font-size: 12px;
  font-weight: 600;
}

.disclosure-text {
  margin: 8px 0 0;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 1.6;
}
</style>
