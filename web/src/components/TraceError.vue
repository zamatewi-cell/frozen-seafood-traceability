<script setup lang="ts">
import AppIcons from '@/components/icons/AppIcons.vue'

defineProps<{
  title?: string
  message: string
  requestId?: string
}>()

const emit = defineEmits<{
  (e: 'retry'): void
}>()
</script>

<template>
  <div class="error-panel-card" role="alert">
    <div class="error-icon-box">
      <AppIcons name="alert-triangle" size="32" color="#b91c1c" />
    </div>

    <h2 class="error-title">{{ title || '查询服务异常' }}</h2>
    <p class="error-message">{{ message }}</p>

    <div v-if="requestId" class="safe-request-id-box">
      <span class="req-label">排查追踪标识 (Request ID):</span>
      <code class="mono req-code">{{ requestId }}</code>
    </div>

    <div class="error-actions">
      <button type="button" class="btn-retry" @click="emit('retry')">
        <AppIcons name="refresh" size="16" />
        <span>重新尝试查询</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.error-panel-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-danger-border);
  border-radius: var(--radius-md);
  padding: 28px 20px;
  text-align: center;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}
.error-icon-box {
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  border-radius: 50%;
  background-color: var(--color-danger-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}
.error-title {
  margin: 0 0 8px;
  font-size: 17px;
  font-weight: 700;
  color: var(--color-danger-text);
}
.error-message {
  margin: 0 auto 14px;
  max-width: 440px;
  font-size: 13px;
  color: var(--color-text-body);
  line-height: 1.5;
}
.safe-request-id-box {
  display: inline-block;
  padding: 6px 12px;
  background-color: #f8fafc;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--color-text-muted);
  margin-bottom: 18px;
}
.req-label {
  margin-right: 6px;
}
.req-code {
  color: var(--color-text-title);
  font-weight: 600;
}
.error-actions {
  display: flex;
  justify-content: center;
}
.btn-retry {
  min-height: 44px;
  padding: 0 18px;
  background-color: #ffffff;
  border: 1px solid var(--color-border-dark);
  color: var(--color-text-title);
  border-radius: var(--radius-sm);
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-retry:hover {
  border-color: var(--color-ocean);
  color: var(--color-ocean);
}
</style>
