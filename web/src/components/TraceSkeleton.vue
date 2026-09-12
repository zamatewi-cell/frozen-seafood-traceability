<template>
  <div class="skeleton-container" aria-busy="true" aria-label="正在加载追溯数据，请稍候">
    <div class="consumer-card skeleton-card">
      <div class="skeleton-line pill-skeleton" />
      <div class="skeleton-line title-skeleton" />
      <div class="skeleton-line subtitle-skeleton" />
      <div class="skeleton-box badge-skeleton" />
    </div>

    <div class="consumer-card skeleton-card">
      <div class="skeleton-line title-skeleton" />
      <div v-for="i in 5" :key="i" class="skeleton-row">
        <div class="skeleton-line short" />
        <div class="skeleton-line medium" />
      </div>
    </div>

    <div class="consumer-card skeleton-card">
      <div class="skeleton-line title-skeleton" />
      <div v-for="i in 3" :key="i" class="skeleton-row">
        <div class="skeleton-circle" />
        <div class="skeleton-line full" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-container {
  display: grid;
  gap: 14px;
}
.skeleton-card {
  padding: 18px;
}
.skeleton-line {
  height: 14px;
  background: linear-gradient(90deg, #e2e8f0 25%, #f1f5f9 37%, #e2e8f0 63%);
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
  border-radius: 4px;
  margin-bottom: 10px;
}
.skeleton-circle {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e2e8f0;
  flex-shrink: 0;
}
.pill-skeleton {
  width: 100px;
  height: 24px;
  border-radius: 999px;
  margin-bottom: 14px;
}
.title-skeleton {
  width: 50%;
  height: 22px;
  margin-bottom: 10px;
}
.subtitle-skeleton {
  width: 70%;
  height: 14px;
  margin-bottom: 14px;
}
.badge-skeleton {
  height: 36px;
  background: #f1f5f9;
  border-radius: 4px;
}
.skeleton-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 12px;
}
.short { width: 25%; }
.medium { width: 45%; }
.full { flex: 1; }
@keyframes shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
</style>
