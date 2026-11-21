/* Travel OS · 全站公共 JS（API 客户端 + 登录态管理）
 * - 纯静态站点，无框架；所有 fetch 调用都通过 apiGet / apiPost / apiPut / apiPatch / apiDelete
 * - 登录成功后从响应 JSON 取 token 存到 localStorage
 * - 后续请求自动带 Authorization: Bearer <token>
 */

const TOKEN_KEY  = 'travelos.token';
const USER_KEY   = 'travelos.user';
const API_BASE   = '/api';        // 由静态服务器或 nginx 反代到 Spring 3001
const SESSION_KEY = 'travelos.cookieSession'; // 本标签页内已确认 Cookie 会话就绪（防重复调用）

/** 读取当前 token（无则 null） */
function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

/** 写入 token + 用户信息（登录、注册成功后调用） */
function setAuth(token, user) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  if (user)  localStorage.setItem(USER_KEY, JSON.stringify(user));
  // 登录/注册响应同时下发了 HttpOnly Cookie（后端 AuthController），本轮会话无需再补建
  if (token && token !== 'cookie') {
    try { sessionStorage.setItem(SESSION_KEY, '1'); } catch (_) { /* 忽略隐私模式 */ }
  }
}

/** 清除登录态（登出/注销） */
function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  try { sessionStorage.removeItem(SESSION_KEY); } catch (_) { /* 忽略隐私模式 */ }
}

/** 当前登录用户（无则 null） */
function getCurrentUser() {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

/** 是否已登录 */
function isLoggedIn() {
  return !!getToken();
}

/** 通用请求封装：自动带 Authorization + 统一错误返回 */
async function apiFetch(path, { method = 'GET', body, headers = {} } = {}) {
  const token = getToken();
  const opts = {
    method,
    // 同源请求携带 Cookie：登录/注册的 Set-Cookie 需要浏览器保存，图片/文档/通知
    // SSE 也依赖同源 Cookie 通道（img/iframe/EventSource 无法设置 Authorization 头）
    credentials: 'same-origin',
    headers: {
      'Accept': 'application/json',
      ...(token ? { 'Authorization': 'Bearer ' + token } : {}),
      ...headers,
    },
  };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(API_BASE + path, opts);
  } catch (networkError) {
    const err = new Error('连不上服务器，请确认后端是否启动');
    err.kind = 'network';
    throw err;
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch (_) { data = text; }
  }
  if (!res.ok) {
    const err = new Error((data && (data.message || data.error)) || ('HTTP ' + res.status));
    err.kind = 'http';
    err.status = res.status;
    err.data = data;
    // 仅 401（未认证/凭证失效）清登录态；403 表示已登录但无权限（如攻略可见性、
    // 账本好友规则），清凭证反而会把用户踢回登录页
    if (res.status === 401) {
      clearAuth();
    }
    throw err;
  }
  return data;
}

const apiGet    = (p)       => apiFetch(p, { method: 'GET' });
const apiPost   = (p, body) => apiFetch(p, { method: 'POST',   body });
const apiPut    = (p, body) => apiFetch(p, { method: 'PUT',    body });
const apiPatch  = (p, body) => apiFetch(p, { method: 'PATCH',  body });
const apiDelete = (p)       => apiFetch(p, { method: 'DELETE' });

/** 把 API 错误转换成用户能看懂的中文短句 */
function humanError(err) {
  if (!err) return '未知错误';
  if (err.kind === 'network') return err.message || '连不上服务器';
  if (err.kind === 'http') {
    // 后端常用：用户不存在 / 密码错误 / 已被注册 等
    return err.message || ('请求失败 (' + err.status + ')');
  }
  return String(err.message || err);
}

/**
 * 确保 Web 端存在可用的同源 Cookie 会话（img/iframe/EventSource 的鉴权通道）。
 *
 * 背景：修复发布前登录的用户只有 localStorage 里的 Bearer Token，浏览器里没有
 * HttpOnly Cookie——普通 API 正常，但 /files/** 与通知 SSE 持续 401。
 * 这里在受保护页面初始化时调用 POST /api/auth/session（带 Bearer），后端校验通过后
 * 用同一令牌重设 HttpOnly Cookie，不签发新 Token。
 *
 * 返回 true = Cookie 会话已就绪（或无需处理，如 'cookie' 占位登录态）；
 * 返回 false = 建立 failed（令牌无效被 401 → 已清理本地登录态；或网络故障）。
 * 幂等：本轮标签页内成功过一次即短路（sessionStorage 标记）；并发调用共享同一请求。
 */
