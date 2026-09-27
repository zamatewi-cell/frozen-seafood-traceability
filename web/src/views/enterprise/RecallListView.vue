<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StatusBadge from '@/components/enterprise/StatusBadge.vue'
import { ApiError } from '@/api/client'
import { listRecalls } from '@/api/recalls'
import { useSession } from '@/stores/session'
import type { Recall } from '@/types/enterprise'
import { formatIsoDateTime, formatRecallStatus } from '@/utils/formatters'

/**
 * 模拟召回列表（Phase B PB5）：本组织发起的召回，以及本组织持有范围批次的召回（上游召回通知 / 溯源协查）。
 * 模拟召回是教学演练，不代表真实法定召回。
 */
const { user } = useSession()
const loadState = ref<'loading' | 'loaded' | 'error'>('loading')
const loadError = ref('')
const recalls = ref<Recall[]>([])
let controller: AbortController | null = null

async function load() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loadState.value = 'loading'
  try {
    const result = await listRecalls(current.signal)
    if (current.signal.aborted) return
    recalls.value = result
    loadState.value = 'loaded'
  } catch (err: unknown) {
    if (current.signal.aborted) return
    loadState.value = 'error'
    loadError.value = err instanceof ApiError ? err.message : '模拟召回加载失败，请稍后重试'
  }
}

/**
 * 与本组织的关系（服务端按批次当前责任组织与发起时快照计算）。当前负责方只标明关系、不断言“待处置”：
 * 本组织负责的可能只是追溯祖先行，案件也可能已结案；具体处置以召回详情为准。
 */
function relationLabel(r: Recall): string {
  switch (r.viewerRelation) {
    case 'OWNER':
      return '本组织发起'
    case 'CURRENT_HOLDER':
      return '本组织当前负责范围批次'
    case 'HISTORICAL_HOLDER':
      return '本组织曾持有范围批次（历史快照，只读）'
    case 'PLATFORM':
      return '平台只读'
    default:
      return r.ownerOrgId === user.value?.orgId ? '本组织发起' : '本组织持有范围批次（通知 / 协查）'
  }
}

load()
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="recall-list-page">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">模拟召回</h1>
        <p class="ent-page-subtitle">
          教学演练中的模拟召回案件：反向追溯上游、正向圈定后续批次，并记录库存、在途与已售事实。不代表真实法定召回或监管结论。
        </p>
      </div>
    </div>

    <div v-if="loadState === 'loading'" class="ent-card ent-state" data-testid="recalls-loading">正在加载模拟召回…</div>
    <div v-else-if="loadState === 'error'" class="ent-card ent-state error" role="alert">
      <p>{{ loadError }}</p>
      <button type="button" class="ent-button" @click="load">重试</button>
    </div>
    <div v-else-if="recalls.length === 0" class="ent-card ent-state" data-testid="recalls-empty">暂无模拟召回。</div>
    <div v-else class="ent-card ent-table-scroll">
      <table class="ent-table" data-testid="recall-table">
        <thead>
          <tr><th>召回编号</th><th>状态</th><th>原因</th><th>发起时间</th><th>与本组织关系</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in recalls" :key="r.id" data-testid="recall-row" :data-recall-id="r.id" :data-status="r.status">
            <td class="mono"><RouterLink :to="`/app/recalls/${r.id}`" :data-testid="`open-recall-${r.id}`">{{ r.recallNo }}</RouterLink></td>
            <td><StatusBadge :info="formatRecallStatus(r.status)" dimension="召回" /></td>
            <td>{{ r.reason }}</td>
            <td>{{ formatIsoDateTime(r.startedAt) }}</td>
            <td data-testid="recall-relation" :data-relation="r.viewerRelation">{{ relationLabel(r) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
