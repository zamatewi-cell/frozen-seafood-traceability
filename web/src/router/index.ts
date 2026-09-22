import { createRouter, createWebHistory, type RouteRecordRaw, type Router, type RouterHistory } from 'vue-router'
import PublicLayout from '@/layouts/PublicLayout.vue'
import EnterpriseLayout from '@/layouts/EnterpriseLayout.vue'
import ConsumerTraceView from '@/views/ConsumerTraceView.vue'
import LoginView from '@/views/LoginView.vue'
import WorkbenchView from '@/views/enterprise/WorkbenchView.vue'
import BatchListView from '@/views/enterprise/BatchListView.vue'
import BatchDetailView from '@/views/enterprise/BatchDetailView.vue'
import SourceBatchCreateView from '@/views/enterprise/SourceBatchCreateView.vue'
import TransferCreateView from '@/views/enterprise/TransferCreateView.vue'
import ShipmentListView from '@/views/enterprise/ShipmentListView.vue'
import ShipmentCreateView from '@/views/enterprise/ShipmentCreateView.vue'
import ShipmentDetailView from '@/views/enterprise/ShipmentDetailView.vue'
import InboundTransferListView from '@/views/enterprise/InboundTransferListView.vue'
import { installAuthGuard } from './guards'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要已登录的企业会话 */
    requiresAuth?: boolean
    /** 仅限未登录访问（已登录时跳转工作台） */
    guestOnly?: boolean
    title?: string
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: PublicLayout,
    children: [
      {
        path: '',
        redirect: '/trace'
      },
      {
        path: 'trace',
        name: 'TraceEntry',
        component: ConsumerTraceView
      },
      {
        path: 'trace/:publicTraceId',
        name: 'TraceDetail',
        component: ConsumerTraceView,
        props: true
      },
      {
        path: 'login',
        name: 'Login',
        component: LoginView,
        meta: { guestOnly: true, title: '企业登录' }
      }
    ]
  },
  {
    path: '/app',
    component: EnterpriseLayout,
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        name: 'Workbench',
        component: WorkbenchView,
        meta: { title: '工作台' }
      },
      {
        path: 'batches',
        name: 'BatchList',
        component: BatchListView,
        meta: { title: '批次列表' }
      },
      {
        path: 'batches/new',
        name: 'SourceBatchCreate',
        component: SourceBatchCreateView,
        meta: { title: '新建来源批次' }
      },
      {
        path: 'batches/:id',
        name: 'BatchDetail',
        component: BatchDetailView,
        props: true,
        meta: { title: '批次详情' }
      },
      {
        path: 'batches/:id/transfers/new',
        name: 'TransferCreate',
        component: TransferCreateView,
        props: true,
        meta: { title: '发起交接' }
      },
      {
        path: 'shipments',
        name: 'ShipmentList',
        component: ShipmentListView,
        meta: { title: '运输任务' }
      },
      {
        path: 'shipments/new',
        name: 'ShipmentCreate',
        component: ShipmentCreateView,
        meta: { title: '新建运输任务' }
      },
      {
        path: 'shipments/:id',
        name: 'ShipmentDetail',
        component: ShipmentDetailView,
        props: true,
        meta: { title: '运输任务详情' }
      },
      {
        path: 'transfers/inbound',
        name: 'InboundTransfers',
        component: InboundTransferListView,
        meta: { title: '待接收交接' }
      },
      {
        path: ':pathMatch(.*)*',
        redirect: '/app'
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/trace'
  }
]

export function createAppRouter(history: RouterHistory = createWebHistory()): Router {
  const router = createRouter({ history, routes })
  installAuthGuard(router)
  return router
}

const router = createAppRouter()

export default router
