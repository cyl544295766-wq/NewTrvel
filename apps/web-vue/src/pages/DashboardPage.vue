<template>
  <div class="dashboard" v-loading="loading">
    <!-- ===== Hero 横幅（真实素材） ===== -->
    <header class="hero">
      <div class="hero-bg" :style="{ backgroundImage: `url(${heroImg})` }"></div>
      <div class="hero-mask"></div>
      <div class="hero-content">
        <div class="hero-left">
          <h1>{{ greeting }}，{{ auth.user?.displayName ?? auth.user?.username }} 👋</h1>
          <p class="hero-sub">{{ todayText }} · 愿你被山海环绕</p>
        </div>
        <div class="hero-slogan">
          <span class="slogan-line">行走在路上</span>
          <span class="slogan-line">遇见更大的世界 ✈</span>
        </div>
      </div>
    </header>

    <!-- ===== 统计数字板（粉彩） ===== -->
    <el-row :gutter="16" class="stat-grid">
      <el-col :span="6" v-for="stat in stats" :key="stat.key">
        <div class="stat-card" :class="`stat-${stat.key}`">
          <span class="stat-icon">{{ stat.icon }}</span>
          <div class="stat-body">
            <div class="stat-label">{{ stat.label }}</div>
            <div class="stat-value">
              <span class="number">{{ stat.display }}</span>
              <span class="unit">{{ stat.unit }}</span>
            </div>
            <div class="stat-foot">{{ stat.foot }}</div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ===== 最近旅行 + 即将出发 ===== -->
    <el-row :gutter="16" class="section">
      <el-col :span="16">
        <el-card shadow="never" class="panel">
          <div class="section-head">
            <span class="head-icon head-blue">✈️</span>
            <span class="head-title">最近的旅行</span>
            <RouterLink to="/trips" class="head-link">查看全部 →</RouterLink>
          </div>
          <div v-if="!data?.recentTrips.length" class="empty-block">
            <img :src="itineraryEmptyImg" class="empty-illustration" alt="暂无旅行" />
            <p class="empty-text">暂无旅行，创建你的第一个旅行规划</p>
            <el-button type="primary" round size="small" @click="router.push('/trips/new')">去创建</el-button>
          </div>
          <div v-for="trip in data?.recentTrips" :key="trip.id" class="trip-row"
            @click="router.push(`/trips/${trip.id}`)">
            <div class="trip-thumb" :style="trip.coverImageUrl
              ? { backgroundImage: `url(${trip.coverImageUrl})` }
              : { backgroundImage: `url(${sceneryImg})` }">
              <span class="thumb-text">{{ (trip.destination ?? trip.title).slice(0, 2) }}</span>
            </div>
            <div class="trip-info">
              <div class="trip-title">
                <span class="name">
                  <el-icon class="pin"><Location /></el-icon>{{ trip.title }}
                </span>
                <el-tag size="small" round type="success">{{ statusLabel(trip.status) }}</el-tag>
                <span v-if="trip.isFavorite" class="fav">⭐</span>
              </div>
              <div class="trip-dates muted">
                {{ formatDate(trip.startDate) }} 至 {{ formatDate(trip.endDate) }}（{{ tripDays(trip) }}天）
              </div>
              <div class="trip-dest muted">
                <el-icon class="pin"><Compass /></el-icon>{{ trip.destination ?? '目的地未设置' }}
              </div>
            </div>
            <el-icon class="trip-arrow"><ArrowRight /></el-icon>
          </div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never" class="panel promo-card">
          <template v-if="data?.upcomingTrips.length">
            <div class="section-head">
              <span class="head-icon head-green">🚗</span>
              <span class="head-title">即将出发</span>
            </div>
            <div class="promo-list">
              <div v-for="trip in data.upcomingTrips" :key="trip.id" class="promo-trip"
                @click="router.push(`/trips/${trip.id}`)">
                🧳 {{ trip.title }}
                <span class="promo-date">{{ formatDate(trip.startDate) }}</span>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="promo-photo" :style="{ backgroundImage: `url(${upcomingImg})` }"
              @click="router.push('/trips/new')" role="button" aria-label="规划一场说走就走的旅行"></div>
          </template>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 最近花费 + 最近游记 ===== -->
    <el-row :gutter="16" class="section">
      <el-col :span="12">
        <el-card shadow="never" class="panel">
          <div class="section-head">
            <span class="head-icon head-amber">💰</span>
            <span class="head-title">最近花费</span>
          </div>
          <div v-if="!data?.recentExpenses.length" class="empty-block">
            <div class="empty-emoji">🧾</div>
            <p class="empty-text">暂无花费记录，旅途中的每一笔都值得记下</p>
          </div>
          <div v-for="expense in data?.recentExpenses" :key="expense.id" class="line-row">
            <span class="line-main">{{ expense.title }} <el-tag size="small" round>{{ expense.tripTitle }}</el-tag></span>
            <span class="amount">¥{{ expense.amount }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="panel">
          <div class="section-head">
            <span class="head-icon head-purple">📖</span>
            <span class="head-title">最近游记</span>
          </div>
          <div v-if="!data?.recentJournals.length" class="empty-block">
            <img :src="journalEmptyImg" class="empty-illustration" alt="暂无游记" />
            <p class="empty-text">暂无游记，记录旅途中的美好瞬间</p>
            <el-button type="primary" round size="small" plain @click="router.push('/trips')">去写游记</el-button>
          </div>
          <div v-for="journal in data?.recentJournals" :key="journal.id" class="line-row">
            <RouterLink :to="`/trips/${journal.tripId}/journals/${journal.id}`" class="line-main link">
              {{ journal.title }}
            </RouterLink>
            <span class="muted">{{ journal.tripTitle }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ===== 最近照片 + 到期文档 ===== -->
    <el-row :gutter="16" class="section">
      <el-col :span="12">
        <el-card shadow="never" class="panel">
          <div class="section-head">
            <span class="head-icon head-green">📷</span>
            <span class="head-title">最近照片</span>
            <RouterLink v-if="data?.recentPhotos.length" to="/trips" class="head-link">查看全部 →</RouterLink>
          </div>
          <div v-if="!data?.recentPhotos.length" class="photo-empty">
            <div class="photo-grid dimmed">
              <img v-for="(src, index) in samplePhotos" :key="index" :src="src" class="photo-thumb" alt="" />
            </div>
            <div class="photo-empty-tip">
              <p>还没有照片</p>
              <span>上传旅途中的美好瞬间吧</span>
            </div>
          </div>
          <div v-else class="photo-grid">
            <img v-for="photo in data.recentPhotos" :key="photo.id" :src="photo.thumbnailUrl"
              :alt="photo.alt" class="photo-thumb" />
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" class="panel">
          <div class="section-head">
            <span class="head-icon head-coral">📄</span>
            <span class="head-title">即将到期的文档</span>
          </div>
          <div v-if="!data?.upcomingDocuments.length" class="empty-block">
            <img :src="documentsEmptyImg" class="empty-illustration small" alt="暂无到期提醒" />
            <p class="empty-text">暂无到期提醒，一切尽在掌握 👌</p>
          </div>
          <div v-for="doc in data?.upcomingDocuments" :key="doc.id" class="line-row">
            <span class="line-main">{{ doc.title }} <el-tag size="small" round>{{ doc.tripTitle }}</el-tag></span>
            <span class="muted">{{ formatDate(doc.expiredAt) }} 到期</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Location, Compass, ArrowRight } from '@element-plus/icons-vue';
import { dashboardApi } from '../api';
import { useAuthStore } from '../stores/auth';
import type { DashboardData, Trip } from '../api/types';

import heroImg from '../assets/images/01_hero_clean.jpg';
import upcomingImg from '../assets/images/02_upcoming_clean.jpg';
import sceneryImg from '../assets/images/03_recent_trip_scenery.jpg';
import itineraryEmptyImg from '../assets/images/08_itinerary_empty_illustration.jpg';
import journalEmptyImg from '../assets/images/09_journal_empty_illustration.jpg';
import documentsEmptyImg from '../assets/images/10_documents_empty_illustration.jpg';
import photoMountain from '../assets/images/04_photo_mountain_lake.jpg';
import photoSunset from '../assets/images/05_photo_sunset_coast.jpg';
import photoBlueCoast from '../assets/images/06_photo_blue_coast.jpg';
import photoAutumn from '../assets/images/07_photo_autumn_lake.jpg';

const samplePhotos = [photoMountain, photoSunset, photoBlueCoast, photoAutumn];

const auth = useAuthStore();
const router = useRouter();
const loading = ref(false);
const data = ref<DashboardData | null>(null);

const greeting = computed(() => {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 12) return '早上好';
  if (hour < 18) return '下午好';
  return '晚上好';
});

