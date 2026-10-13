<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">游记 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-button type="primary" @click="openForm()">写游记</el-button>
    </div>

    <el-empty v-if="journals.length === 0" description="还没有游记" />
    <el-row :gutter="16">
      <el-col v-for="journal in journals" :key="journal.id" :span="8" class="journal-col">
        <el-card shadow="hover" class="journal-card" @click="router.push(`/trips/${tripId}/journals/${journal.id}`)">
          <div class="journal-title">
            {{ journal.title }}
            <el-tag size="small" :type="journal.isDraft ? 'info' : 'success'">
              {{ journal.isDraft ? '草稿' : '已发布' }}
            </el-tag>
          </div>
          <p class="muted">{{ journal.content.slice(0, 80) }}…</p>
          <div class="muted">{{ formatDate(journal.createdAt) }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="formVisible" title="写游记" width="640px">
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="200" />
        </el-form-item>
        <el-form-item label="正文" required>
          <el-input v-model="form.content" type="textarea" :rows="8" maxlength="20000" show-word-limit />
        </el-form-item>
        <el-form-item label="心情">
          <el-select v-model="form.mood" clearable>
            <el-option label="开心" value="happy" />
            <el-option label="兴奋" value="excited" />
            <el-option label="疲惫" value="tired" />
            <el-option label="低落" value="sad" />
            <el-option label="放松" value="relaxed" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联照片">
          <el-select v-model="form.photoIds" multiple style="width: 100%">
            <el-option v-for="photo in photos" :key="photo.id" :label="photo.caption ?? '未命名'"
              :value="photo.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="发布">
          <el-switch v-model="form.isDraft" active-text="草稿" inactive-text="发布" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage, journalsApi, photosApi, tripsApi } from '../api';
import type { Photo, TripJournal } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const saving = ref(false);
const tripTitle = ref('');
const journals = ref<TripJournal[]>([]);
const photos = ref<Photo[]>([]);
const formVisible = ref(false);
const editingId = ref<string | null>(null);

const form = reactive({
  title: '',
  content: '',
  mood: null as string | null,
  isDraft: true,
  photoIds: [] as string[],
});

async function load() {
  loading.value = true;
  try {
    tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
    journals.value = (await journalsApi.list(tripId)).journals;
    photos.value = (await photosApi.list(tripId)).photos;
  } finally {
    loading.value = false;
  }
}

function openForm(journal?: TripJournal) {
  editingId.value = journal?.id ?? null;
  form.title = journal?.title ?? '';
  form.content = journal?.content ?? '';
  form.mood = journal?.mood ?? null;
  form.isDraft = journal?.isDraft ?? true;
  form.photoIds = journal?.photos.map(photo => photo.id) ?? [];
  formVisible.value = true;
}

async function save() {
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('请填写标题和正文');
    return;
  }
  saving.value = true;
  try {
    const payload: Record<string, unknown> = {
      title: form.title,
      content: form.content,
      mood: form.mood,
      isDraft: form.isDraft,
      photoIds: form.photoIds,
    };
    if (editingId.value) {
      await journalsApi.update(tripId, editingId.value, payload);
    } else {
      await journalsApi.create(tripId, payload);
    }
    formVisible.value = false;
    ElMessage.success('已保存');
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function removeJournal(journal: TripJournal) {
  await ElMessageBox.confirm(`确定删除「${journal.title}」吗？`, '删除确认', { type: 'warning' });
  await journalsApi.remove(tripId, journal.id);
  await load();
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}

defineExpose({ removeJournal });
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { margin: 16px 0; }
.journal-col { margin-bottom: 16px; }
.journal-card { cursor: pointer; }
.journal-title { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
