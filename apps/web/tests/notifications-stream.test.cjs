/* Travel OS · 通知前端行为自动化测试（Node 内置 test + assert + vm，无框架）
 *
 * 加载方式：直接读取并执行真实文件，不复制逻辑。
 *   1. apps/web/assets/js/api.js（真实的登录态 / ensureSession / apiFetch）
 *   2. apps/web/notifications/index.html 的全部内联 <script>
 *      （含 requireLogin、boot、列表查询、SSE 订阅 subscribeLive）
 * 通过 vm 提供最小 DOM、localStorage/sessionStorage、fetch、EventSource、
 * location 与可控假定时器；不访问网络、不依赖数据库、不真实等待。
 *
 * 运行：node --test apps/web/tests/notifications-stream.test.cjs
 *
 * 用例对应需求：
 *   1  Cookie 就绪后才建流              → 1
 *   2  会话建立失败不立即建流/有限重试    → 2a 2b 2c 2d
 *   3  临时网络故障有限退避              → 3
 *   4  建连成功重置重连计数              → 4
 *   5  收到 unread 重置计数 + 合并刷新    → 5a 5b
 *   6  会话失效清理并跳登录              → 6a 6b 6c
 *   7  出错/页面销毁关闭与清理            → 7a 7b 7c
 *   8  并发连接与旧回调隔离              → 8a 8b
 *   9  列表 API 与 SSE 故障隔离          → 9a 9b 9c
 */
'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

/* ============================== 真实文件加载 ============================== */

const WEB_ROOT = path.join(__dirname, '..');
const API_PATH = path.join(WEB_ROOT, 'assets', 'js', 'api.js');
const PAGE_PATH = path.join(WEB_ROOT, 'notifications', 'index.html');

const API_SOURCE = fs.readFileSync(API_PATH, 'utf8');
const PAGE_HTML = fs.readFileSync(PAGE_PATH, 'utf8');

