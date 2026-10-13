<template>
  <div v-loading="loading">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">预订管理 · {{ tripTitle }}</span></template>
    </el-page-header>

    <div class="toolbar">
      <el-button type="primary" @click="router.push(`/trips/${tripId}/vouchers`)">🎫 我的凭证</el-button>
      <el-button v-if="canWrite" type="primary" plain @click="openForm()">新建预订</el-button>
    </div>

    <el-empty v-if="bookings.length === 0" description="还没有预订，添加机票/酒店/火车票等" />

    <div class="booking-list">
      <el-card v-for="booking in bookings" :key="booking.id" shadow="never" class="booking-card">
        <div class="booking-row">
          <span class="type-badge" :class="`type-${booking.type}`">{{ typeIcon(booking.type) }}</span>
          <div class="booking-main" @click="openForm(booking)">
            <div class="booking-title">
              {{ booking.title }}
              <el-tag size="small" round>{{ typeLabel(booking.type) }}</el-tag>
              <el-tag v-if="booking.reminderEnabled" size="small" round type="warning" effect="plain">
                🔔 提前 {{ formatMinutes(booking.reminderMinutes) }}
              </el-tag>
            </div>
            <div class="muted">
              {{ formatDateTime(booking.startTime) }}
              <template v-if="booking.endTime"> ~ {{ formatDateTime(booking.endTime) }}</template>
            </div>
            <div class="muted" v-if="booking.confirmCode || booking.orderNo">
              <span v-if="booking.confirmCode">确认码 <b>{{ booking.confirmCode }}</b></span>
              <span v-if="booking.orderNo"> · 订单号 <b>{{ booking.orderNo }}</b></span>
            </div>
            <div class="muted" v-if="booking.amount">金额 ¥{{ booking.amount }}</div>
          </div>
          <div class="booking-actions" v-if="canWrite">
            <el-switch v-model="booking.reminderEnabled" inline-prompt active-text="提醒"
              @change="(value: string | number | boolean) => toggleReminder(booking, value)" />
            <el-button size="small" text type="danger" @click="removeBooking(booking)">删除</el-button>
          </div>
        </div>
      </el-card>
    </div>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑预订' : '新建预订'" width="620px" top="6vh">
      <el-form label-width="90px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.type">
            <el-radio-button value="flight">✈️ 机票</el-radio-button>
            <el-radio-button value="hotel">🏨 酒店</el-radio-button>
            <el-radio-button value="train">🚄 火车</el-radio-button>
            <el-radio-button value="ticket">🎫 门票</el-radio-button>
            <el-radio-button value="other">🚗 其他</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" :placeholder="typePlaceholder" maxlength="120" />
        </el-form-item>

        <!-- 按类型动态字段（存入 extra） -->
        <template v-if="form.type === 'flight'">
          <el-form-item label="航班号"><el-input v-model="extra.flightNo" placeholder="如 CA1234" /></el-form-item>
          <el-form-item label="起降机场"><el-input v-model="extra.airports" placeholder="如 大兴T2 → 虹桥T2" /></el-form-item>
          <el-form-item label="舱位"><el-input v-model="extra.cabin" placeholder="如 经济舱" /></el-form-item>
        </template>
        <template v-else-if="form.type === 'hotel'">
          <el-form-item label="酒店名称"><el-input v-model="extra.hotelName" /></el-form-item>
          <el-form-item label="房型"><el-input v-model="extra.roomType" /></el-form-item>
          <el-form-item label="取消政策"><el-input v-model="extra.cancelPolicy" placeholder="如 入住前 1 天可免费取消" /></el-form-item>
        </template>
        <template v-else-if="form.type === 'train'">
          <el-form-item label="车次"><el-input v-model="extra.trainNo" placeholder="如 G101" /></el-form-item>
          <el-form-item label="发到站"><el-input v-model="extra.stations" placeholder="如 北京南 → 上海虹桥" /></el-form-item>
          <el-form-item label="座位"><el-input v-model="extra.seat" placeholder="如 05车12F" /></el-form-item>
        </template>
        <template v-else-if="form.type === 'ticket'">
          <el-form-item label="项目名称"><el-input v-model="extra.itemName" placeholder="如 故宫门票" /></el-form-item>
          <el-form-item label="场次"><el-input v-model="extra.session" placeholder="如 上午场" /></el-form-item>
        </template>
        <template v-else>
          <el-form-item label="取还地点"><el-input v-model="extra.pickup" placeholder="取/还车时间地点" /></el-form-item>
        </template>

        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.000Z" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.000Z" />
        </el-form-item>
        <el-form-item label="订单号"><el-input v-model="form.orderNo" /></el-form-item>
        <el-form-item label="确认码"><el-input v-model="form.confirmCode" /></el-form-item>
        <el-form-item label="金额">
          <el-input-number v-model="amountValue" :min="0" :precision="2" />
          <span class="muted" style="margin-left:8px">CNY</span>
        </el-form-item>
        <el-form-item label="关联行程天">
          <el-select v-model="form.tripDayId" clearable style="width:100%">
            <el-option v-for="day in days" :key="day.id" :value="day.id"
              :label="`第 ${day.dayIndex} 天 · ${formatDate(day.date)}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="行前提醒">
          <el-switch v-model="form.reminderEnabled" active-text="开启" inactive-text="关闭" />
          <el-select v-model="form.reminderMinutes" style="width:160px;margin-left:12px"
            :disabled="!form.reminderEnabled">
            <el-option label="提前 30 分钟" :value="30" />
            <el-option label="提前 1 小时" :value="60" />
            <el-option label="提前 2 小时" :value="120" />
            <el-option label="提前 24 小时" :value="1440" />
            <el-option label="提前 48 小时" :value="2880" />
          </el-select>
        </el-form-item>
        <el-form-item label="凭证图片">
          <input type="file" accept="image/jpeg,image/png,image/webp" @change="handleFile" />
          <div class="muted">截图订单/二维码，凭证页可一屏速查（≤5MB）</div>
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
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { errorMessage, bookingsApi, daysApi, tripsApi } from '../api';
import type { Booking, TripDay } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const saving = ref(false);
const tripTitle = ref('');
const tripRole = ref('');
const canWrite = computed(() => tripRole.value !== 'viewer');
const bookings = ref<Booking[]>([]);
const days = ref<TripDay[]>([]);
const formVisible = ref(false);
const editingId = ref<string | null>(null);
const amountValue = ref<number | undefined>(undefined);
const attachmentUrl = ref<string | null>(null);

