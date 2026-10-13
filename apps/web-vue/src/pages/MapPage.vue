<template>
  <div>
    <el-page-header @back="router.push(`/trips/${tripId}`)">
      <template #content><span class="title">行程地图 · {{ tripTitle }}</span></template>
    </el-page-header>
    <div ref="mapContainer" class="map-container">
      <div v-if="error" class="map-error">
        <p>{{ error }}</p>
        <p class="muted">请检查 VITE_AMAP_MAP_KEY 配置或网络连接。</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { tripsApi } from '../api';
import type { RouteDay } from '../api/types';

const route = useRoute();
const router = useRouter();
const tripId = route.params.tripId as string;
const tripTitle = ref('');
const mapContainer = ref<HTMLElement | null>(null);
const error = ref<string | null>(null);

let map: any = null;
let overlays: any[] = [];

function loadAmap(): Promise<any> {
  const win = window as any;
  if (win.AMap) return Promise.resolve(win.AMap);
  const key = import.meta.env.VITE_AMAP_MAP_KEY as string | undefined;
  const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE as string | undefined;
  if (!key) {
    return Promise.reject(new Error('未配置高德地图 JS API Key'));
  }
  if (securityCode) {
    win._AMapSecurityConfig = { securityJsCode: securityCode };
  }
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.onload = () => (win.AMap ? resolve(win.AMap) : reject(new Error('高德地图脚本加载失败')));
    script.onerror = () => reject(new Error('高德地图网络连接失败'));
    document.head.appendChild(script);
  });
}

const COLORS = ['#378ADD', '#639922', '#BA7517', '#993556', '#534AB7', '#D85A30'];

async function render() {
  const AMap = await loadAmap();
  map = new AMap.Map(mapContainer.value, {
    center: [104.0, 35.5],
    zoom: 4,
    resizeEnable: true,
  });
  const { route: routeDays } = await tripsApi.route(tripId);
  const points: [number, number][] = [];
  routeDays.forEach((day: RouteDay, dayIndex: number) => {
    const path: [number, number][] = [];
    day.places.forEach((place) => {
      const position: [number, number] = [Number(place.longitude), Number(place.latitude)];
      if (Number.isFinite(position[0]) && Number.isFinite(position[1])) {
        path.push(position);
        points.push(position);
        const marker = new AMap.Marker({
          position,
          title: place.name,
          content: `<div style="background:${COLORS[dayIndex % COLORS.length]};color:#fff;`
            + 'border-radius:10px;padding:1px 6px;font-size:12px;white-space:nowrap;">'
            + `${day.dayIndex}-${place.name}</div>`,
          extData: { placeId: place.id },
        });
        overlays.push(marker);
      }
    });
    if (path.length >= 2) {
      overlays.push(new AMap.Polyline({
        path,
        strokeColor: COLORS[dayIndex % COLORS.length],
        strokeWeight: 3,
        strokeOpacity: 0.85,
      }));
    }
  });
  map.add(overlays);
  if (points.length > 0) {
    map.setFitView(overlays, false, [60, 60, 60, 60]);
  }
}

onMounted(async () => {
  tripTitle.value = (await tripsApi.detail(tripId)).trip.title;
  try {
    await render();
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  }
});

onUnmounted(() => {
  map?.destroy?.();
});
</script>

<style scoped>
.title { font-weight: 600; }
.map-container {
  margin-top: 16px;
  height: calc(100vh - 190px);
  border-radius: 10px;
  overflow: hidden;
  background: var(--el-fill-color-light);
  position: relative;
}
.map-error {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