/** 提取页面内联 <script>（排除带 src 的外链脚本）。 */
function extractInlineScripts(html) {
  const scripts = [];
  const re = /<script(?![^>]*\bsrc\s*=)[^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  while ((match = re.exec(html)) !== null) {
    if (match[1].trim()) scripts.push(match[1]);
  }
  return scripts;
}

const PAGE_SCRIPTS = extractInlineScripts(PAGE_HTML);
const STREAM_SCRIPT = PAGE_SCRIPTS.find((s) => s.includes('subscribeLive'));

// 页面被重构导致找不到真实 SSE 逻辑时，测试应立即失败而不是“空跑通过”
assert.ok(STREAM_SCRIPT, 'notifications/index.html 应包含 subscribeLive 内联脚本');
assert.ok(/ensureSession/.test(STREAM_SCRIPT), '真实 SSE 脚本应调用 ensureSession');
assert.ok(/new EventSource\(['"]\/api\/notifications\/stream['"]\)/.test(STREAM_SCRIPT),
  '真实 SSE 脚本应使用固定的 /api/notifications/stream 地址');

/* ============================== 基础替身 ============================== */

const RETRY_DELAYS = new Set([1000, 2000, 4000, 8000]);

function createStorage(initial) {
  const map = new Map(Object.entries(initial || {}));
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => { map.set(k, String(v)); },
    removeItem: (k) => { map.delete(k); },
    clear: () => map.clear(),
    key: (i) => Array.from(map.keys())[i] ?? null,
    get length() { return map.size; },
    _map: map,
  };
}

function createElementStub(id) {
  const attrs = new Map();
  const listeners = new Map();
  const classes = new Set();
  return {
    id,
    textContent: '',
    innerHTML: '',
    value: '',
    disabled: false,
    hidden: false,
    style: {},
    dataset: {},
    classList: {
      add: (...names) => names.forEach((n) => classes.add(n)),
      remove: (...names) => names.forEach((n) => classes.delete(n)),
      contains: (n) => classes.has(n),
      toggle: (n) => { if (classes.has(n)) classes.delete(n); else classes.add(n); },
    },
    setAttribute(k, v) { attrs.set(k, String(v)); },
    getAttribute(k) { return attrs.has(k) ? attrs.get(k) : null; },
    removeAttribute(k) { attrs.delete(k); },
    hasAttribute(k) { return attrs.has(k); },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const list = listeners.get(type);
      if (!list) return;
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    },
    dispatch(type, event) {
      (listeners.get(type) || []).slice().forEach((fn) => fn(event));
    },
    click() {
      this.dispatch('click', { target: this, preventDefault() {}, stopPropagation() {} });
    },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    closest() { return null; },
    focus() {},
    _listeners: listeners,
    _attrs: attrs,
  };
}

/** 可控假定时器：tick(ms) 推进虚拟时钟并执行到期回调（不真实等待）。 */
function createTimers() {
  let now = 0;
  let seq = 0;
  const all = [];
  const asyncErrors = [];

  function setTimeoutFake(fn, delay, ...args) {
    const id = ++seq;
    const d = Number(delay) || 0;
    all.push({ id, fn, args, delay: d, at: now + d, cancelled: false, fired: false });
    return id;
  }

  function clearTimeoutFake(id) {
    const t = all.find((x) => x.id === id);
    if (t) t.cancelled = true;
  }

  function pending() {
    return all.filter((t) => !t.cancelled && !t.fired);
  }

  async function tick(ms) {
    const target = now + Math.max(0, Number(ms) || 0);
    for (let guard = 0; guard < 500; guard++) {
      await flush(3);
      const due = all
        .filter((t) => !t.cancelled && !t.fired && t.at <= target)
        .sort((a, b) => a.at - b.at || a.id - b.id)[0];
      if (!due) break;
      now = due.at;
      due.fired = true;
      const ret = due.fn(...due.args);
      if (ret && typeof ret.then === 'function') ret.catch((e) => asyncErrors.push(e));
    }
    now = target;
    await flush();
    return asyncErrors;
  }

  return { now: () => now, setTimeout: setTimeoutFake, clearTimeout: clearTimeoutFake, pending, tick, all };
}

/** 让所有已排队的 Promise 微任务/事件循环轮次跑完。 */
async function flush(turns = 6) {
  for (let i = 0; i < turns; i++) await new Promise((r) => setImmediate(r));
}

/* ============================== 环境组装 ============================== */

const DEFAULT_TOKEN = 'test-token-value-not-a-real-jwt';
const DEFAULT_USER = { id: 'u-test', username: 'tester', displayName: 'Tester', role: 'user' };

/**
 * 创建一个执行真实页面脚本的隔离环境。
 * options.routes 可覆盖各类请求的行为：(rec) => { status, body } | { network: true } | Promise
 *   session / trips / list / unreadCount / probe / other
 * 其中 probe = SSE 出错后前端用原始 fetch 探测 /api/notifications/unread-count（无 Accept 头）；
 * unreadCount = apiGet('/notifications/unread-count')（带 Accept 头）。
 */
async function createEnv(options = {}) {
  const token = options.token === undefined ? DEFAULT_TOKEN : options.token;
  const user = options.user === undefined ? DEFAULT_USER : options.user;
  const routes = options.routes || {};

  const localStorage = createStorage();
  if (token) localStorage.setItem('travelos.token', token);
  if (user) localStorage.setItem('travelos.user', JSON.stringify(user));
  const sessionStorage = createStorage();

  const timers = createTimers();
  const fetches = [];
  const streams = [];
  const pagehideHandlers = [];
  const replaced = [];
  let activeStreams = 0;
  let maxActiveStreams = 0;

  const locationStub = {
    pathname: '/notifications/',
    search: '',
    href: 'http://127.0.0.1/notifications/',
    replaced,
    replace(url) { replaced.push(String(url)); this.href = String(url); },
    assign(url) { replaced.push(String(url)); this.href = String(url); },
  };

  const elements = new Map();
  function getEl(id) {
    if (!elements.has(id)) elements.set(id, createElementStub(id));
    return elements.get(id);
  }

  const documentStub = {
    readyState: 'complete',
    documentElement: createElementStub('#document'),
    getElementById: getEl,
    querySelector(sel) {
      if (sel === '.shell') return getEl('__shell');
      if (sel === '.filters') return getEl('__filters');
      return null;
    },
    querySelectorAll() { return []; },
    addEventListener() {},
    createElement(tag) { return createElementStub(tag); },
  };

  function headersOf(rec) {
    return Object.keys(rec.headers || {}).map((k) => k.toLowerCase());
  }

  function kindOf(rec) {
    if (rec.url === '/api/auth/session') return 'session';
    if (rec.url.startsWith('/api/trips')) return 'trips';
    if (rec.url.startsWith('/api/notifications/unread-count')) {
      // apiGet 总会带 Accept；SSE 失效探测的原始 fetch 只带 Authorization
      return headersOf(rec).includes('accept') ? 'unreadCount' : 'probe';
    }
    if (rec.url === '/api/notifications' || rec.url.startsWith('/api/notifications?')) return 'list';
    return 'other';
  }

  const routeTable = {
    session: routes.session || (() => ({ status: 200, body: {} })),
    trips: routes.trips || (() => ({ status: 200, body: { trips: [] } })),
    list: routes.list || (() => ({ status: 200, body: { notifications: [], total: 0, totalPages: 1 } })),
    unreadCount: routes.unreadCount || (() => ({ status: 200, body: { count: 0 } })),
    probe: routes.probe || (() => ({ status: 200, body: { count: 0 } })),
    other: routes.other || (() => ({ status: 200, body: {} })),
  };

  async function fetchFake(url, opts = {}) {
    const rec = {
      url: String(url),
      method: String(opts.method || 'GET').toUpperCase(),
      headers: { ...(opts.headers || {}) },
      opts,
    };
    rec.kind = kindOf(rec);
    fetches.push(rec);

    const handler = routeTable[rec.kind] || routeTable.other;
    const spec = await handler(rec, env);

    if (spec && spec.network) {
      const err = new Error('simulated network failure');
      err.simulatedNetwork = true;
      throw err;
    }
    const status = spec && typeof spec.status === 'number' ? spec.status : 200;
    let body = spec && Object.prototype.hasOwnProperty.call(spec, 'body')
      ? spec.body
      : (status >= 400 ? { message: 'HTTP ' + status } : {});
    const text = body === undefined || body === null
      ? ''
      : (typeof body === 'string' ? body : JSON.stringify(body));
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => text,
      json: async () => (typeof body === 'string' ? JSON.parse(body) : body),
      headers: { get: () => null },
    };
  }

  class FakeEventSource {
    constructor(url) {
      this.url = String(url);
      this.closed = false;
      this.listeners = new Map();
      this.onopen = null;
      this.onerror = null;
      this.onmessage = null;
      streams.push(this);
      activeStreams++;
      if (activeStreams > maxActiveStreams) maxActiveStreams = activeStreams;
    }

    addEventListener(type, fn) {
      if (!this.listeners.has(type)) this.listeners.set(type, []);
      this.listeners.get(type).push(fn);
    }

    removeEventListener(type, fn) {
      const list = this.listeners.get(type);
      if (!list) return;
      const i = list.indexOf(fn);
      if (i >= 0) list.splice(i, 1);
    }

    close() {
      if (!this.closed) {
        this.closed = true;
        activeStreams--;
      }
    }

    emit(type, event) {
      const ev = event || {};
      if (type === 'open' && typeof this.onopen === 'function') this.onopen(ev);
      else if (type === 'error' && typeof this.onerror === 'function') this.onerror(ev);
      (this.listeners.get(type) || []).slice().forEach((fn) => fn(ev));
    }
  }

  const sandbox = {
    console,
    fetch: fetchFake,
    EventSource: FakeEventSource,
    localStorage,
    sessionStorage,
    location: locationStub,
    document: documentStub,
    confirm: () => true,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    addEventListener(type, fn) {
      if (type === 'pagehide') pagehideHandlers.push(fn);
    },
  };

  const context = vm.createContext(sandbox);
  context.window = context;
  context.self = context;

  const env = {
    context,
    document: documentStub,
    localStorage,
    sessionStorage,
    location: locationStub,
    timers,
    fetches,
    streams,
    routes: routeTable,
    tokenValue: token,
    userValue: user,
    el: getEl,
    click: (id) => getEl(id).click(),
    pagehide: () => pagehideHandlers.slice().forEach((fn) => fn({ type: 'pagehide' })),
    pending: timers.pending,
    retryTimers: () => timers.pending().filter((t) => RETRY_DELAYS.has(t.delay)),
    tick: timers.tick,
    flush,
    count: (kind) => fetches.filter((f) => f.kind === kind).length,
    requests: (kind) => fetches.filter((f) => f.kind === kind),
    token: () => localStorage.getItem('travelos.token'),
    userRaw: () => localStorage.getItem('travelos.user'),
    sessionFlag: () => sessionStorage.getItem('travelos.cookieSession'),
    lastStream: () => streams[streams.length - 1],
    activeStreamCount: () => activeStreams,
    maxActiveStreamCount: () => maxActiveStreams,
  };

  vm.runInContext(API_SOURCE, context, { filename: 'assets/js/api.js' });
  for (const script of PAGE_SCRIPTS) {
    vm.runInContext(script, context, { filename: 'notifications/index.html#inline' });
  }

  await flush();
  return env;
}

