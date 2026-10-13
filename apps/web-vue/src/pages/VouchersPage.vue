<template>
  <div v-loading="loading" class="vouchers-page">
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">我的凭证 · {{ tripTitle }}</span></template>
    </el-page-header>
    <p class="sub muted">按时间正序排列，出行时一屏速查</p>

    <el-empty v-if="vouchers.length === 0" description="还没有预订凭证，先去添加预订吧" />

    <div class="voucher-timeline">
      <div v-for="(voucher, index) in vouchers" :key="voucher.id" class="voucher-card">
        <div class="time-chip">{{ formatDateTime(voucher.startTime) }}</div>
        <el-card shadow="never" class="voucher-card-inner">
          <div class="voucher-head">
            <span class="type-badge" :class="`type-${voucher.type}`">{{ typeIcon(voucher.type) }}</span>
            <div class="voucher-title">
              <b>{{ voucher.title }}</b>
              <el-tag size="small" round>{{ typeLabel(voucher.type) }}</el-tag>
            </div>
            <span class="seq">#{{ index + 1 }}</span>
          </div>
          <div class="code-grid">
            <div v-if="voucher.confirmCode" class="code-item">
              <span class="code-label">确认码</span>
              <span class="code-value">{{ voucher.confirmCode }}</span>
            </div>
            <div v-if="voucher.orderNo" class="code-item">
              <span class="code-label">订单号</span>
              <span class="code-value">{{ voucher.orderNo }}</span>
            </div>
            <template v-for="(value, key) in voucher.extra" :key="key">
              <div v-if="value" class="code-item">
                <span class="code-label">{{ extraLabel(String(key), voucher.type) }}</span>
                <span class="code-value">{{ value }}</span>
              </div>
            </template>
          </div>
          <div v-if="voucher.startTime && voucher.endTime" class="muted">
            {{ formatDateTime(voucher.startTime) }} ~ {{ formatDateTime(voucher.endTime) }}
          </div>
          <el-image v-if="voucher.attachmentUrl" :src="voucher.attachmentUrl"
            :preview-src-list="[voucher.attachmentUrl]" fit="cover" class="voucher-img"
            preview-teleported />
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { bookingsApi, tripsApi } from '../api';
import type { Booking } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const loading = ref(false);
const tripTitle = ref('');
const vouchers = ref<Booking[]>([]);

function typeIcon(type: string) {
  return { flight: '✈️', hotel: '🏨', train: '🚄', ticket: '🎫', other: '🚗' }[type] ?? '📄';
}

function typeLabel(type: string) {
  return { flight: '机票', hotel: '酒店', train: '火车', ticket: '门票', other: '其他' }[type] ?? type;
}

function extraLabel(key: string, type: string) {
  const labels: Record<string, Record<string, string>> = {
    flight: { flightNo: '航班号', airports: '起降机场', cabin: '舱位' },
    hotel: { hotelName: '酒店', roomType: '房型', cancelPolicy: '取消政策' },
    train: { trainNo: '车次', stations: '发到站', seat: '座位' },
    ticket: { itemName: '项目', session: '场次' },
    other: { pickup: '取还地点' },
  };
  return labels[type]?.[key] ?? key;
}

function formatDateTime(value: string | null) {
  if (!value) return '时间未设置';
  return new Date(value).toLocaleString('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });
}

onMounted(async () => {
  loading.value = true;
  try {
    tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
    vouchers.value = (await bookingsApi.vouchers(tripId)).vouchers;
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.title { font-weight: 600; }
.sub { margin: 8px 0 16px; color: var(--el-text-color-secondary); font-size: 13px; }

.voucher-timeline {
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 640px;
}
.voucher-card { position: relative; padding-left: 20px; }
.voucher-card::before {
  content: '';
  position: absolute;
  left: 6px;
  top: 24px;
  bottom: -18px;
  width: 2px;
  background: var(--el-border-color-lighter);
}
.voucher-card:last-child::before { display: none; }
.time-chip {
  position: absolute;
  left: -6px;
  top: 14px;
  z-index: 1;
  background: #2f8f5f;
  color: #fff;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 999px;
}
.voucher-card-inner { border-radius: 12px; border: none; box-shadow: 0 3px 12px rgba(64, 92, 128, 0.1); }
.voucher-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.type-badge {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  border-radius: 10px;
  background: #eef4f8;
}
.voucher-title { flex: 1; display: flex; align-items: center; gap: 8px; color: #22384e; }
.seq { color: var(--el-text-color-secondary); font-size: 12px; }

.code-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
  margin-bottom: 8px;
}
@media (min-width: 560px) {
  .code-grid { grid-template-columns: repeat(2, 1fr); }
}
.code-item {
  display: flex;
  gap: 8px;
  align-items: baseline;
  background: #f5f8fa;
  border-radius: 8px;
  padding: 6px 10px;
}
.code-label { font-size: 12px; color: var(--el-text-color-secondary); flex: none; }
.code-value { font-weight: 600; color: #22384e; word-break: break-all; font-variant-numeric: tabular-nums; }
.voucher-img {
  width: 100%;
  max-height: 220px;
  border-radius: 10px;
  margin-top: 8px;
}
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