const form = reactive({
  type: 'flight' as Booking['type'],
  title: '',
  startTime: null as string | null,
  endTime: null as string | null,
  orderNo: '',
  confirmCode: '',
  tripDayId: null as string | null,
  reminderEnabled: true,
  reminderMinutes: 1440,
});
const extra = reactive<Record<string, string>>({});

const typePlaceholder = computed(() => ({
  flight: '如 北京 → 上海 CA1234',
  hotel: '如 亚朵酒店（外滩店）',
  train: '如 G101 北京南 → 上海虹桥',
  ticket: '如 故宫门票（上午场）',
  other: '如 租车：神州租车',
}[form.type]));

function typeIcon(type: string) {
  return { flight: '✈️', hotel: '🏨', train: '🚄', ticket: '🎫', other: '🚗' }[type] ?? '📄';
}

function typeLabel(type: string) {
  return { flight: '机票', hotel: '酒店', train: '火车', ticket: '门票', other: '其他' }[type] ?? type;
}

async function load() {
  loading.value = true;
  try {
    const trip = await tripsApi.detail(tripId);
    tripTitle.value = trip.trip.title;
    tripRole.value = trip.trip.currentUserRole;
    bookings.value = (await bookingsApi.list(tripId)).bookings;
    days.value = (await daysApi.list(tripId)).days;
  } finally {
    loading.value = false;
  }
}

