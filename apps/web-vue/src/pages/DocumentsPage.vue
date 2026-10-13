<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">旅行文档 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-button v-if="canWrite" type="primary" @click="formVisible = true">上传文档</el-button>
    </div>

    <el-empty v-if="documents.length === 0" description="还没有文档" />
    <el-table v-else :data="documents" border>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" />
      <el-table-column label="到期时间" width="130">
        <template #default="{ row }">
          <span :class="{ expired: row.isReminder && row.expiredAt && new Date(row.expiredAt) < new Date() }">
            {{ row.expiredAt ? formatDate(row.expiredAt) : '—' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="提醒" width="80">
        <template #default="{ row }">{{ row.isReminder ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openFile(row.url)">查看</el-button>
          <el-button size="small" text @click="toggleReminder(row)">{{ row.isReminder ? '关闭提醒' : '开启提醒' }}</el-button>
          <el-button size="small" text type="danger" @click="removeDocument(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" title="上传文档" width="520px">
      <el-form label-width="80px">
        <el-form-item label="类型" required>
          <el-select v-model="form.type">
            <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" />
        </el-form-item>
        <el-form-item label="文件" required>
          <input type="file" accept="application/pdf,image/jpeg,image/png,image/webp" @change="handleFile" />
          <div class="muted">支持 PDF / jpg / png / webp，≤10MB</div>
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker v-model="form.expiredAt" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="到期提醒">
          <el-switch v-model="form.isReminder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { documentsApi, errorMessage, tripsApi } from '../api';
import type { TravelDocument } from '../api/types';

const TYPES = [
  { value: 'passport', label: '护照' },
  { value: 'visa', label: '签证' },
  { value: 'flight', label: '机票' },
  { value: 'hotel', label: '酒店确认单' },
  { value: 'ticket', label: '门票' },
  { value: 'insurance', label: '保险' },
  { value: 'other', label: '其他' },
];

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const canWrite = computed(() => tripRole.value !== 'viewer');
const saving = ref(false);
const tripTitle = ref('');
const tripRole = ref('');
const documents = ref<TravelDocument[]>([]);
const formVisible = ref(false);
const fileDataUrl = ref<string | null>(null);

const form = reactive({
  type: 'flight',
  title: '',
  expiredAt: null as string | null,
  isReminder: false,
});

async function load() {
  loading.value = true;
  try {
    const trip = await tripsApi.detail(tripId);
    tripTitle.value = trip.trip.title;
    tripRole.value = trip.trip.currentUserRole;
    documents.value = (await documentsApi.list(tripId)).documents;
  } finally {
    loading.value = false;
  }
}

async function handleFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('单个文档不能超过 10MB');
    return;
  }
  fileDataUrl.value = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
}

async function save() {
  if (!form.title.trim() || !fileDataUrl.value) {
    ElMessage.warning('请填写标题并选择文件');
    return;
  }
  saving.value = true;
  try {
    await documentsApi.create(tripId, {
      type: form.type,
      title: form.title,
      url: fileDataUrl.value,
      expiredAt: form.expiredAt,
      isReminder: form.isReminder,
    });
    formVisible.value = false;
    form.title = '';
    fileDataUrl.value = null;
    ElMessage.success('已上传');
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function toggleReminder(document_: TravelDocument) {
  await documentsApi.update(tripId, document_.id, { isReminder: !document_.isReminder });
  await load();
}

async function removeDocument(document_: TravelDocument) {
  await ElMessageBox.confirm(`确定删除「${document_.title}」吗？`, '删除确认', { type: 'warning' });
  await documentsApi.remove(tripId, document_.id);
  await load();
}

function openFile(url: string) {
  window.open(url, '_blank');
}

function typeLabel(value: string) {
  return TYPES.find(item => item.value === value)?.label ?? value;
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { margin: 16px 0; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.expired { color: var(--el-color-danger); }
</style>
