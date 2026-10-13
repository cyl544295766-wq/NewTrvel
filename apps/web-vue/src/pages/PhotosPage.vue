<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">照片 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-upload :show-file-list="false" accept="image/jpeg,image/png,image/webp" :auto-upload="false"
        :on-change="handleFile">
        <el-button type="primary">上传照片（≤5MB）</el-button>
      </el-upload>
    </div>

    <el-empty v-if="photos.length === 0" description="还没有照片" />
    <div class="photo-grid">
      <div v-for="photo in photos" :key="photo.id" class="photo-item" @click="preview(photo)">
        <img :src="photo.url" :alt="photo.caption ?? ''" />
        <div class="caption">{{ photo.isCover ? '⭐ ' : '' }}{{ photo.caption ?? '未命名' }}</div>
      </div>
    </div>

    <el-dialog v-model="previewVisible" :title="current?.caption ?? '照片详情'" width="720px">
      <img v-if="current" :src="current.url" style="width: 100%; border-radius: 8px" />
      <el-form label-width="80px" style="margin-top: 12px">
        <el-form-item label="说明">
          <el-input v-model="editCaption" />
        </el-form-item>
        <el-form-item label="拍摄时间">
          <el-date-picker v-model="editShotAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.000Z" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="current?.isCover" @click="setCover">设为封面</el-button>
        <el-button type="danger" @click="removePhoto">删除</el-button>
        <el-button type="primary" @click="savePhoto">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadFile } from 'element-plus';
import { errorMessage, photosApi, tripsApi } from '../api';
import type { Photo } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const tripTitle = ref('');
const photos = ref<Photo[]>([]);
const previewVisible = ref(false);
const current = ref<Photo | null>(null);
const editCaption = ref('');
const editShotAt = ref<string | null>(null);

async function load() {
  loading.value = true;
  try {
    tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
    photos.value = (await photosApi.list(tripId)).photos;
  } finally {
    loading.value = false;
  }
}

async function handleFile(file: UploadFile) {
  const raw = file.raw as File;
  if (!raw) {
    return;
  }
  if (raw.size > 5 * 1024 * 1024) {
    ElMessage.error('单张图片不能超过 5MB');
    return;
  }
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(raw);
  });
  try {
    await photosApi.create(tripId, { url: dataUrl });
    ElMessage.success('已上传');
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

function preview(photo: Photo) {
  current.value = photo;
  editCaption.value = photo.caption ?? '';
  editShotAt.value = photo.shotAt;
  previewVisible.value = true;
}

async function savePhoto() {
  if (!current.value) {
    return;
  }
  try {
    await photosApi.update(tripId, current.value.id, {
      caption: editCaption.value || null,
      shotAt: editShotAt.value,
    });
    previewVisible.value = false;
    await load();
    ElMessage.success('已保存');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function setCover() {
  if (!current.value) {
    return;
  }
  await photosApi.update(tripId, current.value.id, { isCover: true });
  previewVisible.value = false;
  await load();
  ElMessage.success('已设为封面');
}

async function removePhoto() {
  if (!current.value) {
    return;
  }
  await ElMessageBox.confirm('确定删除这张照片吗？', '删除确认', { type: 'warning' });
  await photosApi.remove(tripId, current.value.id);
  previewVisible.value = false;
  await load();
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { margin: 16px 0; }
.photo-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.photo-item { cursor: pointer; }
.photo-item img {
  width: 100%;
  height: 150px;
  object-fit: cover;
  border-radius: 8px;
}
.caption {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
