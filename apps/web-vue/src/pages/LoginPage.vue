<template>
  <div class="auth-page">
    <!-- 左侧旅行意象区 -->
    <div class="auth-visual">
      <div class="visual-bg" :style="{ backgroundImage: `url(${heroImg})` }"></div>
      <div class="visual-mask"></div>
      <div class="visual-content">
        <div class="visual-logo">🧭 Travel OS</div>
        <div class="visual-slogan">
          <p class="slogan-main">行走在路上</p>
          <p class="slogan-sub">遇见更大的世界 ✈</p>
        </div>
        <p class="visual-foot">长期旅行管理 · 行程 / 费用 / 游记 / 地图</p>
      </div>
    </div>

    <!-- 右侧表单卡片 -->
    <div class="auth-panel">
      <div class="auth-card">
        <h1 class="auth-title">{{ mode === 'login' ? '欢迎回来' : '开启你的旅程' }}</h1>
        <p class="auth-subtitle">{{ mode === 'login' ? '登录你的 Travel OS 账户' : '只需用户名和密码，立即出发' }}</p>

        <div class="mode-tabs">
          <button class="mode-tab" :class="{ active: mode === 'login' }" type="button"
            @click="switchMode('login')">登录</button>
          <button class="mode-tab" :class="{ active: mode === 'register' }" type="button"
            @click="switchMode('register')">注册</button>
          <span class="tab-indicator" :class="mode"></span>
        </div>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large"
          @keyup.enter="submit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名（3-20 位字母/数字/下划线）"
              autocomplete="username" clearable>
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码"
              autocomplete="current-password" show-password>
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="mode === 'register'" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码"
              autocomplete="new-password" show-password>
              <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
          </el-form-item>

          <el-button type="primary" class="submit-button" size="large" round
            :loading="loading" @click="submit">
            {{ mode === 'login' ? '登 录' : '注 册 并 登 录' }}
          </el-button>
        </el-form>

        <p class="auth-switch-tip">
          {{ mode === 'login' ? '还没有账户？' : '已有账户？' }}
          <a class="switch-link" @click.prevent="switchMode(mode === 'login' ? 'register' : 'login')">
            {{ mode === 'login' ? '立即注册' : '直接登录' }}
          </a>
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, FormInstance, FormRules } from 'element-plus';
import { User, Lock, Key } from '@element-plus/icons-vue';
import { consumeRedirect, errorMessage } from '../api/http';
import { useAuthStore } from '../stores/auth';
import heroImg from '../assets/images/01_hero_clean.jpg';

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const formRef = ref<FormInstance>();
const loading = ref(false);
const mode = ref<'login' | 'register'>((route.query.mode as 'register') === 'register' ? 'register' : 'login');

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
});

const rules = computed<FormRules>(() => ({
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9_]{3,20}$/,
      message: '用户名须为 3-20 位字母、数字或下划线',
      trigger: ['blur', 'change'],
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    ...(mode.value === 'register'
      ? [{
          validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
            if (!value || value.length < 8) {
              callback(new Error('密码至少 8 位'));
            } else if (!/[A-Za-z]/.test(value) || !/\d/.test(value)) {
              callback(new Error('密码须同时包含字母和数字'));
            } else {
              callback();
            }
          },
          trigger: ['blur', 'change'],
        }]
      : []),
  ],
  confirmPassword: mode.value === 'register'
    ? [{
        validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
          if (!value) {
            callback(new Error('请再次输入密码'));
          } else if (value !== form.password) {
            callback(new Error('两次输入的密码不一致'));
          } else {
            callback();
          }
        },
        trigger: ['blur', 'change'],
      }]
    : [],
}));

