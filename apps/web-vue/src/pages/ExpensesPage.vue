<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">费用 · {{ tripTitle }}</span></template>
    </el-page-header>

    <el-row :gutter="16" class="summary-grid">
      <el-col :span="8"><el-card><el-statistic title="预算" :value="Number(summary?.budget ?? 0)" :precision="2" prefix="¥" /></el-card></el-col>
      <el-col :span="8"><el-card><el-statistic title="总花费" :value="Number(summary?.totalExpenseAmount ?? 0)" :precision="2" prefix="¥" /></el-card></el-col>
      <el-col :span="8"><el-card><el-statistic title="剩余预算" :value="Number(summary?.remainingBudget ?? 0)" :precision="2" prefix="¥" /></el-card></el-col>
    </el-row>

    <div class="toolbar">
      <el-button type="primary" @click="openForm()">记一笔</el-button>
    </div>

    <el-table :data="expenses" border>
      <el-table-column prop="title" label="项目" />
      <el-table-column label="金额" width="120">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column label="分类" width="110">
        <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
      </el-table-column>
      <el-table-column label="付款人" width="110">
        <template #default="{ row }">{{ row.payer.displayName }}</template>
      </el-table-column>
      <el-table-column label="日期" width="120">
        <template #default="{ row }">{{ formatDate(row.spentAt) }}</template>
      </el-table-column>
      <el-table-column label="分摊">
        <template #default="{ row }">
          <span class="muted">{{ row.shares.map((s: any) => `${s.displayName} ¥${s.shareAmount}`).join('、') }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button size="small" text @click="openForm(row)">编辑</el-button>
          <el-button size="small" text type="danger" @click="removeExpense(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-card v-if="summary" class="balance-card">
      <template #header>成员结算</template>
      <div v-for="balance in summary.memberBalances" :key="balance.displayName" class="row-item">
        <span>{{ balance.displayName }}</span>
        <span class="muted">
          垫付 ¥{{ balance.paid }} / 应付 ¥{{ balance.owed }} /
          <el-text :type="Number(balance.balance) >= 0 ? 'success' : 'danger'">
            {{ Number(balance.balance) >= 0 ? '应收' : '应付' }} ¥{{ Math.abs(Number(balance.balance)).toFixed(2) }}
          </el-text>
        </span>
      </div>
    </el-card>

    <el-dialog v-model="formVisible" title="费用" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="项目" required>
          <el-input v-model="form.title" maxlength="120" />
        </el-form-item>
        <el-form-item label="金额" required>
          <el-input-number v-model="amountValue" :min="0" :precision="2" :step="10" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category">
            <el-option v-for="c in CATEGORIES" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="付款人" required>
          <el-select v-model="form.payerUserId">
            <el-option v-for="m in members" :key="m.userId" :label="m.displayName" :value="m.userId" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期" required>
          <el-date-picker v-model="form.spentAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.000Z" />
        </el-form-item>
        <el-form-item label="分摊成员">
          <el-checkbox-group v-model="form.shareUserIds">
            <el-checkbox v-for="m in members" :key="m.userId" :label="m.displayName" :value="m.userId" />
          </el-checkbox-group>
          <div class="muted">不填分摊成员则无法保存；金额将平均分摊</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
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
import { errorMessage, expensesApi, membersApi, tripsApi } from '../api';
import type { ExpenseSummary, TripExpense, TripMember } from '../api/types';

const CATEGORIES = [
  { value: 'transport', label: '交通' },
  { value: 'hotel', label: '住宿' },
  { value: 'food', label: '餐饮' },
  { value: 'ticket', label: '门票' },
  { value: 'parking', label: '停车' },
  { value: 'shopping', label: '购物' },
  { value: 'maintenance', label: '维修' },
  { value: 'activity', label: '活动' },
  { value: 'other', label: '其他' },
];

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const saving = ref(false);
const tripTitle = ref('');
const expenses = ref<TripExpense[]>([]);
const summary = ref<ExpenseSummary | null>(null);
const members = ref<TripMember[]>([]);
const formVisible = ref(false);
const editingId = ref<string | null>(null);
const amountValue = ref<number>(0);

const form = reactive({
  title: '',
  category: 'other',
  payerUserId: '',
  spentAt: new Date().toISOString(),
  notes: '',
  shareUserIds: [] as string[],
});

async function load() {
  loading.value = true;
  try {
    tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
    const result = await expensesApi.list(tripId);
    expenses.value = result.expenses;
    summary.value = await expensesApi.summary(tripId);
    members.value = (await membersApi.list(tripId)).members;
  } finally {
    loading.value = false;
  }
}

function openForm(expense?: TripExpense) {
  editingId.value = expense?.id ?? null;
  form.title = expense?.title ?? '';
  amountValue.value = expense ? Number(expense.amount) : 0;
  form.category = expense?.category ?? 'other';
  form.payerUserId = expense?.payer.id ?? members.value[0]?.userId ?? '';
  form.spentAt = expense?.spentAt ?? new Date().toISOString();
  form.notes = expense?.notes ?? '';
  form.shareUserIds = expense?.shares.map(share => share.userId) ?? [];
  formVisible.value = true;
}

async function save() {
  if (!form.title.trim() || amountValue.value <= 0) {
    ElMessage.warning('请填写项目和大于 0 的金额');
    return;
  }
  if (form.shareUserIds.length === 0) {
    ElMessage.warning('请至少选择一个分摊成员');
    return;
  }
  saving.value = true;
  try {
    const payload: Record<string, unknown> = {
      title: form.title,
      amount: amountValue.value.toFixed(2),
      category: form.category,
      payerUserId: form.payerUserId,
      currency: 'CNY',
      spentAt: form.spentAt,
      notes: form.notes || null,
      shareUserIds: form.shareUserIds,
    };
    if (editingId.value) {
      await expensesApi.update(tripId, editingId.value, payload);
    } else {
      await expensesApi.create(tripId, payload);
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

async function removeExpense(expense: TripExpense) {
  await ElMessageBox.confirm(`确定删除「${expense.title}」吗？`, '删除确认', { type: 'warning' });
  await expensesApi.remove(tripId, expense.id);
  await load();
}

function categoryLabel(value: string) {
  return CATEGORIES.find(item => item.value === value)?.label ?? value;
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.summary-grid { margin: 16px 0; }
.toolbar { margin-bottom: 12px; }
.balance-card { margin-top: 16px; }
.row-item {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-extra-light);
}
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
