<script setup lang="ts">
import type { StatusBadgeInfo } from '@/utils/formatters'

defineProps<{
  info: StatusBadgeInfo
  /** 维度名称，用于无障碍说明，例如“流转”或“风险” */
  dimension: string
}>()
</script>

<template>
  <span
    class="status-badge"
    :class="info.tone"
    :title="info.description"
    :aria-label="`${dimension}状态：${info.label}`"
  >{{ info.label }}</span>
</template>
