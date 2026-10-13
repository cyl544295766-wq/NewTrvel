<template>
  <div class="app-shell">
    <aside class="app-sidebar">
      <div class="app-logo">🧭 Travel OS</div>
      <nav class="app-nav">
        <RouterLink to="/" class="nav-item">🏠 首页</RouterLink>
        <RouterLink to="/trips" class="nav-item">🧳 旅行</RouterLink>
        <RouterLink to="/stats" class="nav-item">📊 统计</RouterLink>
        <RouterLink to="/notifications" class="nav-item">
          🔔 通知
          <el-badge v-if="unreadCount > 0" :value="unreadCount" class="nav-badge" />
        </RouterLink>
      </nav>
      <div class="app-sidebar-footer">
        <RouterLink to="/account" class="user-name" title="账号设置">
          {{ auth.user?.displayName ?? auth.user?.username }}
        </RouterLink>
        <el-button size="small" text @click="handleLogout">退出</el-button>
      </div>
    </aside>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { notificationsApi } from '../api';
import { useAuthStore } from '../stores/auth';

const auth = useAuthStore();
const router = useRouter();
const unreadCount = ref(0);
let timer: number | undefined;

async function refreshUnread() {
  try {
    const result = await notificationsApi.unreadCount();
    unreadCount.value = result.count;
  } catch {
    // ignore
  }
}

async function handleLogout() {
  await auth.logout();
  router.push({ name: 'login' });
}

onMounted(() => {
  refreshUnread();
  timer = window.setInterval(refreshUnread, 60000);
});
onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer);
  }
});
</script>

<style scoped>
.app-shell {
  display: flex;
  min-height: 100vh;
}
.app-sidebar {
  width: 220px;
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  padding: 16px 12px;
  gap: 8px;
}
.app-logo {
  font-size: 18px;
  font-weight: 600;
  padding: 8px 12px 20px;
}
.app-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  color: var(--el-text-color-primary);
  text-decoration: none;
}
.nav-item.router-link-active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.app-sidebar-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.user-name {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-decoration: none;
}
.user-name:hover {
  color: var(--el-color-primary);
}
.app-main {
  flex: 1;
  padding: 24px;
  background: var(--el-fill-color-blank);
}
.nav-badge {
  margin-left: auto;
}
</style>
