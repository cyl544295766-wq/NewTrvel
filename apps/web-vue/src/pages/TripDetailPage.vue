<template>
  <div v-loading="loading">
    <el-page-header @back="router.push('/trips')">
      <template #content>
        <span class="title">{{ trip?.title }}</span>
        <el-tag size="small" class="tag">{{ trip?.currentUserRole }}</el-tag>
      </template>
    </el-page-header>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane label="概览" name="overview">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="目的地">{{ trip?.destination ?? '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ trip?.status }}</el-descriptions-item>
          <el-descriptions-item label="开始日期">{{ formatDate(trip?.startDate) }}</el-descriptions-item>
          <el-descriptions-item label="结束日期">{{ formatDate(trip?.endDate) }}</el-descriptions-item>
          <el-descriptions-item label="预算">¥{{ trip?.budget ?? '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ trip?.description ?? '无' }}</el-descriptions-item>
        </el-descriptions>
        <div class="actions">
          <el-button type="primary" @click="router.push(`/trips/${tripId}/edit`)">编辑</el-button>
          <el-button @click="router.push(`/trips/${tripId}/itinerary`)">行程安排</el-button>
          <el-button @click="router.push(`/trips/${tripId}/expenses`)">费用</el-button>
          <el-button @click="router.push(`/trips/${tripId}/map`)">地图</el-button>
          <el-button @click="router.push(`/trips/${tripId}/photos`)">照片</el-button>
          <el-button @click="router.push(`/trips/${tripId}/documents`)">文档</el-button>
          <el-button @click="router.push(`/trips/${tripId}/packing-lists`)">打包清单</el-button>
          <el-button @click="router.push(`/trips/${tripId}/journals`)">游记</el-button>
          <el-button type="success" @click="exportPdf">导出 PDF</el-button>
          <el-button @click="archive">归档</el-button>
        </div>
      </el-tab-pane>

      <el-tab-pane label="成员" name="members">
        <div class="invite-row">
          <el-input v-model="inviteUsername" placeholder="用户名" style="width: 200px" />
          <el-select v-model="inviteRole" style="width: 120px">
            <el-option label="管理员" value="admin" />
            <el-option label="成员" value="member" />
            <el-option label="观察者" value="viewer" />
          </el-select>
          <el-button type="primary" @click="invite">邀请</el-button>
        </div>
        <el-table :data="members" border>
          <el-table-column prop="displayName" label="昵称" />
          <el-table-column prop="username" label="用户名" />
          <el-table-column prop="role" label="角色" width="120" />
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <el-select v-if="row.role !== 'owner'" :model-value="row.role" size="small"
                @change="(role: string) => changeRole(row, role)">
                <el-option v-for="role in ['admin', 'member', 'viewer']" :key="role" :label="role" :value="role" />
              </el-select>
              <el-button v-if="row.role !== 'owner'" size="small" text type="danger"
                @click="removeMember(row)">移除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="天气" name="weather">
        <el-empty v-if="!weather.length" description="暂无天气数据" />
        <el-table v-else :data="weather" border>
          <el-table-column label="日期" width="120">
            <template #default="{ row }">{{ formatDate(row.date) }}</template>
          </el-table-column>
          <el-table-column prop="condition" label="天气" width="100" />
          <el-table-column label="温度">
            <template #default="{ row }">{{ row.tempLow }}°C ~ {{ row.tempHigh }}°C</template>
          </el-table-column>
          <el-table-column label="降水概率">
            <template #default="{ row }">{{ row.rainChance === null ? '—' : row.rainChance + '%' }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  errorMessage,
  exportTripPdf,
  membersApi,
  tripsApi,
  weatherApi,
} from '../api';
import type { Trip, TripMember, WeatherRow } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const trip = ref<Trip | null>(null);
const members = ref<TripMember[]>([]);
const weather = ref<WeatherRow[]>([]);
const activeTab = ref('overview');
const inviteUsername = ref('');
const inviteRole = ref('member');

async function load() {
  loading.value = true;
  try {
    trip.value = (await tripsApi.detail(tripId)).trip;
    members.value = (await membersApi.list(tripId)).members;
  } finally {
    loading.value = false;
  }
}

watch(activeTab, async (tab) => {
  if (tab === 'weather' && weather.value.length === 0) {
    try {
      const result = await weatherApi.tripWeather(tripId);
      weather.value = result.weather ?? [];
    } catch {
      weather.value = [];
    }
  }
});

async function invite() {
  if (!inviteUsername.value.trim()) {
    ElMessage.warning('请输入用户名');
    return;
  }
  try {
    await membersApi.invite(tripId, inviteUsername.value.trim(), inviteRole.value);
    ElMessage.success('已邀请');
    inviteUsername.value = '';
    members.value = (await membersApi.list(tripId)).members;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function changeRole(member: TripMember, role: string) {
  try {
    await membersApi.updateRole(tripId, member.id, role);
    members.value = (await membersApi.list(tripId)).members;
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function removeMember(member: TripMember) {
  await ElMessageBox.confirm(`确定移除成员「${member.displayName}」吗？`, '移除确认', { type: 'warning' });
  await membersApi.remove(tripId, member.id);
  members.value = (await membersApi.list(tripId)).members;
}

async function archive() {
  await ElMessageBox.confirm('归档后旅行将从列表隐藏，确定吗？', '归档确认', { type: 'warning' });
  await tripsApi.archive(tripId);
  ElMessage.success('已归档');
  router.push('/trips');
}

async function exportPdf() {
  try {
    await exportTripPdf(tripId);
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '未设置';
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.tag { margin-left: 8px; }
.detail-tabs { margin-top: 16px; }
.actions { margin-top: 16px; display: flex; flex-wrap: wrap; gap: 4px; }
.invite-row { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
