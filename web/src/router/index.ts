import { createRouter, createWebHistory } from 'vue-router'
import ConsumerTraceView from '@/views/ConsumerTraceView.vue'

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
      path: '/:pathMatch(.*)*',
      redirect: '/trace'
    }
  ]
})

export default router