function switchMode(next: 'login' | 'register') {
  mode.value = next;
  formRef.value?.clearValidate();
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  loading.value = true;
  try {
    if (mode.value === 'login') {
      await auth.login(form.username.trim(), form.password);
      ElMessage.success('登录成功，欢迎回来');
    } else {
      await auth.register(form.username.trim(), form.password);
      ElMessage.success('注册成功，已自动登录');
    }
    const redirect = consumeRedirect();
    router.push(redirect ?? '/');
  } catch (error) {
    ElMessage.error(errorMessage(error));
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
}

/* ===== 左侧视觉区 ===== */
.auth-visual {
  position: relative;
  flex: 1.1;
  overflow: hidden;
}
.visual-bg {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
}
.visual-mask {
  position: absolute;
  inset: 0;
  background: linear-gradient(160deg, rgba(15, 45, 78, 0.62) 0%, rgba(15, 45, 78, 0.28) 55%, rgba(47, 125, 91, 0.35) 100%);
}
.visual-content {
  position: relative;
  z-index: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 44px 48px;
}
.visual-logo {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  text-shadow: 0 2px 8px rgba(16, 42, 72, 0.5);
}
.visual-slogan {
  font-family: 'Kaiti SC', 'STKaiti', 'KaiTi', serif;
  color: #fff;
  text-shadow: 0 2px 12px rgba(16, 42, 72, 0.5);
}
.slogan-main {
  margin: 0;
  font-size: 44px;
  letter-spacing: 8px;
}
.slogan-sub {
  margin: 8px 0 0;
  font-size: 26px;
  letter-spacing: 5px;
  opacity: 0.92;
}
.visual-foot {
  margin: 0;
  color: rgba(255, 255, 255, 0.85);
  font-size: 13px;
  letter-spacing: 1px;
}

/* ===== 右侧表单 ===== */
.auth-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #f7faf8 0%, #eef4f2 100%);
  padding: 40px 24px;
}
.auth-card {
  width: 100%;
  max-width: 420px;
  background: #fff;
  border-radius: 18px;
  padding: 36px 34px 28px;
  box-shadow: 0 12px 40px rgba(35, 66, 54, 0.12);
}
.auth-title {
  margin: 0 0 4px;
  font-size: 24px;
  color: #22384e;
}
.auth-subtitle {
  margin: 0 0 20px;
  color: #8a99a8;
  font-size: 14px;
}

/* Tab 切换 */
.mode-tabs {
  position: relative;
  display: flex;
  background: #f0f4f2;
  border-radius: 999px;
  padding: 4px;
  margin-bottom: 22px;
}
.mode-tab {
  flex: 1;
  z-index: 1;
  border: none;
  background: transparent;
  padding: 9px 0;
  border-radius: 999px;
  font-size: 14px;
  color: #6b7c8d;
  cursor: pointer;
  transition: color 0.2s ease;
}
.mode-tab.active {
  color: #fff;
  font-weight: 600;
}
.tab-indicator {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 4px;
  width: calc(50% - 4px);
  border-radius: 999px;
  background: linear-gradient(135deg, #2f8f5f, #46a37a);
  transition: transform 0.25s ease;
}
.tab-indicator.register {
  transform: translateX(100%);
}

/* 输入框定制 */
:deep(.el-input__wrapper) {
  border-radius: 10px;
  padding: 4px 14px;
  box-shadow: 0 0 0 1px #dfe7e3 inset;
  transition: box-shadow 0.2s ease;
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #2f8f5f inset;
}
:deep(.el-form-item) { margin-bottom: 18px; }

.submit-button {
  width: 100%;
  margin-top: 4px;
  background: linear-gradient(135deg, #2f8f5f, #46a37a);
  border: none;
  letter-spacing: 4px;
  font-weight: 600;
}
.submit-button:hover {
  background: linear-gradient(135deg, #2a8055, #3d9370);
}

.auth-switch-tip {
  margin: 18px 0 0;
  text-align: center;
  color: #8a99a8;
  font-size: 13px;
}
.switch-link {
  color: #2f8f5f;
  font-weight: 600;
  cursor: pointer;
}

/* ===== 移动端单列 ===== */
@media (max-width: 768px) {
  .auth-visual {
    display: none;
  }
  .auth-page {
    background: linear-gradient(180deg, #f7faf8 0%, #eef4f2 100%);
  }
  .auth-card {
    box-shadow: 0 6px 20px rgba(35, 66, 54, 0.1);
  }
}
</style>
