<template>
  <el-card class="form-card">
    <h2>{{ isEdit ? '编辑旅行' : '新建旅行' }}</h2>
    <el-form :model="form" label-width="90px">
      <el-form-item label="标题" required>
        <el-input v-model="form.title" maxlength="120" placeholder="旅行标题" />
      </el-form-item>
      <el-form-item label="目的地">
        <el-input v-model="form.destination" placeholder="目的地" />
      </el-form-item>
      <el-form-item label="日期范围">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期" />
      </el-form-item>
      <el-form-item label="预算">
        <el-input-number v-model="budgetValue" :min="0" :precision="2" :step="500" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="4" placeholder="旅行描述（可选）" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button @click="router.back()">取消</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { errorMessage, tripsApi } from '../api';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string | undefined;
const isEdit = Boolean(tripId);
const saving = ref(false);

const form = reactive({
  title: '',
  destination: '',
  description: '',
});
const dateRange = ref<[string, string] | null>(null);
const budgetValue = ref<number | undefined>(undefined);

const budget = computed(() =>
  budgetValue.value === undefined || budgetValue.value === null ? null : String(budgetValue.value));

onMounted(async () => {
  if (tripId) {
    const { trip } = await tripsApi.detail(tripId);
    form.title = trip.title;
    form.destination = trip.destination ?? '';
    form.description = trip.description ?? '';
    budgetValue.value = trip.budget ? Number(trip.budget) : undefined;
    if (trip.startDate && trip.endDate) {
      dateRange.value = [trip.startDate.slice(0, 10), trip.endDate.slice(0, 10)];
    }
  }
});

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写旅行标题');
    return;
  }
  saving.value = true;
  try {
    const payload: Record<string, unknown> = {
      title: form.title,
      description: form.description || null,
      destination: form.destination || null,
      startDate: dateRange.value?.[0] ?? null,
      endDate: dateRange.value?.[1] ?? null,
      budget: budget.value,
    };
    if (isEdit && tripId) {
      await tripsApi.update(tripId, payload);
      ElMessage.success('已保存');
      router.push(`/trips/${tripId}`);
    } else {
      const { trip } = await tripsApi.create(payload);
      ElMessage.success('已创建');
      router.push(`/trips/${trip.id}`);
    }
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.form-card { max-width: 640px; }
</style>