/* ============================== 1. 会话就绪后建流 ============================== */

test('1. Cookie 会话确认就绪后才创建 EventSource，且地址不含 Token', async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const env = await createEnv({ routes: { session: () => gate } });

  assert.equal(env.count('session'), 1, '页面启动应发起会话补建');
  assert.equal(env.streams.length, 0, '会话 pending 期间不得创建 EventSource');
  assert.equal(env.sessionFlag(), null, '会话未就绪时不应写就绪标记');

  release({ status: 200, body: {} });
  await env.flush();

  assert.equal(env.streams.length, 1, '会话就绪后应恰好创建 1 条 EventSource');
  assert.equal(env.streams[0].url, '/api/notifications/stream');
  assert.ok(!env.streams[0].url.includes(env.tokenValue), 'SSE URL 不得包含 Token');
  for (const req of env.fetches) {
    assert.ok(!req.url.includes(env.tokenValue), '任何请求 URL 都不得包含 Token');
  }
  assert.equal(env.sessionFlag(), '1', '会话成功后应写就绪标记');
});

/* ============================== 2. 会话建立失败不建流 ============================== */

test('2a. 首次会话失败、快速重试成功：成功前不建流，成功后仅 1 条', async () => {
  let calls = 0;
  const env = await createEnv({
    routes: {
      session: () => {
        calls++;
        return calls === 1 ? { network: true } : { status: 200, body: {} };
      },
    },
  });

  assert.equal(env.count('session'), 1);
  assert.equal(env.streams.length, 0, '首次会话失败时不得建流');
  assert.equal(env.pending().filter((t) => t.delay === 1500).length, 1,
    '应安排 1.5s 后的快速重试');
  assert.equal(env.token(), env.tokenValue, '会话建立失败不得清登录态');

  await env.tick(1500);

  assert.equal(env.count('session'), 2);
  assert.equal(env.streams.length, 1, '第二次会话成功后才建流，且只建 1 条');
  assert.equal(env.streams[0].url, '/api/notifications/stream');
});

