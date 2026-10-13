<template>
  <div>
    <h2>账号设置</h2>
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never" class="panel">
          <template #header><span class="panel-title">👤 账户信息</span></template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">{{ auth.user?.username }}</el-descriptions-item>
            <el-descriptions-item label="昵称">{{ auth.user?.displayName }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" type="success">正常</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="注册时间">{{ formatDate(auth.user?.createdAt) }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never" class="panel danger-panel">
          <template #header><span class="panel-title danger">⚠️ 危险操作</span></template>
          <p class="danger-tip">
            注销账户后数据将保留 30 天，期间无法登录；30 天后账户及旅行数据将被永久删除。
          </p>
          <el-form label-width="80px" style="max-width: 420px">
            <el-form-item label="登录密码" required>
              <el-input v-model="password" type="password" placeholder="请输入当前密码以确认注销"
                show-password autocomplete="current-password" />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="confirmed">我已了解注销后果，30 天后数据将被永久删除</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button type="danger" :disabled="!password || !confirmed" :loading="loading"
                @click="showConfirm">注销账户</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { authApi, errorMessage } from '../api';
import { useAuthStore } from '../stores/auth';

const auth = useAuthStore();
const router = useRouter();
const password = ref('');
const confirmed = ref(false);
const loading = ref(false);

async function showConfirm() {
  await ElMessageBox.confirm(
    '确定要注销账户吗？注销后将立即退出登录，且无法恢复（30 天后数据永久删除）。',
    '注销确认',
    { type: 'warning', confirmButtonText: '确定注销', cancelButtonText: '再想想' },
  );
  await deactivate();
}

async function deactivate() {
  loading.value = true;
  try {
    await authApi.deactivate(password.value);
    auth.clearAfterDeactivate();
    ElMessage.success('账户已注销，感谢使用 Travel OS');
    router.push('/login');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—';
}
</script>

<style scoped>
.panel { border-radius: 14px; border: none; box-shadow: 0 4px 16px rgba(64, 92, 128, 0.08); }
.panel-title { font-weight: 600; color: #22384e; }
.panel-title.danger { color: var(--el-color-danger); }
.danger-panel { border: 1px solid #fbdede; }
.danger-tip {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdf1f1;
  color: #b04a3c;
  font-size: 13px;
  line-height: 1.7;
}
</style>