const todayText = computed(() => {
  const now = new Date();
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  return `${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`;
});

const stats = computed(() => {
  const s = data.value?.stats;
  return [
    {
      key: 'trips',
      icon: '🧳',
      label: '旅行次数',
      display: String(s?.tripCount ?? 0),
      unit: '次',
      foot: '包含所有未删除行程',
    },
    {
      key: 'expense',
      icon: '💰',
      label: '累计花费',
      display: Number(s?.totalExpenseAmount ?? 0).toLocaleString('zh-CN', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }),
      unit: '¥',
      foot: '仅统计 CNY 花费',
    },
    {
      key: 'days',
      icon: '📅',
      label: '累计天数',
      display: String(s?.totalDays ?? 0),
      unit: '天',
      foot: '按行程日期区间累计',
    },
    {
      key: 'packing',
      icon: '🎒',
      label: '待打包物品',
      display: String(s?.pendingPackingItemCount ?? 0),
      unit: '件',
      foot: (s?.pendingPackingItemCount ?? 0) > 0 ? '记得出发前清点行囊' : '行囊已就绪 🎉',
    },
  ];
});

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    draft: '草稿', planning: '规划中', active: '进行中', completed: '已完成', archived: '已归档',
  };
  return labels[status] ?? status;
}

function tripDays(trip: Trip) {
  if (!trip.startDate || !trip.endDate) {
    return '?';
  }
  const diff = Math.floor(
    (new Date(trip.endDate).getTime() - new Date(trip.startDate).getTime()) / 86_400_000) + 1;
  return Math.max(diff, 0);
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '未设置';
}

