<template>
  <div>
    <div class="page-header">
      <h2>我的旅行</h2>
      <el-button type="primary" @click="router.push('/trips/new')">新建旅行</el-button>
    </div>
    <el-empty v-if="!loading && trips.length === 0" description="还没有旅行，创建一个吧" />
    <el-row :gutter="16">
      <el-col v-for="trip in trips" :key="trip.id" :span="8" class="trip-col">
        <el-card shadow="hover" class="trip-card" @click="router.push(`/trips/${trip.id}`)">
          <img v-if="trip.coverImageUrl" :src="trip.coverImageUrl" class="trip-cover" />
          <div class="trip-body">
            <div class="trip-title">
              {{ trip.isFavorite ? '⭐ ' : '' }}{{ trip.title }}
              <el-tag size="small" :type="statusType(trip.status)">{{ statusLabel(trip.status) }}</el-tag>
            </div>
            <div class="muted">{{ trip.destination ?? '目的地未设置' }}</div>
            <div class="muted">{{ formatDate(trip.startDate) }} ~ {{ formatDate(trip.endDate) }}</div>
            <div class="trip-actions">
              <el-button size="small" text type="primary"
                @click.stop="toggleFavorite(trip)">{{ trip.isFavorite ? '取消收藏' : '收藏' }}</el-button>
              <el-button size="small" text @click.stop="duplicate(trip)">复制</el-button>
              <el-button size="small" text type="danger" @click.stop="removeTrip(trip)">删除</el-button>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage, tripsApi } from '../api';
import type { Trip } from '../api/types';

const router = useRouter();
const trips = ref<Trip[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    trips.value = (await tripsApi.list()).trips;
  } finally {
    loading.value = false;
  }
}

async function toggleFavorite(trip: Trip) {
  await tripsApi.favorite(trip.id);
  await load();
}

async function duplicate(trip: Trip) {
  await tripsApi.duplicate(trip.id);
  ElMessage.success('已复制');
  await load();
}

async function removeTrip(trip: Trip) {
  await ElMessageBox.confirm(`确定删除旅行「${trip.title}」吗？`, '删除确认', { type: 'warning' });
  await tripsApi.remove(trip.id);
  ElMessage.success('已删除');
  await load();
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    draft: '草稿', planning: '规划中', active: '进行中', completed: '已完成', archived: '已归档',
  };
  return labels[status] ?? status;
}

function statusType(status: string): 'info' | 'primary' | 'success' | 'warning' {
  const types: Record<string, 'info' | 'primary' | 'success' | 'warning'> = {
    draft: 'info', planning: 'primary', active: 'success', completed: 'warning', archived: 'info',
  };
  return types[status] ?? 'info';
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '未设置';
}

onMounted(load);
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; }
.trip-col { margin-bottom: 16px; }
.trip-card { cursor: pointer; }
.trip-cover { width: 100%; height: 140px; object-fit: cover; border-radius: 6px; }
.trip-body { padding-top: 10px; display: flex; flex-direction: column; gap: 4px; }
.trip-title { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.trip-actions { display: flex; justify-content: flex-end; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
