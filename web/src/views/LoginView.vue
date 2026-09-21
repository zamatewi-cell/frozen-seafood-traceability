<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/api/client'
import { login } from '@/stores/session'
import { safeRedirectTarget } from '@/router/guards'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const canSubmit = computed(() => username.value.trim().length > 0 && password.value.length > 0 && !submitting.value)

function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.status === 401) return '用户名或密码错误，或账户当前不可用'
    if (err.status === 400) {
      const first = err.fieldErrors[0]
      return first ? first.message : err.message
    }
    if (err.status === 0) return '无法连接服务器，请检查网络后重试'
    if (err.status === 408) return '登录请求超时，请稍后重试'
    if (err.status === 403) return '安全校验未通过，请刷新页面后重试'
    return err.requestId ? `登录服务暂时不可用（请求编号 ${err.requestId}）` : '登录服务暂时不可用，请稍后重试'
  }
  return '登录失败，请稍后重试'
}

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await login(username.value.trim(), password.value)
    password.value = ''
    await router.replace(safeRedirectTarget(route.query.redirect))
  } catch (err: unknown) {
    password.value = ''
    errorMessage.value = describeError(err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <section class="login-card" aria-labelledby="login-title">
      <h1 id="login-title" class="login-title">企业用户登录</h1>
      <p class="login-subtitle">使用平台分配的企业账号登录，查看本组织负责的追溯批次。</p>

      <form class="login-form" novalidate @submit.prevent="handleSubmit">
        <label class="field">
          <span class="field-label">用户名</span>
          <input
            id="login-username"
            v-model="username"
            name="username"
            type="text"
            autocomplete="username"
            maxlength="64"
            required
            :disabled="submitting"
          />
        </label>

        <label class="field">
          <span class="field-label">密码</span>
          <input
            id="login-password"
            v-model="password"
            name="password"
            type="password"
            autocomplete="current-password"
            maxlength="128"
            required
            :disabled="submitting"
          />
        </label>

        <p v-if="errorMessage" class="login-error" role="alert">{{ errorMessage }}</p>

        <button type="submit" class="primary-button" :disabled="!canSubmit">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="login-footnote">
        消费者无需登录，可直接
        <RouterLink to="/trace">查询公开追溯码</RouterLink>。
      </p>
    </section>
  </div>
</template>

<style scoped>
.login-container {
  max-width: 420px;
  margin: 24px auto;
}
.login-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  padding: 28px 24px;
}
.login-title {
  margin: 0 0 6px;
  font-size: 20px;
  color: var(--color-text-title);
}
.login-subtitle {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-body);
}
.field input {
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  background-color: #ffffff;
}
.login-error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--color-danger-border);
  background-color: var(--color-danger-bg);
  color: var(--color-danger-text);
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.primary-button {
  min-height: 44px;
  border: none;
  border-radius: var(--radius-sm);
  background-color: var(--color-ocean);
  color: #ffffff;
  font-weight: 600;
}
.primary-button:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
}
.primary-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.login-footnote {
  margin: 18px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
