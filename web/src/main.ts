import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/client'
import { clearSession } from './stores/session'
import './assets/styles.css'

// 企业接口返回 401（会话过期、账户或组织被停用）时：清理前端会话并回到登录页
setUnauthorizedHandler(() => {
  clearSession()
  const current = router.currentRoute.value
  if (current.matched.some((record) => record.meta.requiresAuth)) {
    router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

const app = createApp(App)
app.use(router)
app.mount('#app')
