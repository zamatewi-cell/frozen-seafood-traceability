<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'
import type { TimelineItem } from '@/types/trace'
import { formatIsoDateTime } from '@/utils/formatters'

defineProps<{
  timeline: TimelineItem[]
}>()

function isSimulatedSource(label: string): boolean {
  return label.includes('SIMULATED') || label.includes('模拟')
}

function isDeviceSource(label: string): boolean {
  return label.includes('DEVICE')
}
</script>

<template>
  <section class="consumer-card timeline-card" aria-label="供应链流通履历">
    <h2 class="consumer-card-title">
      <AppIcons name="clock" size="18" color="#0284c7" />
      <span>供应链履历时间线</span>
      <span class="timeline-count">（{{ timeline.length }} 节点）</span>
    </h2>

    <div v-if="timeline.length === 0" class="empty-timeline">
      <AppIcons name="info" size="24" color="#94a3b8" />
      <p class="empty-text">该批次当前尚未记录生效流转事件。</p>
      <span class="empty-subtext">后续供应链节点记录录入并审核生效后将在此展示。</span>
    </div>

    <ol v-else class="timeline-list">
      <li v-for="(item, idx) in timeline" :key="idx" class="timeline-item">
        <div class="timeline-marker">
          <span class="marker-number">{{ idx + 1 }}</span>
          <span v-if="idx < timeline.length - 1" class="marker-line" />
        </div>
        <div class="timeline-body">
          <div class="item-header">
            <strong class="item-event">{{ item.event }}</strong>
            <time class="item-time mono">{{ formatIsoDateTime(item.occurredAt) }}</time>
          </div>
          <div class="item-source-row">
            <span class="source-label-tag" :class="{ 'simulated-tag': isSimulatedSource(item.dataSourceLabel) }">
              {{ item.dataSourceLabel }}
            </span>
            <span v-if="isSimulatedSource(item.dataSourceLabel)" class="truth-pill">
              仿真推演
            </span>
            <span v-else-if="isDeviceSource(item.dataSourceLabel)" class="truth-pill device-pill">
              预留标识
            </span>
          </div>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.timeline-count {
  font-size: 12px;
  font-weight: 400;
  color: var(--color-text-muted);
}
.empty-timeline {
  padding: 24px 16px;
  text-align: center;
  background-color: #f8fafc;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
}
.empty-text {
  margin: 8px 0 4px;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-body);
}
.empty-subtext {
  font-size: 11px;
  color: var(--color-text-light);
}
.timeline-list {
  list-style: none;
  margin: 0;
  padding: 4px 0 0;
}
.timeline-item {
  display: flex;
  gap: 12px;
  position: relative;
  min-height: 56px;
}
.timeline-marker {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 24px;
  flex-shrink: 0;
}
.marker-number {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background-color: var(--color-ocean-subtle);
  border: 1.5px solid var(--color-ocean);
  color: var(--color-ocean-hover);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  z-index: 1;
}
.marker-line {
  flex: 1;
  width: 2px;
  background-color: #e2e8f0;
  margin: 4px 0;
}
.timeline-body {
  flex: 1;
  padding-bottom: 16px;
}
.item-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.item-event {
  font-size: 14px;
  color: var(--color-text-title);
  font-weight: 600;
}
.item-time {
  font-size: 12px;
  color: var(--color-text-muted);
}
.item-source-row {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.source-label-tag {
  font-size: 11px;
  color: var(--color-text-muted);
  background-color: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
}
.source-label-tag.simulated-tag {
  background-color: #fef3c7;
  color: #92400e;
  border: 1px solid #fde68a;
}
.truth-pill {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  background-color: #fee2e2;
  color: #991b1b;
  border: 1px solid #fecaca;
  font-weight: 600;
}
.device-pill {
  background-color: #e0f2fe;
  color: #0369a1;
  border-color: #bae6fd;
}
</style>
