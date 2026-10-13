<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}/journals`)">
      <template #content><span class="title">{{ journal?.title ?? '游记详情' }}</span></template>
    </el-page-header>

    <el-card v-if="journal" class="journal-card">
      <div class="meta">
        <el-tag size="small" :type="journal.isDraft ? 'info' : 'success'">
          {{ journal.isDraft ? '草稿' : '已发布' }}
        </el-tag>
        <span v-if="journal.tripDay" class="muted">第 {{ journal.tripDay.dayIndex }} 天</span>
        <span v-if="journal.tripPlace" class="muted">@{{ journal.tripPlace.name }}</span>
        <span class="muted">{{ formatDate(journal.createdAt) }}</span>
        <span v-if="journal.mood" class="mood">{{ moodLabel(journal.mood) }}</span>
      </div>
      <div class="content">{{ journal.content }}</div>
      <div v-if="journal.photos.length" class="photo-grid">
        <img v-for="photo in journal.photos" :key="photo.id" :src="photo.url" :alt="photo.caption ?? ''" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { journalsApi } from '../api';
import type { TripJournal } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const journalId = route.params.journalId as string;
const loading = ref(false);
const journal = ref<TripJournal | null>(null);

function moodLabel(value: string) {
  const labels: Record<string, string> = {
    happy: '😊 开心', excited: '🤩 兴奋', tired: '😴 疲惫', sad: '😢 低落', relaxed: '😌 放松',
  };
  return labels[value] ?? value;
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}

onMounted(async () => {
  loading.value = true;
  try {
    journal.value = (await journalsApi.detail(tripId, journalId)).journal;
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.title { font-weight: 600; }
.journal-card { margin-top: 16px; }
.meta { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.content { white-space: pre-wrap; line-height: 1.8; }
.photo-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 16px;
}
.photo-grid img { width: 100%; height: 150px; object-fit: cover; border-radius: 8px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.mood { font-size: 14px; }
</style>
