<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'

import type { PublicRecallDisposition } from '@/types/trace'

defineProps<{
  notice?: string | null
  /** PB6：模拟召回处置进展（受控状态、固定文案与处置完成日期）。 */
  disposition?: PublicRecallDisposition | null
}>()
</script>

<template>
  <div class="recall-alert-card" role="alert" aria-live="assertive">
    <div class="recall-icon-col">
      <AppIcons name="alert-triangle" size="26" color="#b91c1c" />
    </div>
    <div class="recall-content-col">
      <div class="recall-title-row">
        <h2 class="recall-heading">系统模拟召回演练声明</h2>
        <span class="drill-tag">教学演练推演</span>
      </div>
      <p class="recall-text">
        {{ notice || '此批次海产品已启动系统模拟召回演练，流通环节已暂停，请联系销售商或质量管理部门处理（本提示为系统教学演练模拟信息）。' }}
      </p>
      <p v-if="disposition" class="recall-disposition" data-testid="public-recall-disposition" :data-status="disposition.status">
        <strong>{{ disposition.status === 'CLOSED' ? '处置已完成' : '处置进行中' }}：</strong>{{ disposition.label }}
        <span v-if="disposition.closedDate">（{{ disposition.closedDate }}）</span>
      </p>
      <div class="recall-subnote">
        <AppIcons name="info" size="14" color="#b91c1c" />
        <span>注意：本召回提示属于供应链质量演练模拟流程，不替代企业真实法定公告。</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.recall-alert-card {
  display: flex;
  gap: 14px;
  padding: 16px;
  background-color: var(--color-danger-bg);
  border: 1.5px solid var(--color-danger-border);
  border-radius: var(--radius-md);
  margin-bottom: 14px;
  box-shadow: 0 2px 6px rgba(185, 28, 28, 0.08);
}
.recall-icon-col {
  flex-shrink: 0;
  padding-top: 2px;
}
.recall-content-col {
  flex: 1;
}
.recall-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  flex-wrap: wrap;
}
.recall-heading {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-danger-text);
}
.drill-tag {
  font-size: 11px;
  padding: 2px 8px;
  background-color: #fee2e2;
  color: #991b1b;
  border-radius: 999px;
  border: 1px solid #fca5a5;
  font-weight: 600;
}
.recall-text {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.5;
  color: #7f1d1d;
  font-weight: 500;
}
.recall-disposition {
  margin: 0 0 8px;
  font-size: 13px;
  line-height: 1.5;
  color: #7f1d1d;
}
.recall-subnote {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #991b1b;
}
</style>