let ensureSessionInFlight = null;
async function ensureSession() {
  const token = getToken();
  if (!token) return false;
  if (token === 'cookie') return true; // 占位登录态：Cookie 由后端校验，失败走统一 401 流程
  try {
    if (sessionStorage.getItem(SESSION_KEY) === '1') return true;
  } catch (_) { /* 隐私模式下 sessionStorage 可能不可用，继续请求 */ }
  if (ensureSessionInFlight) return ensureSessionInFlight;
  ensureSessionInFlight = (async function () {
    try {
      const res = await fetch(API_BASE + '/auth/session', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Authorization': 'Bearer ' + token },
      });
      if (res.ok) {
        try { sessionStorage.setItem(SESSION_KEY, '1'); } catch (_) { /* 忽略 */ }
        return true;
      }
      if (res.status === 401) {
        // 令牌已失效（改密/注销/禁用/过期）：与服务端同规则，清理本地登录态
        clearAuth();
      }
      return false;
    } catch (_) {
      return false; // 网络故障：不误清登录态，Cookie 未就绪由各链路自行降级
    } finally {
      ensureSessionInFlight = null;
    }
  })();
  return ensureSessionInFlight;
}

/** 需要登录态的页面：顶部调用一次，未登录跳登录页 */
function requireLogin(loginUrl = '/login/') {
  if (!isLoggedIn()) {
    const here = location.pathname + location.search;
    sessionStorage.setItem('travelos.redirect', here);
    location.replace(loginUrl);
    return false;
  }
  // 已登录：异步补建 Cookie 会话（不阻塞页面渲染）；文件/SSE 页面需在其后 await 确认
  ensureSession();
  return true;
}

/** 登录成功后取出"原本要去"的页面跳转（搭配 requireLogin 使用） */
function consumeRedirect(defaultPath = '/home/') {
  const r = sessionStorage.getItem('travelos.redirect');
  if (r) {
    sessionStorage.removeItem('travelos.redirect');
    return r;
  }
  return defaultPath;
}

/**
 * 全站统一的退出登录（侧栏退出按钮 / 账户页共用，与登录鉴权逻辑一致）。
 * 流程：
 *   1. 未登录（无 token）：直接跳登录页，不调接口（边界情况）
 *   2. 已登录：调 POST /auth/logout 清服务端 Cookie，失败自动重试一次
 *   3. 无论接口成败都清除本地 token 与用户缓存（JWT 无状态，本地清理即完成登出）
 *   4. 重试仍失败时通过 onNotice 提示（如 toast），不阻塞跳转
 * @param {{redirectUrl?: string, onNotice?: (msg: string) => void}} opts
 */
async function performLogout(opts = {}) {
  const redirectUrl = opts.redirectUrl || '/login/';
  if (!isLoggedIn()) {
    location.replace(redirectUrl);
    return;
  }
  let serverOk = false;
  for (let attempt = 0; attempt < 2 && !serverOk; attempt++) {
    try {
      await apiPost('/auth/logout', {});
      serverOk = true;
    } catch (e) {
      if (attempt === 0) await new Promise(r => setTimeout(r, 600)); // 稍候重试一次
    }
  }
  clearAuth();
  if (!serverOk && typeof opts.onNotice === 'function') {
    opts.onNotice('云端登出未成功（网络原因），本地登录凭证已清除');
  }
  location.replace(redirectUrl);
}

/* 自动接线：为页面上的退出按钮绑定统一逻辑（#sideLogout 侧栏 / #btnLogout 账户页）。
 * 按钮只负责 DOM，行为全部收敛到这里，避免各页实现漂移。 */
(function () {
  function wireLogoutButtons() {
    ['sideLogout', 'btnLogout'].forEach(function (id) {
      var el = document.getElementById(id);
      if (!el || el.dataset.logoutBound) return;
      el.dataset.logoutBound = '1';
      el.addEventListener('click', function () {
        if (!confirm('退出当前账户？需要重新登录才能继续使用。')) return;
        performLogout({
          onNotice: function (msg) {
            if (typeof showToast === 'function') showToast(msg);
          },
        });
      });
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', wireLogoutButtons);
  } else {
    wireLogoutButtons();
  }
})();