test('2b. 连续会话失败按 1s/2s/4s/8s/8s 有限退避，超限停止且不建流', async () => {
  const env = await createEnv({ routes: { session: () => ({ network: true }) } });

  assert.equal(env.streams.length, 0);
  assert.equal(env.pending().filter((t) => t.delay === 1500).length, 1);
  await env.tick(1500);
  assert.equal(env.streams.length, 0, '快速重试失败后仍不得建流');

  const delays = [];
  for (let i = 0; i < 8 && env.retryTimers().length > 0; i++) {
    const timer = env.retryTimers()[0];
    delays.push(timer.delay);
    await env.tick(timer.delay);
    assert.equal(env.streams.length, 0, '退避期间保持 0 条 EventSource');
  }

  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 8000], '退避间隔应有上限且次数有限');
  assert.equal(env.count('session'), 7, '初始 1 + 快速重试 1 + 退避 5 = 7 次会话尝试');
  assert.equal(env.retryTimers().length, 0, '超过上限后不得继续安排会话重试');
  assert.equal(env.streams.length, 0);
});

test('2c. 本地无登录态：直接跳登录页，不建流也不补建会话', async () => {
  const env = await createEnv({ token: null });

  assert.equal(env.count('session'), 0, '未登录不应发起会话补建');
  assert.equal(env.streams.length, 0);
  assert.deepEqual(env.location.replaced, ['/login/']);
  assert.equal(env.sessionStorage.getItem('travelos.redirect'), '/notifications/');
});

test('2d. 会话补建返回 401：清登录态、跳登录页、不建流', async () => {
  const env = await createEnv({ routes: { session: () => ({ status: 401 }) } });

  assert.equal(env.streams.length, 0, '失效会话不得创建必然 401 的 SSE');
  assert.equal(env.token(), null);
  assert.equal(env.userRaw(), null);
  assert.deepEqual(env.location.replaced, ['/login/']);
  assert.equal(env.sessionFlag(), null);
});

