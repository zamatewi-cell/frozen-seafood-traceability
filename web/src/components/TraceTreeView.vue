<script setup lang="ts">
import { ref, provide } from 'vue'
import TraceTreeNode from '@/components/TraceTreeNode.vue'
import type { TraceTreeNode as TraceTreeNodeType } from '@/types/trace'

defineOptions({ name: 'TraceTreeView' })

const props = defineProps<{
  nodes: TraceTreeNodeType[]
}>()

// 展开/折叠全部信号:递增整数触发 watch
const expandSignal = ref(0)
const collapseSignal = ref(0)
provide('treeExpandSignal', expandSignal)
provide('treeCollapseSignal', collapseSignal)

function expandAll() {
  expandSignal.value++
}
function collapseAll() {
  collapseSignal.value++
}
</script>

<template>
  <div class="trace-tree-container">
    <!-- 顶部:流程方向图例 + 操作按钮 -->
    <div class="tree-toolbar">
      <div class="legend-flow">
        <span class="legend-title">溯源方向</span>
        <span class="chip" style="--c:#059669">零售终端</span>
        <span class="arrow">↓</span>
        <span class="chip" style="--c:#d97706">分拣批发</span>
        <span class="arrow">↓</span>
        <span class="chip" style="--c:#7c3aed">加工厂</span>
        <span class="arrow">↓</span>
        <span class="chip" style="--c:#0284c7">捕捞船队</span>
      </div>
      <div class="toolbar-acts">
        <button class="tool-btn" type="button" @click="expandAll">展开全部</button>
        <button class="tool-btn ghost" type="button" @click="collapseAll">折叠全部</button>
      </div>
    </div>
    <p class="tree-hint">从零售终端向下展开,点击节点查看该环节的备注、质检和出入库时间。每个节点的时间线按实际操作时间排列。</p>

    <!-- 根节点列表 -->
    <div class="root-list">
      <TraceTreeNode
        v-for="(node, i) in props.nodes"
        :key="i"
        :node="node"
        :depth="0"
      />
    </div>
  </div>
</template>

<style scoped>
.trace-tree-container {
  width: 100%;
}
.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  margin-bottom: 6px;
  background: linear-gradient(135deg, #f0f9ff 0%, #f5f3ff 100%);
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: 10px;
}
.legend-flow {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}
.legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--color-text-title, #0f172a);
  margin-right: 4px;
  white-space: nowrap;
}
.legend-flow .chip {
  font-size: 11px;
  font-weight: 600;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 12%, #fff);
  border: 1px solid color-mix(in srgb, var(--c) 35%, #fff);
  padding: 1px 7px;
  border-radius: 4px;
}
.legend-flow .arrow {
  font-size: 12px;
  color: var(--color-text-muted, #94a3b8);
  font-weight: 700;
}
.toolbar-acts {
  display: flex;
  gap: 6px;
}
.tool-btn {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 12px;
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: 6px;
  background: #fff;
  color: var(--color-text-body, #334155);
  cursor: pointer;
  transition: all 0.15s;
}
.tool-btn:hover {
  border-color: var(--color-primary, #0284c7);
  color: var(--color-primary, #0284c7);
}
.tool-btn.ghost {
  background: transparent;
}
.tree-hint {
  font-size: 11px;
  color: var(--color-text-muted, #64748b);
  margin: 0 0 14px;
  padding: 0 2px;
  line-height: 1.5;
}
.root-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
