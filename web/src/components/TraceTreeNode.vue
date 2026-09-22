<script setup lang="ts">
import { ref, inject, watch } from 'vue'
import TraceTimeline from '@/components/TraceTimeline.vue'
import type { TraceTreeNode } from '@/types/trace'

const props = defineProps<{
  node: TraceTreeNode
  depth: number
}>()

// 根层默认展开,深层默认折叠
const expanded = ref(props.depth === 0)

// 注入根容器的展开/折叠信号
const expandSignal = inject('treeExpandSignal', ref(0))
const collapseSignal = inject('treeCollapseSignal', ref(0))
watch(expandSignal, () => { expanded.value = true })
watch(collapseSignal, () => { expanded.value = false })

function toggle() {
  expanded.value = !expanded.value
}

const stageMeta: Record<string, { label: string; color: string; soft: string; icon: string }> = {
  SOURCE: { label: '捕捞船队', color: '#0284c7', soft: '#e0f2fe', icon: '捕' },
  PROCESSING: { label: '加工厂', color: '#7c3aed', soft: '#f3e8ff', icon: '工' },
  DISTRIBUTION: { label: '分拣批发', color: '#d97706', soft: '#fef3c7', icon: '装' },
  RETAIL: { label: '零售终端', color: '#059669', soft: '#d1fae5', icon: '售' },
  UNKNOWN: { label: '未知', color: '#64748b', soft: '#f1f5f9', icon: '?' }
}
function stageOf(stage: string) {
  return stageMeta[stage] || stageMeta.UNKNOWN
}
const meta = stageOf(props.node.stage)
const hasChildren = props.node.children && props.node.children.length > 0
const hasTimeline = props.node.timeline && props.node.timeline.length > 0
</script>

<template>
  <div class="tree-node" :style="{ '--branch-color': meta.color }">
    <!-- 节点卡片(点击展开/折叠) -->
    <div
      class="node-card"
      :class="{ expanded }"
      :style="{ '--sc': meta.color, '--sb': meta.soft }"
      @click="toggle"
    >
      <div class="node-head">
        <span class="stage-icon">{{ meta.icon }}</span>
        <span class="stage-label">{{ meta.label }}</span>
        <span class="node-org">{{ node.orgName }}</span>
        <span v-if="node.allocatedQuantity" class="node-qty">{{ node.allocatedQuantity }}kg</span>
        <span v-if="hasChildren" class="branch-count">{{ node.children!.length }}支下游</span>
        <span class="chevron" :class="{ rotated: expanded }">›</span>
      </div>
      <div v-if="node.batch" class="node-batch">
        <span class="batch-no mono">{{ node.batch.publicBatchNo }}</span>
        <span v-if="node.batch.maskedOrigin" class="batch-sep">·</span>
        <span v-if="node.batch.maskedOrigin" class="batch-origin">{{ node.batch.maskedOrigin }}</span>
        <span v-if="node.batch.productionDate" class="batch-sep">·</span>
        <span v-if="node.batch.productionDate" class="batch-date">{{ node.batch.productionDate }}</span>
      </div>
    </div>

    <!-- 展开内容:时间线 + 子分支 -->
    <!-- 时间线(展开时才渲染) -->
    <div v-if="expanded" class="timeline-wrap">
      <div v-if="hasTimeline">
        <TraceTimeline :timeline="node.timeline!" />
      </div>
      <div v-else class="no-events">暂无溯源事件记录</div>
    </div>

    <!-- 递归子节点(始终在DOM中,折叠时隐藏,确保"展开全部"能递归生效) -->
    <div v-if="hasChildren" v-show="expanded" class="children">
      <TraceTreeNode
        v-for="(child, i) in node.children"
        :key="i"
        :node="child"
        :depth="depth + 1"
      />
    </div>
  </div>
</template>

<style scoped>
.tree-node {
  position: relative;
}

/* ========== 节点卡片 ========== */
.node-card {
  background: var(--color-card, #fff);
  border: 1px solid var(--color-border, #e2e8f0);
  border-left: 4px solid var(--sc);
  border-radius: 10px;
  padding: 0 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
  cursor: pointer;
  transition: box-shadow 0.15s, border-color 0.15s;
  user-select: none;
}
.node-card:hover {
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08);
}
.node-card.expanded {
  border-bottom-left-radius: 4px;
  border-bottom-right-radius: 4px;
}
.node-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0 6px;
  flex-wrap: wrap;
}
.stage-icon {
  flex: none;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--sc);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--sc) 40%, transparent);
}
.stage-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--sc);
  background: var(--sb);
  padding: 2px 8px;
  border-radius: 4px;
  white-space: nowrap;
}
.node-org {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-title, #0f172a);
}
.node-qty {
  font-size: 12px;
  font-weight: 600;
  color: var(--sc);
  background: var(--sb);
  padding: 2px 9px;
  border-radius: 10px;
  white-space: nowrap;
}
.branch-count {
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
  background: #f1f5f9;
  padding: 2px 8px;
  border-radius: 4px;
  white-space: nowrap;
}
.chevron {
  margin-left: auto;
  font-size: 20px;
  font-weight: 700;
  color: var(--color-text-muted, #94a3b8);
  transition: transform 0.2s ease;
  line-height: 1;
}
.chevron.rotated {
  transform: rotate(90deg);
}
.node-batch {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--color-text-muted, #64748b);
  padding: 0 0 10px;
}
.batch-no {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--color-text-body, #334155);
  font-weight: 600;
}
.batch-sep {
  color: var(--color-text-light, #cbd5e1);
}
.batch-origin {
  color: var(--color-text-body, #475569);
}
.batch-date {
  color: var(--color-text-muted, #64748b);
}

/* ========== 展开内容 ========== */
.timeline-wrap {
  padding: 4px 0 10px 14px;
}
.timeline-wrap :deep(.timeline-card) {
  box-shadow: none;
  border: none;
  padding: 0;
}
.timeline-wrap :deep(.consumer-card-title) {
  font-size: 13px;
  padding: 4px 0 6px;
}
.no-events {
  font-size: 12px;
  color: var(--color-text-light, #94a3b8);
  padding: 6px 0 6px 14px;
  font-style: italic;
}

/* ========== 子节点(上游分支) ========== */
.children {
  display: flex;
  flex-direction: column;
  padding-left: 20px;
  position: relative;
  margin-top: 12px;
}
/* 主干垂直线 */
.children::before {
  content: '';
  position: absolute;
  left: 4px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--branch-color, #cbd5e1);
  opacity: 0.4;
  border-radius: 2px;
}
/* 每个子节点 */
.children > .tree-node {
  position: relative;
  padding-top: 12px;
}
/* 水平连接线 */
.children > .tree-node::before {
  content: '';
  position: absolute;
  left: -16px;
  top: 26px;
  width: 16px;
  height: 2px;
  background: var(--branch-color, #cbd5e1);
  opacity: 0.4;
  border-radius: 2px;
}
/* 垂直连接段(贯穿到下一个兄弟) */
.children > .tree-node::after {
  content: '';
  position: absolute;
  left: -16px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--branch-color, #cbd5e1);
  opacity: 0.4;
  border-radius: 2px;
}
/* 最后一个子:垂直线只到水平线位置 */
.children > .tree-node:last-child::after {
  bottom: auto;
  height: 26px;
}
</style>