/* ============================== 3. 临时网络故障有限退避 ============================== */

test('3. SSE 出错且探测网络异常：关闭当前连接、保留登录态、退避上限 5 次', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  assert.equal(env.streams.length, 1);
  const delays = [];

  for (let i = 0; i < 5; i++) {
    const es = env.lastStream();
    es.emit('error');
    await env.flush();

    assert.equal(es.closed, true, `第 ${i + 1} 次出错应立即关闭当前连接`);
    assert.equal(env.activeStreamCount(), 0, '出错后不得留下活动连接');
    assert.equal(env.token(), env.tokenValue, '网络故障不得清 Token');
    assert.notEqual(env.userRaw(), null, '网络故障不得清用户信息');

    const retries = env.retryTimers();
    assert.equal(retries.length, 1, `第 ${i + 1} 次出错应安排且仅安排 1 个退避定时器`);
    delays.push(retries[0].delay);
    await env.tick(retries[0].delay);
    assert.equal(env.streams.length, i + 2, '退避到期后重建 1 条连接');
  }

  assert.deepEqual(delays, [1000, 2000, 4000, 8000, 8000], '退避按指数增长且封顶 8s');
  assert.equal(env.streams.length, 6, '初始 1 条 + 自动重连 5 条');

  const last = env.lastStream();
  last.emit('error');
  await env.flush();
  assert.equal(env.retryTimers().length, 0, '超过 RETRY_MAX 后停止自动重连');
  assert.equal(last.closed, true);
  assert.equal(env.streams.length, 6);

  await env.tick(30000);
  assert.equal(env.streams.length, 6, '超限后长时间等待也不得再建连');
  assert.equal(env.maxActiveStreamCount(), 1, '任何时刻至多 1 条活动 SSE');
});

/* ============================== 4. onopen 重置计数 ============================== */

test('4. onopen 重置重连计数：恢复后再次断网仍从第一轮退避开始，且可再次恢复', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  let es = env.lastStream();
  es.emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 1000);
  await env.tick(1000);

  es = env.lastStream();
  es.emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 2000, '未建连成功时沿用累计计数');
  await env.tick(2000);

  es = env.lastStream();
  es.emit('open');
  es.emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 1000, 'onopen 后计数应归零，重新从 1s 开始');

  await env.tick(1000);
  es = env.lastStream();
  es.emit('open');
  es.emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 1000, '成功恢复后再次断网仍可完整重试');

  await env.tick(1000);
  assert.equal(env.activeStreamCount(), 1);
  assert.equal(env.streams.length, 5);
});

/* ============================== 5. unread 重置计数与合并刷新 ============================== */

test('5a. 收到 unread 重置计数；短窗口多事件合并为一次列表刷新', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  let es = env.lastStream();
  es.emit('error');
  await env.flush();
  await env.tick(1000);
  es = env.lastStream();

  const listBefore = env.count('list');
  const unreadBefore = env.count('unreadCount');

  es.emit('unread');
  es.emit('unread');
  es.emit('unread');
  await env.flush();

  const debounce = env.pending().filter((t) => t.delay === 400);
  assert.equal(debounce.length, 1, '短窗口内多个 unread 只能有 1 个去抖定时器');
  assert.equal(env.count('list'), listBefore, '去抖窗口结束前不得刷新列表');

  await env.tick(400);
  assert.equal(env.count('list'), listBefore + 1, '合并后只发起 1 次列表查询');
  assert.ok(env.count('unreadCount') >= unreadBefore + 1, '应刷新未读总数');

  es.emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 1000, 'unread 后计数应归零，重新从 1s 开始');
});

test('5b. unread 触发的刷新失败不会中断 SSE 生命周期', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  let es = env.lastStream();
  es.emit('error');
  await env.flush();
  await env.tick(1000);
  es = env.lastStream();

  env.routes.list = () => ({ network: true });
  env.routes.unreadCount = () => ({ network: true });

  es.emit('unread');
  await env.tick(400);
  assert.equal(env.streams.length, 2, '列表/未读刷新失败不得额外建流');
  assert.equal(env.activeStreamCount(), 1);

  es.emit('error');
  await env.flush();
  const retries = env.retryTimers();
  assert.equal(retries.length, 1, '刷新失败后 SSE 出错仍应正常安排重连');
  assert.equal(retries[0].delay, 1000);

  await env.tick(1000);
  assert.equal(env.streams.length, 3, '刷新失败后 SSE 仍能恢复建连');
});

