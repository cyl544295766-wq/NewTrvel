<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">打包清单 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-select v-model="newCategory" style="width: 140px">
        <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
      </el-select>
      <el-input v-model="newName" placeholder="清单名称" style="width: 220px" />
      <el-button type="primary" @click="createList">新建清单（自动填充模板）</el-button>
    </div>

    <el-empty v-if="lists.length === 0" description="还没有打包清单" />
    <el-row :gutter="16">
      <el-col v-for="list in lists" :key="list.id" :span="8" class="list-col">
        <el-card>
          <template #header>
            <div class="list-header">
              <span>{{ list.name }} <el-tag size="small">{{ categoryLabel(list.category) }}</el-tag></span>
              <span class="muted">{{ list.packedCount }}/{{ list.itemCount }}</span>
            </div>
          </template>
          <el-progress :percentage="list.itemCount === 0 ? 0 : Math.round(list.packedCount * 100 / list.itemCount)"
            :stroke-width="8" style="margin-bottom: 8px" />
          <div v-for="item in list.items" :key="item.id" class="item-row">
            <el-checkbox :model-value="item.isPacked"
              @change="(value: string | number | boolean) => toggleItem(list, item, value)">{{ item.name }}</el-checkbox>
            <span class="muted">×{{ item.quantity }}</span>
            <el-button size="small" text type="danger" @click="removeItem(list, item)">删</el-button>
          </div>
          <div class="add-item-row">
            <el-input v-model="itemDrafts[list.id]" size="small" placeholder="添加物品"
              @keyup.enter="addItem(list)" />
            <el-button size="small" type="primary" @click="addItem(list)">添加</el-button>
          </div>
          <div class="list-actions">
            <el-button size="small" text @click="duplicateList(list)">复制清单</el-button>
            <el-button size="small" text type="danger" @click="removeList(list)">删除清单</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage, packingApi, tripsApi } from '../api';
import type { PackingItem, PackingList } from '../api/types';

const CATEGORIES = [
  { value: 'clothing', label: '衣物' },
  { value: 'toiletries', label: '洗漱' },
  { value: 'electronics', label: '电子' },
  { value: 'medicine', label: '药品' },
  { value: 'documents', label: '证件' },
  { value: 'other', label: '其他' },
];

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const tripTitle = ref('');
const lists = ref<PackingList[]>([]);
const newCategory = ref('clothing');
const newName = ref('');
const itemDrafts = reactive<Record<string, string>>({});

async function load() {
  loading.value = true;
  try {
    tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
    lists.value = (await packingApi.list(tripId)).packingLists;
  } finally {
    loading.value = false;
  }
}

async function createList() {
  if (!newName.value.trim()) {
    ElMessage.warning('请输入清单名称');
    return;
  }
  try {
    await packingApi.create(tripId, { name: newName.value.trim(), category: newCategory.value });
    newName.value = '';
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function addItem(list: PackingList) {
  const name = (itemDrafts[list.id] ?? '').trim();
  if (!name) {
    return;
  }
  try {
    await packingApi.createItem(tripId, list.id, { name });
    itemDrafts[list.id] = '';
    await load();
  } catch (error) {
    ElMessage.error(errorMessage(error));
  }
}

async function toggleItem(list: PackingList, item: PackingItem, value: unknown) {
  await packingApi.updateItem(tripId, list.id, item.id, { isPacked: Boolean(value) });
  await load();
}

async function removeItem(list: PackingList, item: PackingItem) {
  await packingApi.removeItem(tripId, list.id, item.id);
  await load();
}

async function duplicateList(list: PackingList) {
  await packingApi.duplicate(tripId, list.id);
  await load();
}

async function removeList(list: PackingList) {
  await ElMessageBox.confirm(`确定删除清单「${list.name}」吗？`, '删除确认', { type: 'warning' });
  await packingApi.remove(tripId, list.id);
  await load();
}

function categoryLabel(value: string) {
  return CATEGORIES.find(item => item.value === value)?.label ?? value;
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { display: flex; gap: 8px; margin: 16px 0; }
.list-col { margin-bottom: 16px; }
.list-header { display: flex; justify-content: space-between; align-items: center; }
.item-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 4px 0;
}
.add-item-row { display: flex; gap: 6px; margin-top: 8px; }
.list-actions { display: flex; justify-content: flex-end; margin-top: 8px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
