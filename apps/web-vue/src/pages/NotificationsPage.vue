<template>
  <div v-loading="loading">
    <div class="page-header">
      <h2>通知中心</h2>
      <div class="actions">
        <el-button @click="markAll">全部已读</el-button>
        <el-button type="danger" plain @click="clearRead">清除已读</el-button>
      </div>
    </div>

    <el-empty v-if="notifications.length === 0" description="暂无通知" />
    <div v-for="notification in notifications" :key="notification.id" class="notification"
      :class="{ unread: !notification.isRead }" @click="markRead(notification)">
      <div class="title-row">
        <span class="title">{{ notification.title }}</span>
        <el-tag v-if="notification.priority === 'high'" size="small" type="danger">重要</el-tag>
        <span class="muted">{{ formatTime(notification.createdAt) }}</span>
      </div>
      <div class="content">{{ notification.content }}</div>
    </div>

    <div class="pagination">
      <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total"
        layout="prev, pager, next" @current-change="load" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { notificationsApi } from '../api';
import type { AppNotification } from '../api/types';

const loading = ref(false);
const notifications = ref<AppNotification[]>([]);
const page = ref(1);
const pageSize = 20;
const total = ref(0);

async function load() {
  loading.value = true;
  try {
    const result = await notificationsApi.list(page.value, pageSize);
    notifications.value = result.notifications;
    total.value = result.total;
  } finally {
    loading.value = false;
  }
}

async function markRead(notification: AppNotification) {
  if (notification.isRead) {
    return;
  }
  await notificationsApi.markRead(notification.id);
  await load();
}

async function markAll() {
  await notificationsApi.markAllRead();
  await load();
}

async function clearRead() {
  await notificationsApi.clearRead();
  await load();
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}

onMounted(load);
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.actions { display: flex; gap: 8px; }
.notification {
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  cursor: pointer;
}
.notification.unread { background: var(--el-color-primary-light-9); }
.title-row { display: flex; align-items: center; gap: 8px; }
.title { font-weight: 600; }
.content { color: var(--el-text-color-regular); margin-top: 4px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin-left: auto; }
.pagination { margin-top: 16px; display: flex; justify-content: center; }
</style>