onMounted(async () => {
  loading.value = true;
  try {
    data.value = await dashboardApi.get();
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
/* ===== Hero ===== */
.hero {
  position: relative;
  height: 240px;
  border-radius: 18px;
  overflow: hidden;
  box-shadow: 0 10px 28px rgba(43, 84, 128, 0.2);
}
.hero-bg {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
}
.hero-mask {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(15, 45, 78, 0.52) 0%, rgba(15, 45, 78, 0.2) 40%, rgba(15, 45, 78, 0) 65%);
}
.hero-content {
  position: relative;
  z-index: 1;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 40px;
}
.hero-left h1 {
  margin: 0 0 8px;
  font-size: 28px;
  color: #fff;
  text-shadow: 0 2px 8px rgba(16, 42, 72, 0.45);
}
.hero-sub {
  margin: 0;
  color: rgba(255, 255, 255, 0.94);
  font-size: 14px;
  text-shadow: 0 1px 4px rgba(16, 42, 72, 0.45);
}
.hero-slogan {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  font-family: 'Kaiti SC', 'STKaiti', 'KaiTi', serif;
  font-size: 30px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 6px;
  text-shadow: 0 2px 10px rgba(16, 42, 72, 0.5), 0 0 24px rgba(120, 190, 240, 0.4);
  padding-right: 5%;
}
.slogan-line:last-child {
  margin-left: 3em;
}

/* ===== 粉彩统计卡 ===== */
.stat-grid { margin-top: 16px; }
.stat-grid .el-col { margin-bottom: 8px; }
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  border-radius: 14px;
  padding: 16px 18px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.stat-card:hover {
  transform: translateY(-3px);
  box-shadow: 0 10px 24px rgba(64, 92, 128, 0.14);
}
.stat-trips { background: linear-gradient(135deg, #e9f8ee, #d2efdd); }
.stat-expense { background: linear-gradient(135deg, #e9f2fd, #d3e7fa); }
.stat-days { background: linear-gradient(135deg, #fdf5e2, #f8e9c4); }
.stat-packing { background: linear-gradient(135deg, #fdeff2, #f9dde4); }
.stat-icon {
  flex: none;
  width: 46px;
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.85);
  box-shadow: 0 3px 8px rgba(64, 92, 128, 0.12);
}
.stat-body { min-width: 0; }
.stat-label { font-size: 13px; color: #5b6b7c; }
.stat-value { display: flex; align-items: baseline; gap: 4px; }
.stat-value .number {
  font-size: 26px;
  font-weight: 700;
  color: #22384e;
  font-variant-numeric: tabular-nums;
  word-break: break-all;
}
.stat-value .unit { font-size: 13px; color: #6b7c8d; }
.stat-foot { font-size: 12px; color: #8a99a8; margin-top: 2px; }

/* ===== 通用面板 ===== */
.section { margin-top: 16px; }
.panel {
  border-radius: 14px;
  border: none;
  box-shadow: 0 4px 16px rgba(64, 92, 128, 0.08);
}
.panel :deep(.el-card__header) { padding: 14px 18px 6px; border-bottom: none; }
.panel :deep(.el-card__body) { padding: 4px 18px 16px; }
.section-head { display: flex; align-items: center; gap: 8px; }
.head-icon {
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-size: 15px;
}
.head-blue { background: #e7f0fb; }
.head-amber { background: #faf1de; }
.head-purple { background: #efeafb; }
.head-green { background: #e6f4ec; }
.head-coral { background: #fcece4; }
.head-title { font-size: 16px; font-weight: 600; color: #22384e; }
.head-link {
  margin-left: auto;
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}

/* ===== 最近旅行行卡 ===== */
.trip-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 4px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.trip-row:hover { background: #f3f7fb; }
.trip-thumb {
  flex: none;
  width: 88px;
  height: 66px;
  border-radius: 10px;
  background-size: cover;
  background-position: center;
  display: flex;
  align-items: center;
  justify-content: center;
}
.thumb-text {
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 2px;
  text-shadow: 0 1px 6px rgba(16, 42, 72, 0.55);
}
.trip-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.trip-title { display: flex; align-items: center; gap: 8px; }
.trip-title .name {
  font-weight: 600;
  font-size: 15px;
  color: #22384e;
  display: inline-flex;
  align-items: center;
  gap: 2px;
}
.pin { color: var(--el-color-primary); }
.fav { font-size: 14px; }
.trip-arrow { color: #b6c2cf; }

/* ===== 即将出发 ===== */
.promo-card { overflow: hidden; }
.promo-list { padding: 8px 0 4px; display: flex; flex-direction: column; gap: 8px; }
.promo-trip {
  padding: 10px 12px;
  border-radius: 10px;
  background: #e9f5ef;
  border: 1px solid #d5eade;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: #22384e;
}
.promo-trip:hover { background: #dcefe5; }
.promo-date { font-size: 12px; color: #8a99a8; }
.promo-photo {
  height: 200px;
  border-radius: 12px;
  background-size: cover;
  background-position: center;
  cursor: pointer;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.promo-photo:hover {
  transform: scale(1.01);
  box-shadow: 0 8px 22px rgba(43, 84, 128, 0.25);
}

/* ===== 行列表与空状态 ===== */
.line-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 2px;
  border-bottom: 1px solid #eef2f6;
}
.line-row:last-child { border-bottom: none; }
.line-main { color: #33475c; font-size: 14px; }
.line-main.link { color: var(--el-color-primary); text-decoration: none; }
.amount { font-weight: 700; color: #2f8f5f; }
.muted { color: #8a99a8; font-size: 13px; }
.empty-block {
  padding: 18px 0 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.empty-illustration { width: 150px; border-radius: 10px; }
.empty-illustration.small { width: 92px; }
.empty-text {
  margin: 0;
  text-align: center;
  color: #8a99a8;
  font-size: 13px;
  line-height: 1.7;
}
.empty-emoji {
  width: 64px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  border-radius: 50%;
  background: #f1f6fb;
}

/* ===== 照片 ===== */
.photo-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  padding: 6px 0 2px;
}
.photo-thumb {
  width: 100%;
  height: 92px;
  object-fit: cover;
  border-radius: 10px;
  transition: transform 0.2s ease;
}
.photo-thumb:hover { transform: scale(1.04); }
.photo-empty { position: relative; padding: 6px 0 2px; }
.photo-empty .dimmed { filter: grayscale(0.35) opacity(0.35); }
.photo-empty-tip {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.photo-empty-tip p { margin: 0; font-weight: 600; color: #33475c; }
.photo-empty-tip span { font-size: 13px; color: #8a99a8; }
</style>
