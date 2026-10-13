<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">行程安排 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-button type="primary" :disabled="!trip" @click="generateDays">按日期生成行程天</el-button>
    </div>

    <el-empty v-if="days.length === 0" description="还没有行程天，点击上方按钮按日期生成" />

    <el-card v-for="day in days" :key="day.id" class="day-card">
      <template #header>
        <div class="day-header">
          <span>第 {{ day.dayIndex }} 天 · {{ formatDate(day.date) }}</span>
          <el-input v-model="dayTitles[day.id]" :placeholder="`第 ${day.dayIndex} 天标题`" size="small"
            style="width: 220px" @change="saveDayTitle(day)" />
        </div>
      </template>

      <div class="place-row" v-for="place in day.places" :key="place.id">
        <el-tag size="small" type="info">{{ typeLabel(place.type) }}</el-tag>
        <span class="place-name">{{ place.name }}</span>
        <span class="muted">{{ place.address ?? '' }}</span>
        <span class="muted">{{ formatTime(place.startTime) }} ~ {{ formatTime(place.endTime) }}</span>
        <el-checkbox :model-value="place.isCompleted" @change="(value: string | number | boolean) => toggleComplete(day, place, value)">
          已完成
        </el-checkbox>
        <el-button size="small" text type="danger" @click="removePlace(day, place)">删除</el-button>
      </div>
      <el-empty v-if="day.places.length === 0" description="当天暂无地点" :image-size="48" />

      <div class="add-row">
        <el-autocomplete v-model="newPlaceName[day.id]" :fetch-suggestions="searchSuggestions"
          placeholder="输入地点名称（支持搜索联想）" style="width: 280px"
          @select="(item: unknown) => applySuggestion(day.id, item as PlaceSuggestion)" />
        <el-select v-model="newPlaceType[day.id]" style="width: 110px">
          <el-option v-for="t in PLACE_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
        <el-button type="primary" size="small" @click="addPlace(day)">添加地点</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { daysApi, errorMessage, placesApi, tripsApi } from '../api';
import type { PlaceSuggestion, Trip, TripDay, TripPlace } from '../api/types';

const PLACE_TYPES = [
  { value: 'attraction', label: '景点' },
  { value: 'hotel', label: '酒店' },
  { value: 'restaurant', label: '餐厅' },
  { value: 'transport', label: '交通' },
  { value: 'shopping', label: '购物' },
  { value: 'custom', label: '自定义' },
];

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const trip = ref<Trip | null>(null);
const tripTitle = ref('');
const days = ref<TripDay[]>([]);
const dayTitles = reactive<Record<string, string>>({});
const newPlaceName = reactive<Record<string, string>>({});
const newPlaceType = reactive<Record<string, string>>({});
const suggestionCache = reactive<Record<string, PlaceSuggestion[]>>({});

async function load() {
  loading.value = true;
  try {
    trip.value = (await tripsApi.detail(tripId)).trip;
    tripTitle.value = trip.value.title;
    days.value = (await daysApi.list(tripId)).days;
    for (const day of days.value) {
      dayTitles[day.id] = day.title ?? '';
      newPlaceType[day.id] = 'attraction';
    }
  } finally {
    loading.value = false;
  }
}

async function generateDays() {
  try {
    days.value = (await daysApi.generate(tripId)).days;
    ElMessage.success('已生成行程天');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function saveDayTitle(day: TripDay) {
  await daysApi.update(tripId, day.id, { title: dayTitles[day.id] || null });
}

async function addPlace(day: TripDay) {
  const name = (newPlaceName[day.id] ?? '').trim();
  if (!name) {
    ElMessage.warning('请输入地点名称');
    return;
  }
  const suggestion = (suggestionCache[day.id] ?? []).find(item => item.name === name);
  try {
    await placesApi.create(tripId, {
      tripDayId: day.id,
      name,
      type: newPlaceType[day.id] ?? 'attraction',
      address: suggestion?.address ?? null,
      latitude: suggestion?.latitude ?? null,
      longitude: suggestion?.longitude ?? null,
    });
    newPlaceName[day.id] = '';
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function removePlace(day: TripDay, place: TripPlace) {
  await placesApi.remove(tripId, place.id);
  day.places = day.places.filter(item => item.id !== place.id);
}

async function toggleComplete(day: TripDay, place: TripPlace, value: unknown) {
  await placesApi.update(tripId, place.id, { isCompleted: Boolean(value) });
  place.isCompleted = Boolean(value);
}

async function searchSuggestions(query: string, callback: (results: unknown[]) => void) {
  if (!query.trim()) {
    callback([]);
    return;
  }
  try {
    const result = await placesApi.suggestions(query.trim(), trip.value?.destination ?? undefined);
    suggestionCache[tripId] = result.suggestions;
    callback(result.suggestions.map(item => ({ value: item.name, ...item })));
  } catch {
    callback([]);
  }
}

function applySuggestion(_dayId: string, item: PlaceSuggestion) {
  // name already set by autocomplete; suggestion resolved on add
  void item;
}

function typeLabel(type: string) {
  return PLACE_TYPES.find(item => item.value === type)?.label ?? type;
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '';
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { margin: 16px 0; }
.day-card { margin-bottom: 16px; }
.day-header { display: flex; justify-content: space-between; align-items: center; }
.place-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.place-name { font-weight: 500; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.add-row { display: flex; gap: 8px; margin-top: 12px; }
</style>