/* ============================== 6. 会话失效清理并跳登录 ============================== */

test('6a. 探测返回 401/403：关闭连接、清全部登录态、跳登录、不再重连', async () => {
  for (const status of [401, 403]) {
    const env = await createEnv({ routes: { probe: () => ({ status }) } });

    const es = env.lastStream();
    assert.equal(env.sessionFlag(), '1', '前置：会话应已就绪');

    es.emit('error');
    await env.flush();

    assert.equal(es.closed, true, `HTTP ${status} 时应关闭当前连接`);
    assert.equal(env.token(), null, `HTTP ${status} 应清 Token`);
    assert.equal(env.userRaw(), null, `HTTP ${status} 应清用户信息`);
    assert.equal(env.sessionFlag(), null, `HTTP ${status} 应清会话就绪标记`);
    assert.deepEqual(env.location.replaced, ['/login/'], '应跳转登录页');
    assert.equal(env.retryTimers().length, 0, '会话失效不得安排自动重连');

    const streamCount = env.streams.length;
    await env.tick(30000);
    assert.equal(env.streams.length, streamCount, '失效后不得再创建 EventSource');
  }
});

test('6b. 本地登录态已经不存在：错误后同样清态跳登录且不重连', async () => {
  const env = await createEnv({ routes: { probe: () => ({ status: 200, body: { count: 0 } }) } });

  // 模拟其他标签页/流程已清掉本地登录态，但当前 SSE 仍开着
  env.localStorage.removeItem('travelos.token');
  env.localStorage.removeItem('travelos.user');

  const es = env.lastStream();
  es.emit('error');
  await env.flush();

  assert.equal(es.closed, true);
  assert.deepEqual(env.location.replaced, ['/login/']);
  assert.equal(env.retryTimers().length, 0);

  const streamCount = env.streams.length;
  await env.tick(10000);
  assert.equal(env.streams.length, streamCount);
});

/* ============================== 7. 出错与页面销毁 ============================== */

test('7a. onerror 只关闭当前代际连接，不影响之后建立的新连接', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  const old = env.lastStream();
  old.emit('error');
  await env.flush();
  assert.equal(old.closed, true, '出错应立即关闭当时的活动连接');

  await env.tick(1000);
  const fresh = env.lastStream();
  assert.notEqual(fresh, old);
  const probesBefore = env.count('probe');

  old.emit('error');
  await env.flush();

  assert.equal(fresh.closed, false, '旧连接回调不得关闭新连接');
  assert.equal(env.streams.length, 2, '旧连接回调不得额外建连');
  assert.equal(env.count('probe'), probesBefore, '旧连接回调不得再次发起探测');
});

test('7b. pagehide 关闭活动连接、清理退避定时器，重复调用不抛异常', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  const es = env.lastStream();
  es.emit('error');
  await env.flush();
  const retry = env.retryTimers()[0];
  assert.ok(retry, '前置：应有一个待执行的退避定时器');

  env.pagehide();

  assert.equal(es.closed, true, 'pagehide 应关闭活动连接');
  assert.equal(env.retryTimers().length, 0, 'pagehide 应取消待执行的重连定时器');
  assert.equal(env.activeStreamCount(), 0);

  const streamCount = env.streams.length;
  retry.fn(); // 模拟已入队但未清理干净的旧定时器回调
  await env.flush();
  assert.equal(env.streams.length, streamCount, '销毁后的旧退避回调不得建连');

  assert.doesNotThrow(() => { env.pagehide(); env.pagehide(); }, '重复 pagehide 不得抛异常');

  await env.tick(30000);
  assert.equal(env.streams.length, streamCount);
});

