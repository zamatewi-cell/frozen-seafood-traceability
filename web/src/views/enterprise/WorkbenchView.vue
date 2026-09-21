<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useSession } from '@/stores/session'
import { formatOrgType } from '@/utils/formatters'

const { user } = useSession()

const roles = computed(() => (user.value?.roles.length ? user.value.roles.join('、') : '无'))
const scopes = computed(() => (user.value?.scopes.length ? user.value.scopes.join('、') : '无'))
</script>

<template>
  <div class="workbench">
    <div class="ent-page-header">
      <div>
        <h1 class="ent-page-title">工作台</h1>
        <p class="ent-page-subtitle">以下信息全部来自当前服务端会话，不包含任何演示统计数据。</p>
      </div>
    </div>

    <section v-if="user" class="ent-card" aria-labelledby="session-card-title" data-testid="workbench-session">
      <h2 id="session-card-title" class="ent-card-title">当前登录</h2>
      <dl class="ent-dl">
        <dt>用户</dt>
        <dd>{{ user.displayName }}（{{ user.username }}）</dd>
        <dt>所属组织</dt>
        <dd>{{ user.orgName }}</dd>
        <dt>组织编号</dt>
        <dd class="mono">{{ user.orgNo }}</dd>
        <dt>组织类型</dt>
        <dd>{{ formatOrgType(user.orgType) }}</dd>
        <dt>角色</dt>
        <dd>{{ roles }}</dd>
        <dt>数据范围</dt>
        <dd>{{ scopes }}</dd>
      </dl>
    </section>

    <section class="ent-card" aria-labelledby="entry-card-title">
      <h2 id="entry-card-title" class="ent-card-title">业务入口</h2>
      <div class="entry-grid">
        <RouterLink to="/app/batches" class="entry-item" data-testid="entry-batches">
          <strong>我的批次</strong>
          <span>查看当前由本组织负责的追溯批次，按流转状态与风险状态筛选。</span>
        </RouterLink>
        <RouterLink to="/trace" class="entry-item">
          <strong>消费者追溯查询</strong>
          <span>以匿名消费者身份查询公开追溯码对应的白名单信息。</span>
        </RouterLink>
      </div>
    </section>
  </div>
</template>

<style scoped>
.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.entry-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-ocean-subtle);
  text-decoration: none;
  color: var(--color-text-body);
}
.entry-item:hover {
  border-color: var(--color-ocean);
}
.entry-item strong {
  color: var(--color-ocean-hover);
  font-size: 14px;
}
.entry-item span {
  font-size: 12px;
  color: var(--color-text-muted);
}
</style>
