<template>
  <div v-loading="loading">
    <h2>旅行统计</h2>

    <el-row :gutter="16" class="stat-grid">
      <el-col :span="4"><el-card><el-statistic title="旅行次数" :value="overview?.tripCount ?? 0" /></el-card></el-col>
      <el-col :span="4"><el-card><el-statistic title="累计天数" :value="overview?.totalDays ?? 0" /></el-card></el-col>
      <el-col :span="4"><el-card><el-statistic title="总花费" :value="Number(overview?.totalExpenseAmount ?? 0)" :precision="2" prefix="¥" /></el-card></el-col>
      <el-col :span="4"><el-card><el-statistic title="目的地数" :value="overview?.destinationCount ?? 0" /></el-card></el-col>
      <el-col :span="4"><el-card><el-statistic title="平均天数" :value="overview?.averageTripDays ?? 0" /></el-card></el-col>
      <el-col :span="4"><el-card><el-statistic title="最爱目的地" :value="overview?.mostVisitedDestination.label ?? '—'" /></el-card></el-col>
    </el-row>

    <el-row :gutter="16" class="section">
      <el-col :span="12">
        <el-card>
          <template #header>年度对比</template>
          <div ref="yearlyChart" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>月度分布（{{ selectedYear }}）</template>
          <div class="year-picker">
            <el-select v-model="selectedYear" style="width: 120px" @change="loadMonthly">
              <el-option v-for="year in overview?.availableYears ?? []" :key="year" :label="year + '年'" :value="year" />
            </el-select>
          </div>
          <div ref="monthlyChart" class="chart"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="section">
      <el-col :span="12">
        <el-card>
          <template #header>费用分类占比</template>
          <div ref="categoryChart" class="chart"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>年度明细</template>
          <el-table :data="overview?.yearlyComparison ?? []" size="small" border>
            <el-table-column prop="year" label="年份" width="90" />
            <el-table-column prop="tripCount" label="旅行" width="80" />
            <el-table-column prop="totalDays" label="天数" width="80" />
            <el-table-column label="花费">
              <template #default="{ row }">¥{{ row.totalExpenseAmount }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts';
import { onMounted, onUnmounted, ref } from 'vue';
import { statsApi } from '../api';

interface Overview {
  tripCount: number;
  totalDays: number;
  totalExpenseAmount: string;
  destinationCount: number;
  averageTripDays: number;
  mostVisitedDestination: { label: string | null; count: number };
  availableYears: number[];
  yearlyComparison: Array<{ year: number; tripCount: number; totalDays: number; totalExpenseAmount: string }>;
}

interface MonthlyData {
  months: Array<{ month: number; label: string; tripCount: number; totalDays: number; totalExpenseAmount: string }>;
}

interface CategoryData {
  categories: Array<{ category: string; label: string; amount: string; percentage: number }>;
}

const loading = ref(false);
const overview = ref<Overview | null>(null);
const selectedYear = ref<number>(new Date().getFullYear());
const yearlyChart = ref<HTMLElement | null>(null);
const monthlyChart = ref<HTMLElement | null>(null);
const categoryChart = ref<HTMLElement | null>(null);
const charts: echarts.ECharts[] = [];

async function loadOverview() {
  overview.value = (await statsApi.overview()) as unknown as Overview;
  if (overview.value.availableYears.length > 0) {
    selectedYear.value = overview.value.availableYears[0];
  }
  if (yearlyChart.value) {
    const chart = echarts.init(yearlyChart.value);
    charts.push(chart);
    chart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['旅行次数', '天数', '花费'] },
      xAxis: { type: 'category', data: overview.value.yearlyComparison.map(item => item.year + '年') },
      yAxis: [{ type: 'value' }, { type: 'value' }],
      series: [
        { name: '旅行次数', type: 'bar', data: overview.value.yearlyComparison.map(item => item.tripCount) },
        { name: '天数', type: 'bar', data: overview.value.yearlyComparison.map(item => item.totalDays) },
        {
          name: '花费', type: 'line', yAxisIndex: 1,
          data: overview.value.yearlyComparison.map(item => Number(item.totalExpenseAmount)),
        },
      ],
    });
  }
  if (categoryChart.value) {
    const categories = ((await statsApi.expenseCategories()) as unknown as CategoryData).categories
      .filter(item => Number(item.amount) > 0);
    const chart = echarts.init(categoryChart.value);
    charts.push(chart);
    chart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: ¥{c} ({d}%)' },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        data: categories.map(item => ({ name: item.label, value: Number(item.amount) })),
      }],
    });
  }
}

async function loadMonthly() {
  if (!monthlyChart.value) {
    return;
  }
  const data = (await statsApi.monthly(selectedYear.value)) as unknown as MonthlyData;
  const chart = echarts.init(monthlyChart.value);
  charts.push(chart);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.months.map(month => month.label) },
    yAxis: [{ type: 'value' }, { type: 'value' }],
    series: [
      { name: '旅行次数', type: 'bar', data: data.months.map(month => month.tripCount) },
      {
        name: '花费', type: 'line', yAxisIndex: 1,
        data: data.months.map(month => Number(month.totalExpenseAmount)),
      },
    ],
  });
}

onMounted(async () => {
  loading.value = true;
  try {
    await loadOverview();
    await loadMonthly();
  } finally {
    loading.value = false;
  }
});

onUnmounted(() => {
  for (const chart of charts) {
    chart.dispose();
  }
});
</script>

<style scoped>
.stat-grid { margin-bottom: 8px; }
.section { margin-top: 16px; }
.chart { height: 300px; }
.year-picker { margin-bottom: 8px; }
</style>
