<script setup lang="ts">
import { ref, watch } from 'vue'
import AppIcons from '@/components/icons/AppIcons.vue'
import { normalizePublicTraceId } from '@/utils/validation'

const props = defineProps<{
  initialValue?: string
  loading?: boolean
}>()

const emit = defineEmits<{
  (e: 'search', code: string): void
}>()

const inputCode = ref(props.initialValue ? normalizePublicTraceId(props.initialValue) : '')

watch(
  () => props.initialValue,
  (val) => {
    inputCode.value = val ? normalizePublicTraceId(val) : ''
  }
)

function onInput(e: Event) {
  const target = e.target as HTMLInputElement
  inputCode.value = target.value.toUpperCase().replace(/[^A-Z2-7]/g, '')
}

function handleSubmit() {
  const trimmed = inputCode.value.trim()
  if (trimmed) {
    emit('search', trimmed)
  }
}

function handleClear() {
  inputCode.value = ''
}
</script>

<template>
  <form class="search-form-card" role="search" aria-label="追溯码查询表单" @submit.prevent="handleSubmit">
    <div class="search-input-wrapper">
      <label for="trace-code-input" class="search-label">
        <AppIcons name="search" size="18" color="#0284c7" />
        <span>输入商品包装上的26位公开追溯码</span>
      </label>
      <div class="search-bar">
        <input
          id="trace-code-input"
          v-model="inputCode"
          type="text"
          class="search-input mono"
          placeholder="例如：WVKJ5Y2C4P4Q6T7XZ2M7K3B2AC"
          maxlength="26"
          autocomplete="off"
          autocorrect="off"
          autocapitalize="characters"
          spellcheck="false"
          :disabled="loading"
          @input="onInput"
        />
        <button
          v-if="inputCode"
          type="button"
          class="clear-button"
          aria-label="清空输入内容"
          :disabled="loading"
          @click="handleClear"
        >
          <AppIcons name="x" size="16" />
        </button>
        <button
          type="submit"
          class="submit-button"
          :disabled="loading || inputCode.length === 0"
          aria-label="执行追溯查询"
        >
          <AppIcons v-if="loading" name="refresh" size="16" class="spin" />
          <AppIcons v-else name="search" size="16" />
          <span>{{ loading ? '查询中...' : '开始查验' }}</span>
        </button>
      </div>
      <div class="search-hint">
        <span>支持大写英文字母与数字 2-7（RFC 4648 Base32 标准编码）</span>
        <span class="char-count" :class="{ valid: inputCode.length === 26 }">
          {{ inputCode.length }}/26 字符
        </span>
      </div>
    </div>
  </form>
</template>

<style scoped>
.search-form-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: 18px;
  margin-bottom: 16px;
}
.search-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-title);
  margin-bottom: 8px;
}
.search-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  position: relative;
}
.search-input {
  flex: 1;
  height: 44px;
  padding: 0 36px 0 12px;
  border: 1.5px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--color-text-title);
  background-color: #ffffff;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.search-input:focus {
  border-color: var(--color-ocean);
  box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.15);
  outline: none;
}
.clear-button {
  position: absolute;
  right: 116px;
  background: none;
  border: none;
  color: var(--color-text-light);
  width: 44px;
  height: 44px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.clear-button:hover {
  color: var(--color-text-title);
  background-color: #f1f5f9;
}
.submit-button {
  height: 44px;
  padding: 0 20px;
  background-color: var(--color-ocean);
  color: #ffffff;
  border: none;
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
  transition: background-color 0.15s ease;
}
.submit-button:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
}
.submit-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.search-hint {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  font-size: 11px;
  color: var(--color-text-muted);
}
.char-count.valid {
  color: var(--color-success-text);
  font-weight: 600;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
@media (max-width: 480px) {
  .search-form-card { padding: 14px; }
  .search-bar { flex-direction: column; align-items: stretch; }
  .clear-button { right: 0; top: 0; }
  .submit-button { width: 100%; justify-content: center; }
}
</style>
