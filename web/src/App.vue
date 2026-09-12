<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppIcons from '@/components/icons/AppIcons.vue'
</script>

<template>
  <div class="app-layout">
    <header class="consumer-nav-header" role="banner">
      <div class="header-inner">
        <div class="brand-row">
          <AppIcons name="snowflake" size="24" color="#38bdf8" />
          <div class="brand-text">
            <span class="brand-title">冷冻海产品溯源查询</span>
            <span class="brand-subtitle">国家冷链可追溯体系教学实训工程</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-content-canvas" role="main">
      <RouterView />
    </main>

    <footer class="consumer-footer" role="contentinfo">
      <div class="footer-inner">
        <div class="footer-icon">
          <AppIcons name="shield-check" size="20" color="#64748b" />
        </div>
        <p class="footer-disclosure">
          本溯源信息仅反映供应链各节点企业申报登记的电子履历，不作为货物物理真实性或防伪验证凭证；系统相关模拟标识仅用于教学实训推演。
        </p>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.consumer-nav-header {
  background-color: var(--color-navy);
  color: #ffffff;
  padding: 14px 20px;
  box-shadow: var(--shadow-md);
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  max-width: 980px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-text {
  display: flex;
  flex-direction: column;
}
.brand-title {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.brand-subtitle {
  font-size: 10px;
  color: #bae6fd;
}
.main-content-canvas {
  flex: 1;
  padding: 20px 16px;
}
.consumer-footer {
  background-color: #ffffff;
  border-top: 1px solid var(--color-border);
  padding: 16px 20px 24px;
}
.footer-inner {
  max-width: 980px;
  margin: 0 auto;
  display: flex;
  align-items: flex-start;
  gap: 10px;
}
.footer-disclosure {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--color-text-muted);
}
@media (max-width: 480px) {
  .consumer-nav-header { padding: 12px 14px; }
  .brand-title { font-size: 15px; }
  .main-content-canvas { padding: 12px 10px; }
}
</style>
