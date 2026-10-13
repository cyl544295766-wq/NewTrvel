import { defineStore } from 'pinia';
import { authApi } from '../api';
import type { PublicUser } from '../api/types';

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as PublicUser | null,
    initialized: false,
  }),
  getters: {
    isLoggedIn: (state) => state.user !== null,
  },
  actions: {
    async init() {
      if (this.initialized) {
        return;
      }
      try {
        const result = await authApi.me();
        this.user = result.user;
      } catch {
        this.user = null;
      } finally {
        this.initialized = true;
      }
    },
    async login(username: string, password: string) {
      const result = await authApi.login(username, password);
      this.user = result.user;
    },
    async register(username: string, password: string) {
      const result = await authApi.register(username, password);
      this.user = result.user;
    },
    /** 注销账户后清空本地登录态（服务端已清 Cookie）。 */
    clearAfterDeactivate() {
      this.user = null;
    },
    async logout() {
      try {
        await authApi.logout();
      } finally {
        this.user = null;
      }
    },
  },
});
