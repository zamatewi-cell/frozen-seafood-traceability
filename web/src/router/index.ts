import { createRouter, createWebHistory } from 'vue-router'
import ConsumerTraceView from '@/views/ConsumerTraceView.vue'
import LoginView from '@/views/LoginView.vue'
import OrdersView from '@/views/OrdersView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/trace'
    },
    {
      path: '/trace',
      name: 'TraceEntry',
      component: ConsumerTraceView
    },
    {
      path: '/trace/:publicTraceId',
      name: 'TraceDetail',
      component: ConsumerTraceView,
      props: true
    },
    {
      path: '/login',
      name: 'Login',
      component: LoginView
    },
    {
      path: '/orders',
      name: 'Orders',
      component: OrdersView
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/trace'
    }
  ]
})

export default router
