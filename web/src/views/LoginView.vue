<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '@/api/auth'
import { ApiError } from '@/api/client'

const router = useRouter()

const username = ref('')
const password = ref('demo1234')
const loading = ref(false)
const errorMessage = ref('')

const demoAccounts = [
  { username: 'sys_admin', label: '系统管理员' },
  { username: 'capt_ship', label: '捕捞船长（原料供给）' },
  { username: 'plant_op', label: '加工厂操作员' },
  { username: 'whl_op', label: '批发分装开单员' },
  { username: 'market_op', label: '超市采购员' },
  { username: 'shop_op', label: '电商商家运营' }
]

function pickAccount(user: string) {
  username.value = user
}

async function handleLogin() {
  if (!username.value.trim() || !password.value) {
    errorMessage.value = '请输入用户名与密码'
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    await login(username.value.trim(), password.value)
    await router.push('/orders')
  } catch (err) {
    errorMessage.value =
      err instanceof ApiError ? err.message : err instanceof Error ? err.message : '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <h2 class="login-title">企业工作台登录</h2>
      <p class="login-subtitle">选择演示角色并点击「登录」进入多角色订单管理</p>

      <div class="account-picker">
        <button
          v-for="acc in demoAccounts"
          :key="acc.username"
          type="button"
          class="account-chip"
          :class="{ active: username === acc.username }"
          @click="pickAccount(acc.username)"
        >
          <span class="chip-code">{{ acc.username }}</span>
          <span class="chip-label">{{ acc.label }}</span>
        </button>
      </div>

      <form class="login-form" @submit.prevent="handleLogin">
        <label class="field">
          <span class="field-label">用户名</span>
          <input v-model="username" type="text" autocomplete="username" placeholder="例如 market_op" />
        </label>
        <label class="field">
          <span class="field-label">密码</span>
          <input v-model="password" type="password" autocomplete="current-password" />
        </label>

        <p v-if="errorMessage" class="error-text">{{ errorMessage }}</p>

        <div class="form-actions">
          <button type="button" class="link-btn" @click="router.push('/trace')">
            前往消费者溯源
          </button>
          <button type="submit" class="primary-btn" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  max-width: 520px;
  margin: 24px auto 0;
}
.login-card {
  background-color: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  padding: 24px;
}
.login-title {
  margin: 0 0 4px;
  font-size: 20px;
  color: var(--color-text-title);
}
.login-subtitle {
  margin: 0 0 18px;
  font-size: 13px;
  color: var(--color-text-muted);
}
.account-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
}
.account-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-card);
  text-align: left;
  transition: all 0.15s ease;
}
.account-chip:hover {
  border-color: var(--color-ocean-border);
}
.account-chip.active {
  border-color: var(--color-ocean);
  background-color: var(--color-ocean-subtle);
}
.chip-code {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--color-ocean);
}
.chip-label {
  font-size: 12px;
  color: var(--color-text-muted);
}
.login-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.field-label {
  font-size: 13px;
  color: var(--color-text-body);
}
input {
  padding: 9px 12px;
  border: 1px solid var(--color-border-dark);
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--color-text-title);
  background-color: #fff;
}
input:focus {
  outline: 2px solid var(--color-ocean);
  outline-offset: 1px;
}
.error-text {
  margin: 0;
  font-size: 13px;
  color: var(--color-danger-text);
}
.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 4px;
}
.link-btn {
  background: none;
  border: none;
  color: var(--color-ocean);
  font-size: 13px;
  padding: 4px;
}
.link-btn:hover {
  text-decoration: underline;
}
.primary-btn {
  padding: 9px 22px;
  border: none;
  border-radius: var(--radius-sm);
  background-color: var(--color-ocean);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}
.primary-btn:hover:not(:disabled) {
  background-color: var(--color-ocean-hover);
}
.primary-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>