test('7c. pagehide 后旧 unread/onerror 回调不再刷新或建流', async () => {
  const env = await createEnv();

  const es = env.lastStream();
  const listBefore = env.count('list');
  const unreadBefore = env.count('unreadCount');
  const probeBefore = env.count('probe');

  es.emit('unread'); // 先制造一个 400ms 待执行去抖
  env.pagehide();

  await env.tick(400);
  assert.equal(env.count('list'), listBefore, '销毁后未决的 unread 去抖不得刷新列表');
  assert.equal(env.count('unreadCount'), unreadBefore, '销毁后不得刷新未读总数');

  es.emit('unread');
  es.emit('error');
  await env.flush();

  assert.equal(env.count('probe'), probeBefore, '销毁后旧 onerror 不得发起失效探测');
  assert.equal(env.streams.length, 1, '销毁后不得再建流');
  assert.equal(env.activeStreamCount(), 0);

  await env.tick(30000);
  assert.equal(env.streams.length, 1);
});

test('7d. 会话快速重试等待期间 pagehide：到期后不再补建会话或建流', async () => {
  const env = await createEnv({ routes: { session: () => ({ network: true }) } });

  assert.equal(env.streams.length, 0);
  const sessionCalls = env.count('session');
  assert.equal(env.pending().filter((t) => t.delay === 1500).length, 1);

  env.pagehide();
  await env.tick(30000);

  assert.equal(env.count('session'), sessionCalls, '页面销毁后不得继续会话重试');
  assert.equal(env.streams.length, 0, '页面销毁后不得补建 SSE');
  assert.equal(env.retryTimers().length, 0);
});

/* ============================== 8. 并发连接与旧回调隔离 ============================== */

test('8a. 旧连接的延迟 onerror/unread/退避回调在新连接建立后全部作废', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  const first = env.lastStream();
  first.emit('error');
  await env.flush();
  const firstRetry = env.retryTimers()[0];
  assert.ok(firstRetry);

  await env.tick(firstRetry.delay);
  const second = env.lastStream();
  assert.notEqual(second, first);

  const probeBefore = env.count('probe');
  const listBefore = env.count('list');

  // 旧连接的错误与事件回调迟到；旧定时器回调也被重复执行一次
  first.emit('error');
  first.emit('unread');
  firstRetry.fn();
  await env.flush();

  assert.equal(env.streams.length, 2, '旧回调不得创建第三条连接');
  assert.equal(second.closed, false, '旧回调不得关闭当前新连接');
  assert.equal(env.count('probe'), probeBefore, '旧 onerror 不得发起探测');
  assert.equal(env.count('list'), listBefore, '旧 unread 不得刷新列表');
  assert.equal(env.maxActiveStreamCount(), 1, '任何时刻至多 1 条活动 SSE');
  assert.equal(env.activeStreamCount(), 1);
});

test('8b. 重复 onerror 只分类一次；不产生重复定时器或并发连接', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  const es = env.lastStream();
  const probeBefore = env.count('probe');

  es.emit('error');
  es.emit('error');
  es.emit('error');
  await env.flush();

  assert.equal(env.count('probe'), probeBefore + 1, '同一连接重复 onerror 只应探测一次');
  assert.equal(env.retryTimers().length, 1, '只应存在 1 个重连定时器');
  assert.equal(es.closed, true);
  assert.equal(env.activeStreamCount(), 0);

  await env.tick(1000);
  const next = env.lastStream();
  next.emit('error');
  next.emit('error');
  await env.flush();

  assert.equal(env.count('probe'), probeBefore + 2, '新连接只应再探测一次');
  assert.equal(env.retryTimers().length, 1);
  assert.equal(env.streams.length, 2);
  assert.equal(env.maxActiveStreamCount(), 1);
});

test('8c. 会话退避与流建连交接：交接期间 0 条，成功后恰好 1 条且无残留定时器', async () => {
  let calls = 0;
  const env = await createEnv({
    routes: {
      session: () => {
        calls++;
        return calls <= 2 ? { network: true } : { status: 200, body: {} };
      },
    },
  });

  // 初始失败 + 1.5s 快速重试失败 → 进入会话退避，期间 0 条连接
  await env.tick(1500);
  assert.equal(env.streams.length, 0);
  const sessionRetry = env.retryTimers()[0];
  assert.ok(sessionRetry, '应存在会话退避定时器');

  // 会话退避到期并成功 → 建流，退避槽被清空
  await env.tick(sessionRetry.delay);
  assert.equal(env.streams.length, 1, '会话成功瞬间只建 1 条连接');
  assert.equal(env.activeStreamCount(), 1);
  assert.equal(env.retryTimers().length, 0, '交接后不得残留会话/流定时器');

  // 交接后的流错误应从第一轮 1s 退避开始
  env.routes.probe = () => ({ network: true });
  env.lastStream().emit('error');
  await env.flush();
  assert.equal(env.retryTimers()[0].delay, 1000);
  assert.equal(env.maxActiveStreamCount(), 1, '交接 + 重连全程至多 1 条活动连接');
});

