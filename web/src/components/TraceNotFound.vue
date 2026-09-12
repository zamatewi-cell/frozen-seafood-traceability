<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'

defineProps<{
  traceId?: string
}>()

const emit = defineEmits<{
  (e: 'retry'): void
}>()
</script>

<template>
  <div class="neutral-not-found-card" role="status" aria-live="polite">
    <div class="icon-circle">
      <AppIcons name="search" size="32" color="#64748b" />
    </div>

    <h2 class="not-found-title">未找到追溯信息</h2>

    <p class="not-found-desc">
      未找到该追溯码或该码已失效，请核对包装上的26位追溯码字符后重新查验。
    </p>

    <div v-if="traceId" class="queried-id-box">
      <span>查验编码：</span>
      <code class="mono">{{ traceId }}</code>
    </div>

    <div class="guidance-box">
      <h3 class="guidance-title">查验指引说明</h3>
      <ul class="guidance-list">
        <li>请确认追溯编码长度恰为 26 位；</li>
        <li>仅包含大写英文字母 A-Z 与数字 2-7（不含数字 0, 1, 8, 9）；</li>
        <li>若商品已过保质期或批次归档停用，追溯码可能已处于失效状态；</li>
        <li>若疑问仍未解决，请联系销售渠道或监管部门进行核验。</li>
      </ul>
    </div>

    <div class="actions-row">
      <button type="button" class="btn-primary" @click="emit('retry')">
        <AppIcons name="arrow-left" size="16" />
        <span>返回重新输入</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.neutral-not-found-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 32px 24px;
  text-align: center;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}
.icon-circle {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background-color: #f1f5f9;
  display: flex;
  align-items: center;
  justify-content: center;
}
.not-found-title {
  margin: 0 0 10px;
  font-size: 18px;
  font-weight: 700;
  color: var(--color-text-title);
}
.not-found-desc {
  margin: 0 auto 16px;
  max-width: 480px;
  font-size: 14px;
  color: var(--color-text-muted);
  line-height: 1.5;
}
.queried-id-box {
  display: inline-block;
  padding: 6px 12px;
  background-color: #f8fafc;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 20px;
}
.guidance-box {
  max-width: 440px;
  margin: 0 auto 24px;
  text-align: left;
  padding: 14px 16px;
  background-color: #f8fafc;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}
.guidance-title {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-title);
}
.guidance-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.6;
}
.actions-row {
  display: flex;
  justify-content: center;
}
.btn-primary {
  min-height: 44px;
  padding: 0 20px;
  background-color: var(--color-ocean);
  color: #ffffff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background-color: var(--color-ocean-hover);
}
</style>