function openForm(booking?: Booking) {
  editingId.value = booking?.id ?? null;
  form.type = booking?.type ?? 'flight';
  form.title = booking?.title ?? '';
  form.startTime = booking?.startTime ?? null;
  form.endTime = booking?.endTime ?? null;
  form.orderNo = booking?.orderNo ?? '';
  form.confirmCode = booking?.confirmCode ?? '';
  form.tripDayId = booking?.tripDayId ?? null;
  form.reminderEnabled = booking?.reminderEnabled ?? true;
  form.reminderMinutes = booking?.reminderMinutes ?? 1440;
  amountValue.value = booking?.amount ? Number(booking.amount) : undefined;
  attachmentUrl.value = booking?.attachmentUrl ?? null;
  Object.keys(extra).forEach(key => delete extra[key]);
  const source = (booking?.extra ?? {}) as Record<string, unknown>;
  Object.entries(source).forEach(([key, value]) => {
    extra[key] = value == null ? '' : String(value);
  });
  formVisible.value = true;
}

async function handleFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB');
    return;
  }
  attachmentUrl.value = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.readAsDataURL(file);
  });
  ElMessage.success('凭证图片已就绪，保存后生效');
}

async function save() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写预订标题');
    return;
  }
  saving.value = true;
  try {
    const payload: Record<string, unknown> = {
      type: form.type,
      title: form.title,
      startTime: form.startTime,
      endTime: form.endTime,
      orderNo: form.orderNo || null,
      confirmCode: form.confirmCode || null,
      amount: amountValue.value === undefined || amountValue.value === null ? null : amountValue.value.toFixed(2),
      currency: 'CNY',
      attachmentUrl: attachmentUrl.value,
      tripDayId: form.tripDayId,
      reminderEnabled: form.reminderEnabled,
      reminderMinutes: form.reminderMinutes,
      extra: { ...extra },
    };
    if (editingId.value) {
      await bookingsApi.update(tripId, editingId.value, payload);
    } else {
      await bookingsApi.create(tripId, payload);
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

async function toggleReminder(booking: Booking, value: unknown) {
  try {
    await bookingsApi.update(tripId, booking.id, { reminderEnabled: Boolean(value) });
    ElMessage.success(Boolean(value) ? '提醒已开启' : '提醒已关闭');
  } catch (error) {
    ElMessage.error(errorMessage(error));
    await load();
  }
}

async function removeBooking(booking: Booking) {
  await ElMessageBox.confirm(`确定删除预订「${booking.title}」吗？`, '删除确认', { type: 'warning' });
  await bookingsApi.remove(tripId, booking.id);
  ElMessage.success('已删除');
  await load();
}

function formatMinutes(value?: number | null) {
  if (!value) return '—';
  return value % 60 === 0 && value >= 60 ? `${value / 60} 小时` : `${value} 分钟`;
}

function formatDateTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '时间未设置';
}

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN');
}

onMounted(load);
</script>

<style scoped>
.title { font-weight: 600; }
.toolbar { display: flex; gap: 8px; margin: 16px 0; }
.booking-card { border-radius: 12px; margin-bottom: 10px; border: none; box-shadow: 0 3px 12px rgba(64, 92, 128, 0.08); }
.booking-row { display: flex; align-items: center; gap: 14px; }
.type-badge {
  flex: none;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  border-radius: 12px;
  background: #eef4f8;
}
.type-flight { background: #e7f0fb; }
.type-hotel { background: #fdeef1; }
.type-train { background: #e9f8ee; }
.type-ticket { background: #faf1de; }
.booking-main { flex: 1; min-width: 0; cursor: pointer; display: flex; flex-direction: column; gap: 3px; }
.booking-title { font-weight: 600; color: #22384e; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.booking-actions { display: flex; align-items: center; gap: 10px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
