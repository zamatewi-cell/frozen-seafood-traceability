<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import AppIcons from '@/components/icons/AppIcons.vue'
import { logout, useSession } from '@/stores/session'

const router = useRouter()
const { user } = useSession()
const isCarrier = computed(() => user.value?.orgType === 'CARRIER')
const loggingOut = ref(false)
const logoutError = ref('')

async function handleLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  logoutError.value = ''
  try {
    await logout()
  } catch {
    // 服务端未确认注销：会话可能仍然有效，留在当前页面并允许重试
    logoutError.value = '退出登录失败，服务端会话可能仍然有效，请检查网络后重试。'
    return
  } finally {
    loggingOut.value = false
  }
  await router.replace('/login')
}
</script>

<template>
  <div class="enterprise-layout">
    <header class="enterprise-header" role="banner">
      <div class="enterprise-header-inner">
        <RouterLink to="/app" class="enterprise-brand">
          <AppIcons name="snowflake" size="22" color="#38bdf8" />
          <span class="enterprise-brand-title">冷冻海产品溯源 · 企业工作台</span>
        </RouterLink>

        <nav class="enterprise-nav" aria-label="企业端导航">
          <RouterLink to="/app" class="nav-link" exact-active-class="nav-link-active">工作台</RouterLink>
          <RouterLink to="/app/batches" class="nav-link" active-class="nav-link-active">批次</RouterLink>
          <RouterLink to="/app/shipments" class="nav-link" active-class="nav-link-active" data-testid="nav-shipments">运输任务</RouterLink>
          <RouterLink v-if="!isCarrier" to="/app/transfers/inbound" class="nav-link" active-class="nav-link-active" data-testid="nav-inbound">待接收交接</RouterLink>
        </nav>

        <div class="enterprise-user" data-testid="session-user">
          <span v-if="user" class="user-text">
            <strong>{{ user.displayName }}</strong>
            <span class="user-org">{{ user.orgName }}</span>
          </span>
          <button type="button" class="logout-button" :disabled="loggingOut" @click="handleLogout">
            {{ loggingOut ? '退出中…' : '退出登录' }}
          </button>
        </div>
      </div>
    </header>

    <p v-if="logoutError" class="logout-error" role="alert" data-testid="logout-error">{{ logoutError }}</p>

    <main class="enterprise-main" role="main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.enterprise-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.enterprise-header {
  background-color: var(--color-navy);
  color: #ffffff;
  box-shadow: var(--shadow-md);
  position: sticky;
  top: 0;
  z-index: 100;
}
.enterprise-header-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}
.enterprise-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #ffffff;
  text-decoration: none;
}
.enterprise-brand-title {
  font-size: 15px;
  font-weight: 700;
}
.enterprise-nav {
  display: flex;
  gap: 4px;
  flex: 1;
}
.nav-link {
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  color: #bae6fd;
  text-decoration: none;
  font-size: 14px;
}
.nav-link:hover,
.nav-link-active {
  background-color: rgba(56, 189, 248, 0.16);
  color: #ffffff;
}
.enterprise-user {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-text {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  line-height: 1.3;
  text-align: right;
}
.user-org {
  color: #bae6fd;
}
.logout-button {
  min-height: 36px;
  padding: 6px 14px;
  border: 1px solid rgba(186, 230, 253, 0.6);
  border-radius: var(--radius-sm);
  background: transparent;
  color: #ffffff;
  font-size: 13px;
}
.logout-button:hover:not(:disabled) {
  background-color: rgba(56, 189, 248, 0.16);
}
.logout-error {
  width: calc(100% - 40px);
  max-width: 1160px;
  margin: 12px auto 0;
  padding: 10px 12px;
  color: var(--color-danger-text);
  background-color: var(--color-danger-bg);
  border: 1px solid var(--color-danger-border);
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.enterprise-main {
  flex: 1;
  width: 100%;
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}
@media (max-width: 640px) {
  .enterprise-header-inner { padding: 10px 14px; gap: 10px; }
  .logout-error {
  width: calc(100% - 40px);
  max-width: 1160px;
  margin: 12px auto 0;
  padding: 10px 12px;
  color: var(--color-danger-text);
  background-color: var(--color-danger-bg);
  border: 1px solid var(--color-danger-border);
  border-radius: var(--radius-sm);
  font-size: 13px;
}
.enterprise-main { padding: 12px 10px; }
  .user-text { text-align: left; }
}
</style>