/* ============================== 9. 列表 API 与 SSE 故障隔离 ============================== */


test('9a. SSE 故障退避期间，通知列表查询与手动刷新仍正常', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  env.lastStream().emit('error');
  await env.flush();
  const retry = env.retryTimers()[0];
  assert.ok(retry, '前置：SSE 处于退避状态');

  const listBefore = env.count('list');
  env.click('btnRefresh');
  await env.flush();

  assert.equal(env.count('list'), listBefore + 1, '手动刷新应正常发起列表查询');
  assert.equal(env.el('toast').textContent, '已刷新');
  assert.equal(env.streams.length, 1, '手动刷新不得重建 SSE');
  assert.ok(env.retryTimers().includes(retry), '刷新不得取消或重置 SSE 退避定时器');

  await env.tick(retry.delay);
  assert.equal(env.streams.length, 2, '退避到期后 SSE 仍正常恢复');
});

test('9b. 列表 API 失败时 SSE 仍建连；刷新失败不阻断重连', async () => {
  const env = await createEnv({
    routes: {
      list: () => ({ network: true }),
      probe: () => ({ network: true }),
    },
  });

  assert.equal(env.streams.length, 1, '列表查询失败不得阻止 SSE 建连');
  assert.equal(env.activeStreamCount(), 1);
  assert.match(env.el('toast').textContent, /连不上服务器/, '列表失败应有用户可见提示');

  env.routes.unreadCount = () => ({ network: true });
  const es = env.lastStream();
  es.emit('unread');
  const timerErrors = await env.tick(400);
  assert.deepEqual(timerErrors, [], 'unread 触发的刷新失败不得产生未处理异常');
  assert.equal(env.streams.length, 1);

  // 连续多次手动刷新失败也不得触发 SSE 建连/重连
  for (let i = 0; i < 3; i++) {
    env.click('btnRefresh');
    await env.flush();
  }
  assert.equal(env.streams.length, 1, '列表连续失败不得拖累/重建 SSE');
  assert.equal(env.retryTimers().length, 0, '列表失败不得安排 SSE 重连');

  es.emit('error');
  await env.flush();
  const retries = env.retryTimers();
  assert.equal(retries.length, 1, '列表/未读失败后 SSE 仍应安排重连');
  await env.tick(retries[0].delay);
  assert.equal(env.streams.length, 2, 'SSE 应能正常恢复');
});

test('9c. 自动重连耗尽后，列表手动刷新仍可用且不会重建 SSE', async () => {
  const env = await createEnv({ routes: { probe: () => ({ network: true }) } });

  for (let i = 0; i < 5; i++) {
    env.lastStream().emit('error');
    await env.flush();
    const timer = env.retryTimers()[0];
    assert.ok(timer, `第 ${i + 1} 次重连定时器应存在`);
    await env.tick(timer.delay);
  }
  assert.equal(env.streams.length, 6);
  assert.equal(env.retryTimers().length, 0, '前置：自动重连已耗尽');

  const listBefore = env.count('list');
  env.click('btnRefresh');
  await env.flush();

  assert.equal(env.count('list'), listBefore + 1, '手动刷新不依赖 SSE 自动重连');
  assert.equal(env.el('toast').textContent, '已刷新');
  await env.tick(30000);
  assert.equal(env.streams.length, 6, '手动刷新不得重建已耗尽的 SSE');
});

test('9d. SSE 断线时轮询更新未读数，恢复后停止轮询', async () => {
  let unread = 2;
  const env = await createEnv({
    routes: { unreadCount: () => ({ status: 200, body: { count: unread } }) },
  });
  env.lastStream().emit('open');
  env.lastStream().emit('error');
  await env.flush();
  const before = env.count('list');
  unread = 3;
  await env.tick(30000);
  assert.equal(env.count('list'), before + 1);
  assert.equal(Number(env.el('sUnread').textContent), unread);

  env.lastStream().emit('open');
  const recovered = env.count('list');
  await env.tick(30000);
  assert.equal(env.count('list'), recovered);
  env.pagehide();
});





