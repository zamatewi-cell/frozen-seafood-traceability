<script setup lang="ts">
import { computed } from 'vue'
import AppIcons from '@/components/icons/AppIcons.vue'
import type { LineageProjection } from '@/types/trace'
import { formatIsoDateTime, formatOperationType } from '@/utils/formatters'
import { lineageRows } from '@/utils/lineage'

const props = defineProps<{
  lineage: LineageProjection
}>()

const rows = computed(() => lineageRows(props.lineage))
</script>

<template>
  <section class="consumer-card lineage-card" aria-label="批次上游谱系" data-testid="trace-lineage">
    <h2 class="consumer-card-title">
      <AppIcons name="package" size="18" color="#0284c7" />
      <span>批次上游谱系</span>
    </h2>
    <p class="lineage-note">
      展示本批次及其上游来源批次之间已登记的加工、拆分等物料转换关系；同源拆分出的其他批次不在此展示。
    </p>

    <ol class="lineage-rows">
      <li v-for="row in rows" :key="row.generation" class="lineage-row">
        <div v-if="row.incoming.length > 0" class="lineage-edges">
          <span
            v-for="edge in row.incoming"
            :key="`${edge.operationType}-${edge.occurredAt}`"
            class="lineage-edge"
            data-testid="lineage-edge"
            :data-operation-type="edge.operationType"
          >
            ↓ {{ formatOperationType(edge.operationType) }} · <time :datetime="edge.occurredAt">{{ formatIsoDateTime(edge.occurredAt) }}</time>
          </span>
        </div>
        <div class="lineage-nodes">
          <div
            v-for="node in row.nodes"
            :key="node.nodeKey"
            class="lineage-node"
            :class="{ 'is-target': node.role === 'TARGET' }"
            data-testid="lineage-node"
            :data-role="node.role"
            :data-node-key="node.nodeKey"
          >
            <strong class="lineage-node-label">{{ node.label }}</strong>
            <span class="lineage-node-product">{{ node.productName }}</span>
          </div>
        </div>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.lineage-note {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.5;
}
.lineage-rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}
.lineage-edges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  margin-bottom: 6px;
}
.lineage-edge {
  font-size: 12px;
  color: var(--color-ocean-hover);
}
.lineage-nodes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}
.lineage-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 120px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: #f8fafc;
}
.lineage-node.is-target {
  border-color: var(--color-ocean);
  background-color: var(--color-ocean-subtle);
}
.lineage-node-label {
  font-size: 13px;
  color: var(--color-text-title);
}
.lineage-node-product {
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
