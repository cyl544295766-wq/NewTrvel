/* Travel OS · 全站公共 JS（API 客户端 + 登录态管理）
 * - 纯静态站点，无框架；所有 fetch 调用都通过 apiGet / apiPost / apiPut / apiPatch / apiDelete
 * - 登录成功后从响应 JSON 取 token 存到 localStorage
 * - 后续请求自动带 Authorization: Bearer <token>
 */

const TOKEN_KEY  = 'travelos.token';
const USER_KEY   = 'travelos.user';
const API_BASE   = '/api';        // 由静态服务器或 nginx 反代到 Spring 3001

/** 读取当前 token（无则 null） */
function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

/** 写入 token + 用户信息（登录、注册成功后调用） */
function setAuth(token, user) {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  if (user)  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

/** 清除登录态（登出/注销） */
function clearAuth() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
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
    credentials: 'omit',          // 不再依赖 cookie，跨域更可控
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
    // 401/403 一律清登录态，让下一次请求能正确跳登录页
    if (res.status === 401 || res.status === 403) {
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

/** 需要登录态的页面：顶部调用一次，未登录跳登录页 */
function requireLogin(loginUrl = '/login/') {
  if (!isLoggedIn()) {
    const here = location.pathname + location.search;
    sessionStorage.setItem('travelos.redirect', here);
    location.replace(loginUrl);
    return false;
  }
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