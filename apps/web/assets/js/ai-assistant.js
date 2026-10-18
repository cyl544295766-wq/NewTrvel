/* ============================================================
 * Travel OS · AI 助手（统一组件）
 *
 * 把原「悬浮宠物」升级为全站唯一的 AI 助手组件，并补上原先只在
 * 行程详情抽屉里才有的能力：
 *   1. 宠物角色在屏幕范围内自由漫游 / 拖拽 / 边缘吸附；
 *   2. 点击宠物弹出对话气泡，接统一后端（/api/ai/conversations）；
 *   3. 支持「行程上下文」：传入 tripId 时使用行程维度会话，
 *      否则使用全局会话（会话 id 按上下文分别持久化到 localStorage）；
 *   4. 草稿内联确认：行程变更 / 记账草稿直接在气泡中「应用 / 放弃」，
 *      应用后派发 travelos:ai-applied 事件，供宿主页面刷新数据；
 *   5. open_page 工具产生的页面跳转仍会在短暂停留后自动前往。
 *
 * 依赖：assets/css/pet.css（样式）、assets/js/api.js（apiGet/apiPost/isLoggedIn）。
 *
 * 兼容：保留 window.TravelOSPet 别名（init/stop/showBubble/hideBubble），
 *       原引用 pet.js 的页面无需改动。
 *       旧键 travelos.pet.convId 首次运行会自动迁移到 travelos.ai.convId。
 *
 * 公开 API（window.TravelOSAi）：
 *   TravelOSAi.init(options)   初始化（幂等，重复调用只刷新配置）
 *       options: { name, role, tripId, chips, zIndex, pad, mountEl }
 *   TravelOSAi.open()          打开对话气泡（等价 showBubble）
 *   TravelOSAi.close()         收起气泡
 *   TravelOSAi.setTrip(id)     切换行程上下文并重载会话
 *   TravelOSAi.refresh()       重新拉取当前会话消息
 *   TravelOSAi.stop()          暂停漫游并移除 DOM
 *   TravelOSAi.resolvePageTripId()  当前页行程上下文（URL 优先，其次最近行程）
 *   TravelOSAi.lastTripId()    最近一次访问的行程 id（跨页记忆）
 *   TravelOSAi.clearTripMemory()    清除最近行程记忆
 * ============================================================ */
(function (global) {
  'use strict';

  if (global.TravelOSAi && global.TravelOSAi.__loaded) return;

  /* 会话 id 存储键：全局一个 + 每个行程一个 */
  var CONV_KEY_GLOBAL = 'travelos.ai.convId';
  var CONV_KEY_PREFIX = 'travelos.ai.convId.';
  var LEGACY_CONV_KEY = 'travelos.pet.convId';

  var GLOBAL_CHIPS = [
    '帮我想想周末短途去哪玩',
    '旅行打包清单要带什么',
    '怎么记旅行账目最省心'
  ];
  var TRIP_CHIPS = [
    '帮我排一下第 1 天的行程，安排 3 个景点',
    '今天门票花了 240 元，帮我记一笔',
    '这段行程还有什么没准备好的？'
  ];

  var DRAFT_KIND = { expense: '变更预览 · 记一笔账', itinerary_change: '变更预览 · 行程调整' };

  var DEFAULTS = {
    name: '游游',
    role: 'AI 旅行伙伴',
    zIndex: 90,
    pad: 26,
    dragThreshold: 6,
    snap: { zone: 90 },
    chips: null,        /* 空则按上下文自动选择 */
    tripId: null,
    mountEl: null
  };

  var S = {
    opt: null,
    tripId: null,       /* 当前行程上下文（null = 全局） */
    tripBound: true,    /* 会话是否成功绑定行程（创建失败时回退为全局） */
    host: null, pet: null, bubble: null, mask: null, el: {},
    x: 0, y: 0,
    facing: 'right',
    state: 'idle',
    snapped: null,
    drag: null,
    bubbleOpen: false,
    convId: null,
    sending: false,
    expanded: false,    /* 桌面展开为右侧抽屉 */
    mobile: false,      /* ≤720px：气泡即底部 sheet */
    convs: []           /* 当前上下文可切换的会话列表 */
  };

  /* ---------- 工具 ---------- */
  function $(id) { return document.getElementById(id); }
  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function fmtHM(iso) {
    var d = new Date(iso);
    return isNaN(d) ? '' : ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2);
  }
  /* 是否窄屏（气泡以底部 sheet 呈现，模态） */
  function isMobile() {
    try { return global.matchMedia && global.matchMedia('(max-width: 720px)').matches; }
    catch (e) { return (global.innerWidth || 9999) <= 720; }
  }
  /* 当前是否处于模态呈现（展开抽屉 / 移动端 sheet） */
  function modalActive() {
    return !!(S.bubbleOpen && S.bubble && (S.expanded || S.mobile));
  }

  /* ---------- 行程上下文探测（阶段 2：URL + 跨页记忆） ----------
   *   - URL 带行程参数：以参数为准，并记为「最近行程」；
   *   - URL 不带参数：回落到最近一次访问的行程（含全局页续接）；
   *   - 行程子页缺参：解析期把参数补进 URL，避免其自身跳回 /trips/。
   */
  var LAST_TRIP_KEY = 'travelos.ai.lastTripId';
  /* 行程子页 → 该页读取的行程参数名（journal-detail 的 id 是日记 id；map 自带行程选择器降级；
   * trip-form 为新建页，注入 id 会被其当成编辑模式，均不在此列） */
  var TRIP_ROUTES = {
    '/itinerary/': 'trip', '/expenses/': 'trip', '/packing/': 'trip',
    '/documents/': 'trip', '/photos/': 'trip', '/journals/': 'trip',
    '/bookings/': 'trip', '/vouchers/': 'trip',
    '/trip-detail/': 'id'
  };

  function currentPath() {
    var p = global.location.pathname || '/';
    return p.charAt(p.length - 1) === '/' ? p : p + '/';
  }
  function readLastTripId() {
    try { return localStorage.getItem(LAST_TRIP_KEY) || null; } catch (e) { return null; }
  }
  function writeLastTripId(id) {
    if (!id) return;
    try { localStorage.setItem(LAST_TRIP_KEY, id); } catch (e) { /* ignore */ }
  }
  function paramTripId() {
    try {
      var path = currentPath();
      var p = new URLSearchParams(global.location.search || '');
      if (path === '/trip-detail/' || path === '/trip-form/') return p.get('id') || null;
      if (path === '/journal-detail/') return p.get('trip') || null;
      return p.get('trip') || p.get('id') || null;
    } catch (e) { return null; }
  }
  function resolvePageTripId() {
    var fromUrl = paramTripId();
    if (fromUrl) { writeLastTripId(fromUrl); return fromUrl; }
    return readLastTripId();
  }
  /* 解析期执行：行程子页缺参时补上最近行程参数（先于页面自身脚本读取 location） */
  function injectTripParam() {
    var key = TRIP_ROUTES[currentPath()];
    if (!key) return;
    try {
      var p = new URLSearchParams(global.location.search || '');
      var existing = p.get('trip') || p.get('id');
      if (existing) { writeLastTripId(existing); return; }
      var last = readLastTripId();
      if (!last) return;
      p.set(key, last);
      var url = global.location.pathname + '?' + p.toString() + (global.location.hash || '');
      global.history.replaceState(global.history.state, '', url);
      writeLastTripId(last);
    } catch (e) { /* ignore */ }
  }

  /* ---------- 会话 id 持久化（按上下文分键） ---------- */
  function convKey() {
    return S.tripId ? (CONV_KEY_PREFIX + S.tripId) : CONV_KEY_GLOBAL;
  }
  function migrateLegacyConv() {
    try {
      var legacy = localStorage.getItem(LEGACY_CONV_KEY);
      if (legacy && !localStorage.getItem(CONV_KEY_GLOBAL)) {
        localStorage.setItem(CONV_KEY_GLOBAL, legacy);
      }
      localStorage.removeItem(LEGACY_CONV_KEY);
    } catch (e) { /* 隐私模式忽略 */ }
  }
  function loadConvId() {
    try { return localStorage.getItem(convKey()) || null; } catch (e) { return null; }
  }
  function saveConvId(id) {
    try { localStorage.setItem(convKey(), id); } catch (e) { /* ignore */ }
  }
  function clearConvId() {
    try { localStorage.removeItem(convKey()); } catch (e) { /* ignore */ }
  }

  /* ---------- 位置与拖拽 ---------- */
  function bounds() {
    var w = S.pet.offsetWidth || 46;
    var h = S.pet.offsetHeight || 54;
    var pad = S.opt.pad;
    return {
      minX: pad, maxX: Math.max(pad, window.innerWidth - w - pad),
      minY: pad, maxY: Math.max(pad, window.innerHeight - h - pad)
    };
  }
  function clampPos() {
    var b = bounds();
    S.x = Math.min(b.maxX, Math.max(b.minX, S.x));
    S.y = Math.min(b.maxY, Math.max(b.minY, S.y));
  }
  function moveTo(x, y, ms) {
    S.x = x; S.y = y;
    S.pet.style.transition = ms ? ('transform ' + ms + 'ms cubic-bezier(0.3, 1.25, 0.6, 1)') : 'none';
    S.pet.style.transform = 'translate(' + Math.round(x) + 'px,' + Math.round(y) + 'px)';
    placeBubble();
  }
  function setState(name, facing) {
    S.state = name;
    if (facing) S.facing = facing;
    S.pet.setAttribute('data-state', name);
    S.pet.setAttribute('data-facing', S.facing);
  }

  /* ---------- 边缘吸附 ---------- */
  function snapTarget(edge) {
    var w = S.pet.offsetWidth || 46;
    var h = S.pet.offsetHeight || 54;
    var b = bounds();
    var x = S.x, y = S.y;
    if (edge === 'left')   { x = -w / 2; y = Math.min(b.maxY, Math.max(b.minY, S.y)); }
    if (edge === 'right')  { x = window.innerWidth - w / 2; y = Math.min(b.maxY, Math.max(b.minY, S.y)); }
    if (edge === 'top')    { x = Math.min(b.maxX, Math.max(b.minX, S.x)); y = -h / 2; }
    if (edge === 'bottom') { x = Math.min(b.maxX, Math.max(b.minX, S.x)); y = window.innerHeight - h / 2; }
    return { x: x, y: y };
  }
  function snapTo(edge, ms) {
    S.snapped = edge;
    var t = snapTarget(edge);
    moveTo(t.x, t.y, ms);
  }
  function showFull(ms) {
    var w = S.pet.offsetWidth || 46;
    var h = S.pet.offsetHeight || 54;
    var x = S.x, y = S.y;
    if (S.snapped === 'left')   x = 0;
    if (S.snapped === 'right')  x = window.innerWidth - w;
    if (S.snapped === 'top')    y = 0;
    if (S.snapped === 'bottom') y = window.innerHeight - h;
    moveTo(x, y, ms);
  }
  function maybeSnap(ms) {
    var w = S.pet.offsetWidth || 46;
    var h = S.pet.offsetHeight || 54;
    var cx = S.x + w / 2, cy = S.y + h / 2;
    var cand = [
      { edge: 'left',   d: cx },
      { edge: 'right',  d: window.innerWidth - cx },
      { edge: 'top',    d: cy },
      { edge: 'bottom', d: window.innerHeight - cy }
    ].sort(function (a, b) { return a.d - b.d; });
    if (cand[0].d > S.opt.snap.zone) return;
    snapTo(cand[0].edge, ms);
  }

  /* ---------- 拖拽（Pointer Events） ---------- */
  function onPointerDown(e) {
    if (e.button !== undefined && e.button !== 0) return;
    e.preventDefault();
    try { S.pet.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
    S.drag = { px: e.clientX, py: e.clientY, ox: S.x, oy: S.y, moved: false, id: e.pointerId, wasSnapped: S.snapped };
    setState('drag', S.facing);
    S.pet.style.transition = 'none';
  }
  function onPointerMove(e) {
    if (!S.drag || e.pointerId !== S.drag.id) return;
    var dx = e.clientX - S.drag.px, dy = e.clientY - S.drag.py;
    if (!S.drag.moved && Math.abs(dx) + Math.abs(dy) > S.opt.dragThreshold) {
      S.drag.moved = true;
      S.snapped = null;
    }
    if (!S.drag.moved) return;
    var b = bounds();
    S.x = Math.min(b.maxX, Math.max(b.minX, S.drag.ox + dx));
    S.y = Math.min(b.maxY, Math.max(b.minY, S.drag.oy + dy));
    S.pet.style.transform = 'translate(' + Math.round(S.x) + 'px,' + Math.round(S.y) + 'px)';
    placeBubble();
  }
  function onPointerUp(e) {
    if (!S.drag || e.pointerId !== S.drag.id) return;
    var ctx = S.drag;
    S.drag = null;
    try { S.pet.releasePointerCapture(e.pointerId); } catch (err) { /* ignore */ }
    setState('idle', S.facing);
    if (ctx.moved) {
      maybeSnap(420);
    } else if (ctx.wasSnapped) {
      showFull(240);
      showBubble();
    } else if (S.bubbleOpen) {
      hideBubble();
    } else {
      showBubble();
    }
  }

  /* ---------- 对话气泡 ---------- */
  function buildBubble() {
    if (S.mask) return;
    var b = document.createElement('div');
    b.className = 'pet-bubble';
    b.setAttribute('role', 'dialog');
    b.setAttribute('aria-label', S.opt.name + ' · AI 助手');
    b.innerHTML = ''
      + '<div class="pb-head">'
      +   '<span class="av" aria-hidden="true">🐾</span>'
      +   '<div class="who"><div class="nm">' + escapeHtml(S.opt.name) + '</div>'
      +   '<div class="role">' + escapeHtml(S.opt.role) + '</div></div>'
      +   '<button type="button" class="ex" title="展开 / 收起对话面板" aria-label="展开对话面板" aria-expanded="false">⤢</button>'
      +   '<button type="button" class="x" title="收起" aria-label="收起对话">✕</button>'
      + '</div>'
      + '<div class="pb-bar" hidden>'
      +   '<select class="conv" aria-label="选择会话"></select>'
      +   '<button type="button" class="new" title="开始新对话">＋ 新对话</button>'
      +   '<button type="button" class="del" title="删除当前会话">删除</button>'
      + '</div>'
      + '<div class="pb-body"></div>'
      + '<div class="pb-input" hidden>'
      +   '<input type="text" id="petInput" maxlength="200" placeholder="和' + escapeHtml(S.opt.name) + '聊聊旅行…">'
      +   '<button type="button" id="petSend" title="发送">➤</button>'
      + '</div>'
      + '<div class="pet-toast" role="status" aria-live="polite"></div>';
    document.body.appendChild(b);
    S.bubble = b;
    S.el.body = b.querySelector('.pb-body');
    S.el.inputRow = b.querySelector('.pb-input');
    S.el.input = b.querySelector('#petInput');
    S.el.send = b.querySelector('#petSend');
    b.querySelector('.x').addEventListener('click', hideBubble);
    b.querySelector('.ex').addEventListener('click', function () { setExpanded(!S.expanded); });
    b.querySelector('.pb-bar .conv').addEventListener('change', function () { selectConv(this.value); });
    b.querySelector('.pb-bar .new').addEventListener('click', newConv);
    b.querySelector('.pb-bar .del').addEventListener('click', delConv);
    /* 模态遮罩：桌面展开态点遮罩 → 收起为气泡；移动端 → 关闭 */
    var m = document.createElement('div');
    m.className = 'pet-mask';
    m.hidden = true;
    document.body.appendChild(m);
    S.mask = m;
    m.addEventListener('click', function () {
      if (S.expanded) setExpanded(false); else hideBubble();
    });
    /* 注意：不能把事件处理函数直接绑到 send 上 —— 否则事件对象会冒充 preset 参数 */
    S.el.send.addEventListener('click', function () { send(); });
    S.el.input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
    });
    /* 消息区内的事件委托：草稿「应用/放弃」 */
    S.el.body.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('button[data-draft]');
      if (btn) pdAction(btn.getAttribute('data-draft'), btn.getAttribute('data-mid'), btn);
    });
  }

  /* 呈现方式（气泡 / 抽屉 / 底部 sheet）与无障碍状态的同步 */
  function applyLayout() {
    if (!S.bubble || !S.mask) return;
    var modal = modalActive();
    S.bubble.classList.toggle('expanded', !!S.expanded && !S.mobile);
    S.mask.hidden = !modal;
    S.mask.classList.toggle('show', modal);
    S.bubble.setAttribute('aria-modal', modal ? 'true' : 'false');
    try { document.body.style.overflow = modal ? 'hidden' : ''; } catch (e) { /* ignore */ }
    /* 抽屉 / sheet 由 CSS 定位，需清掉气泡模式的定位内联样式 */
    if (S.expanded || S.mobile) {
      S.bubble.style.left = '';
      S.bubble.style.top = '';
    }
    var ex = S.bubble.querySelector('.ex');
    if (ex) {
      ex.textContent = S.expanded ? '⤡' : '⤢';
      ex.setAttribute('aria-expanded', S.expanded ? 'true' : 'false');
      ex.setAttribute('aria-label', S.expanded ? '收起为气泡' : '展开对话面板');
      ex.hidden = S.mobile;   /* 移动端本身就是 sheet，无需展开按钮 */
    }
    var bar = S.bubble.querySelector('.pb-bar');
    if (bar) bar.hidden = !modal;
    if (S.pet) S.pet.setAttribute('aria-expanded', S.bubbleOpen ? 'true' : 'false');
  }

  function setExpanded(on) {
    on = !!on;
    if (S.expanded === on) return;
    S.expanded = on;
    applyLayout();
    if (on && !S.mobile) {
      loadConversations();
      if (S.el.input && !S.el.inputRow.hidden) S.el.input.focus();
    } else {
      placeBubble();
      if (S.pet) S.pet.focus();
    }
  }

  function syncMobile() {
    var m = isMobile();
    if (m === S.mobile) return;
    S.mobile = m;
    S.expanded = S.expanded && !m;
    if (S.bubble) {
      applyLayout();
      if (modalActive()) loadConversations(); else placeBubble();
    }
  }

  function placeBubble() {
    if (!S.bubble || !S.bubbleOpen) return;
    if (S.expanded || S.mobile) return;   /* 抽屉 / sheet 走 CSS 定位 */
    var bw = S.bubble.offsetWidth || 480;
    var bh = S.bubble.offsetHeight || 420;
    var left = Math.min(Math.max(S.x + 23 - bw / 2, 10), window.innerWidth - bw - 10);
    S.bubble.style.left = Math.round(left) + 'px';
    var top = S.y - bh - 16;
    S.bubble.style.top = Math.round(Math.max(10, top)) + 'px';
    var tailX = Math.round(S.x + 23 - left - 6);
    S.bubble.style.setProperty('--tail-x', Math.min(Math.max(tailX, 14), bw - 20) + 'px');
  }

  function toast(msg) {
    if (!S.bubble) return;
    var t = S.bubble.querySelector('.pet-toast');
    if (!t) { t = document.createElement('div'); t.className = 'pet-toast'; S.bubble.appendChild(t); }
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { t.classList.remove('show'); }, 1800);
  }

  /* ---------- 会话切换（抽屉/sheet 中的多会话，与原行程详情面板对齐） ---------- */
  function scopeConvs(list) {
    return (list || []).filter(function (c) {
      return S.tripId ? c.tripId === S.tripId : (c.tripId == null || c.tripId === '');
    });
  }
  function loadConversations() {
    renderBar();
    var qs = S.tripId ? ('?tripId=' + encodeURIComponent(S.tripId)) : '?scope=global';
    apiGet('/ai/conversations' + qs)
      .then(function (r) { S.convs = scopeConvs(r && r.conversations); renderBar(); })
      .catch(function () { /* 读不到就保持既有列表 */ });
  }
  function renderBar() {
    if (!S.bubble) return;
    var sel = S.bubble.querySelector('.pb-bar .conv');
    if (!sel) return;
    var del = S.bubble.querySelector('.pb-bar .del');
    if (!S.convs.length) {
      sel.innerHTML = '<option value="">（还没有会话）</option>';
      sel.disabled = true;
      if (del) del.disabled = true;
      return;
    }
    sel.disabled = false;
    if (del) del.disabled = false;
    sel.innerHTML = S.convs.map(function (c) {
      return '<option value="' + escapeHtml(c.id) + '">' + escapeHtml(c.title || '未命名会话') + '</option>';
    }).join('');
    if (S.convId) sel.value = S.convId;
  }
  function selectConv(id) {
    if (!id || id === S.convId) return;
    S.convId = id;
    saveConvId(id);
    loadHistory();
  }
  function newConv() {
    S.convId = null;
    clearConvId();
    renderEmpty();
    if (S.el.input && !S.el.inputRow.hidden) S.el.input.focus();
  }
  async function delConv() {
    if (!S.convId) return;
    if (!global.confirm('删除该会话？对话记录将不可恢复。')) return;
    try {
      await apiDelete('/ai/conversations/' + encodeURIComponent(S.convId));
      S.convId = null;
      clearConvId();
      renderEmpty();
      loadConversations();
      toast('会话已删除');
    } catch (err) {
      toast(humanError(err));
    }
  }

  function renderEmpty() {
    var tripMode = !!S.tripId;
    S.el.body.innerHTML = ''
      + '<div class="pet-empty">'
      +   '<div class="e1">嗨，我是 ' + escapeHtml(S.opt.name) + ' 🐾'
      +     (tripMode ? '　·　本行程模式' : '') + '</div>'
      +   '<div class="e2">' + (tripMode
              ? '规划、餐厅、酒店、记账…都可以让我来安排'
              : '旅行计划、打包、记账…点什么都可以问我') + '</div>'
      + '</div>'
      + '<div class="pet-chips">'
      +   chips().map(function (c) {
            return '<button type="button" class="pet-chip">' + escapeHtml(c) + '</button>';
          }).join('')
      + '</div>';
    Array.prototype.forEach.call(S.el.body.querySelectorAll('.pet-chip'), function (btn) {
      btn.addEventListener('click', function () { send(btn.textContent); });
    });
    placeBubble();
  }

  function chips() {
    if (S.opt.chips && S.opt.chips.length) return S.opt.chips;
    return S.tripId ? TRIP_CHIPS : GLOBAL_CHIPS;
  }

  function renderLogin() {
    S.el.body.innerHTML = ''
      + '<div class="pet-login">'
      +   '<div class="t">登录之后就能和我聊天啦 🐾</div>'
      +   '<a href="/login/">去登录</a>'
      + '</div>';
    S.el.inputRow.hidden = true;
    placeBubble();
  }

  function navChipHtml(nav) {
    if (!nav || !nav.url) return '';
    return '<a class="pet-nav-chip" href="' + escapeHtml(nav.url) + '">'
      + '📍 打开「' + escapeHtml(nav.label || '页面') + '」→</a>';
  }

  /* 草稿卡片：与行程详情抽屉一致的视觉与字段（含 时间/票价/营业时间） */
  function draftHtml(m) {
    var d = m.draft || {};
    var status = m.draftStatus;
    var done = status === 'applied' || status === 'discarded';
    var html = '<div class="pet-draft' + (done ? ' done' : '') + '">';
    html += '<div class="pd-h">' + escapeHtml(DRAFT_KIND[d.type] || '变更预览') + '</div>';
    html += '<div class="pd-b">';
    html += '<div class="pd-s">' + escapeHtml(d.summary || m.content || '') + '</div>';
    if (d.type === 'expense' && d.expense) {
      var e = d.expense;
      var bits = [];
      if (e.amount) bits.push('¥ ' + e.amount);
      if (e.category) bits.push(e.category);
      if (e.title) bits.push(e.title);
      if (bits.length) html += '<ul class="pd-list"><li><span class="op">记账</span>' + escapeHtml(bits.join(' · ')) + '</li></ul>';
    }
    if (d.type === 'itinerary_change' && Array.isArray(d.changes)) {
      html += '<ul class="pd-list">' + d.changes.slice(0, 12).map(function (c) {
        var op = c.action === 'update' ? '修改' : (c.action === 'remove' || c.action === 'delete' ? '删除' : '新增');
        var txt = (c.name || c.placeId || '');
        if (c.dayIndex != null) txt += ' · 第 ' + c.dayIndex + ' 天';
        var meta = [];
        if (c.startTime && c.endTime) meta.push(escapeHtml(c.startTime + '–' + c.endTime));
        else if (c.startTime) meta.push(escapeHtml(c.startTime));
        if (c.estimatedCost != null && c.estimatedCost !== '') meta.push('¥' + escapeHtml(String(c.estimatedCost)));
        if (c.openTime) meta.push('营业 ' + escapeHtml(c.openTime));
        return '<li><span class="op">' + op + '</span>' + escapeHtml(txt)
          + (meta.length ? '<span class="dmeta">' + meta.join(' · ') + '</span>' : '') + '</li>';
      }).join('') + '</ul>';
    }
    html += '</div>';
    if (done) {
      html += '<div class="pd-stat ' + (status === 'applied' ? 'ok' : 'no') + '">'
        + (status === 'applied' ? '✓ 已应用' : '已放弃') + '</div>';
    } else {
      html += '<div class="pd-acts">'
        + '<button type="button" class="pd-btn apply" data-draft="apply" data-mid="' + escapeHtml(m.id) + '">应用</button>'
        + '<button type="button" class="pd-btn discard" data-draft="discard" data-mid="' + escapeHtml(m.id) + '">放弃</button>'
        + '</div>';
    }
    html += '</div>';
    return html;
  }

  function msgHtml(m) {
    var isUser = m.role === 'user';
    if (!isUser && m.draft && m.draftStatus !== undefined) {
      /* 草稿消息：直接渲染卡片（卡片已含摘要与字段） */
      return '<div class="pet-msg ai msg-draft">' + draftHtml(m) + '</div>';
    }
    var nav = (!isUser && !m.draftStatus && m.navigation) ? navChipHtml(m.navigation) : '';
    return '<div class="pet-msg ' + (isUser ? 'user' : 'ai') + '">'
      + '<div class="txt">' + escapeHtml(m.content || '') + '</div>' + nav + '</div>';
  }

  function renderMessages(items) {
    if (!items.length) { renderEmpty(); return; }
    S.el.body.innerHTML = ''
      + '<div class="pb-msgs" id="petMsgs">'
      +   items.map(msgHtml).join('')
      + '</div>';
    var box = S.el.body.querySelector('#petMsgs');
    box.scrollTop = box.scrollHeight;
    placeBubble();
  }

  function appendTyping() {
    var box = S.el.body.querySelector('#petMsgs');
    if (!box) return;
    var el = document.createElement('div');
    el.className = 'pet-msg ai';
    el.id = 'petTyping';
    el.innerHTML = '<div class="txt"><span class="pet-typing"><i></i><i></i><i></i></span></div>';
    box.appendChild(el);
    box.scrollTop = box.scrollHeight;
    placeBubble();
  }
  function removeTyping() {
    var t = S.el.body.querySelector('#petTyping');
    if (t) t.remove();
  }

  function newTitle(firstText) {
    var prefix = S.tripId ? '' : '宠物·';
    return (prefix + String(firstText || '对话')).slice(0, 24);
  }

  async function ensureConversation(firstText) {
    if (S.convId) return S.convId;
    var payload = { title: newTitle(firstText) };
    if (S.tripId && S.tripBound) payload.tripId = S.tripId;
    var created;
    try {
      created = await apiPost('/ai/conversations', payload);
    } catch (err) {
      /* 行程会话创建失败（如非成员/行程已删）：回退为全局会话，保证可用 */
      if (payload.tripId) {
        S.tripBound = false;
        created = await apiPost('/ai/conversations', { title: payload.title });
      } else {
        throw err;
      }
    }
    S.convId = created.conversation.id;
    saveConvId(S.convId);
    loadConversations();
    return S.convId;
  }

  function fetchMessages() {
    return apiGet('/ai/conversations/' + encodeURIComponent(S.convId)
      + '/messages?page=1&pageSize=30&latest=true');
  }

  async function loadHistory() {
    S.convId = loadConvId();
    if (!S.convId) { renderEmpty(); return; }
    try {
      var d = await fetchMessages();
      var items = (d.messages || []).slice();
      if (!items.length) { renderEmpty(); return; }
      renderMessages(items);
    } catch (err) {
      S.convId = null;
      clearConvId();
      renderEmpty();
    }
  }

  async function refresh() {
    if (!S.convId) { renderEmpty(); return; }
    var d = await fetchMessages();
    renderMessages((d.messages || []).slice());
  }

  async function pdAction(kind, messageId, btn) {
    if (!S.convId || !messageId) return;
    if (btn) btn.disabled = true;
    try {
      await apiPost('/ai/conversations/' + encodeURIComponent(S.convId)
        + '/' + kind + '/' + encodeURIComponent(messageId), {});
      toast(kind === 'apply' ? '已应用变更' : '已放弃该草稿');
      await refresh();
      dispatchApplied(kind, messageId);
    } catch (err) {
      if (btn) btn.disabled = false;
      toast(humanError(err));
    }
  }

  function dispatchApplied(kind, messageId) {
    var detail = { kind: kind, messageId: messageId, conversationId: S.convId, tripId: S.tripId };
    try {
      window.dispatchEvent(new CustomEvent('travelos:ai-applied', { detail: detail }));
    } catch (e) { /* ignore */ }
    if (typeof window.TravelOSOnAiApplied === 'function') {
      try { window.TravelOSOnAiApplied(detail); } catch (e) { /* ignore */ }
    }
  }

  async function send(preset) {
    if (S.sending) return;
    var text = String(preset != null ? preset : S.el.input.value).trim();
    if (!text) return;
    S.sending = true;
    S.el.send.disabled = true;
    S.el.input.value = '';
    var box = S.el.body.querySelector('#petMsgs');
    if (!box) { S.el.body.innerHTML = '<div class="pb-msgs" id="petMsgs"></div>'; box = S.el.body.querySelector('#petMsgs'); }
    var empty = S.el.body.querySelector('.pet-empty');
    if (empty) empty.remove();
    var chipBox = S.el.body.querySelector('.pet-chips');
    if (chipBox) chipBox.remove();
    box.insertAdjacentHTML('beforeend',
      '<div class="pet-msg user"><div class="txt">' + escapeHtml(text) + '</div></div>');
    box.scrollTop = box.scrollHeight;
    appendTyping();
    try {
      await ensureConversation(text);
      await apiPost('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages', { content: text });
      /* 与行程详情 AI 面板同一模式：发送后拉取完整消息列表（草稿状态由后端装配） */
      var d = await fetchMessages();
      var items = (d.messages || []).slice();
      removeTyping();
      renderMessages(items);
      var last = items[items.length - 1];
      if (last && last.role === 'assistant' && last.navigation && last.navigation.url) {
        setTimeout(function () { window.location.href = last.navigation.url; }, 1200);
      }
    } catch (err) {
      removeTyping();
      box.insertAdjacentHTML('beforeend',
        '<div class="pet-msg ai"><div class="txt">' + escapeHtml(humanError(err)) + '</div></div>');
      box.scrollTop = box.scrollHeight;
    }
    S.sending = false;
    S.el.send.disabled = false;
    S.el.input.focus();
  }

  async function showBubble() {
    if (S.snapped) showFull(240);
    buildBubble();
    S.bubbleOpen = true;
    S.pet.classList.add('bubble-open');
    setState('idle', S.facing);
    S.bubble.classList.add('open');
    applyLayout();
    if (modalActive()) loadConversations(); else placeBubble();
    if (!isLoggedIn()) {
      renderLogin();
      return;
    }
    S.el.inputRow.hidden = false;
    S.el.body.innerHTML = '<div class="pet-msg ai"><div class="txt"><span class="pet-typing"><i></i><i></i><i></i></span></div></div>';
    await loadHistory();
    setTimeout(function () { if (S.el.input && !S.el.inputRow.hidden) S.el.input.focus(); }, 120);
  }

  function hideBubble() {
    if (!S.bubble) return;
    S.expanded = false;
    var wasModal = modalActive();
    S.bubble.classList.remove('open');
    S.bubbleOpen = false;
    S.pet.classList.remove('bubble-open');
    applyLayout();
    if (S.snapped) snapTo(S.snapped, 260);
    if (wasModal && S.pet) S.pet.focus();
  }

  /* ---------- 初始化 ---------- */
  function ensureDom() {
    if (!S.host) {
      S.host = document.createElement('div');
      S.host.className = 'pet-host';
      var mount = S.opt.mountEl;
      if (typeof mount === 'string') mount = document.querySelector(mount);
      (mount || document.body).appendChild(S.host);
    }
    if (!S.pet) {
      S.pet = document.createElement('div');
      S.pet.className = 'pet';
      S.pet.setAttribute('data-state', 'idle');
      S.pet.setAttribute('data-facing', 'right');
      S.pet.setAttribute('role', 'button');
      S.pet.setAttribute('tabindex', '0');
      S.pet.setAttribute('aria-label', 'AI 旅行助手');
      S.pet.setAttribute('aria-haspopup', 'dialog');
      S.pet.setAttribute('aria-expanded', 'false');
      S.pet.innerHTML = ''
        + '<div class="body-wrap">'
        +   '<div class="body"><div class="belly"></div><div class="scarf"></div>'
        +     '<div class="eyes"><span class="eye"></span><span class="eye"></span></div>'
        +     '<span class="cheek l"></span><span class="cheek r"></span>'
        +   '</div>'
        +   '<div class="feet"><span class="foot"></span><span class="foot"></span></div>'
        + '</div>'
        + '<div class="shadow"></div>'
        + '<span class="name-chip">' + escapeHtml(S.opt.name) + ' · 点击聊聊</span>';
      S.host.appendChild(S.pet);
      S.pet.addEventListener('pointerdown', onPointerDown);
      S.pet.addEventListener('pointermove', onPointerMove);
      S.pet.addEventListener('pointerup', onPointerUp);
      S.pet.addEventListener('pointercancel', onPointerUp);
      S.pet.addEventListener('mouseenter', function () {
        if (S.snapped && !S.bubbleOpen) showFull(240);
      });
      S.pet.addEventListener('mouseleave', function () {
        if (S.snapped && !S.bubbleOpen) snapTo(S.snapped, 240);
      });
      S.pet.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); showBubble(); }
      });
      window.addEventListener('resize', function () {
        if (!S.pet) return;
        syncMobile();
        if (S.snapped) snapTo(S.snapped, 0);
        else { clampPos(); moveTo(S.x, S.y, 0); }
        placeBubble();
      });
      window.addEventListener('scroll', function () { placeBubble(); }, { passive: true });
      document.addEventListener('keydown', function (e) {
        if (e.key !== 'Escape' || !S.bubbleOpen) return;
        if (S.expanded) setExpanded(false); else hideBubble();
      });
      try {
        var mq = global.matchMedia('(max-width: 720px)');
        var onMq = function () { syncMobile(); };
        if (mq.addEventListener) mq.addEventListener('change', onMq);
        else if (mq.addListener) mq.addListener(onMq);
      } catch (e) { /* ignore */ }
    }
  }

  function init(options) {
    if (S.host) { S.opt = Object.assign({}, S.opt, options || {}); return; }
    S.opt = Object.assign({}, DEFAULTS, options || {});
    var tripId = S.opt.tripId || null;
    migrateLegacyConv();
    S.tripId = tripId;
    S.tripBound = !!tripId;
    S.mobile = isMobile();
    ensureDom();
    var b = bounds();
    S.x = b.maxX - 8;
    S.y = b.maxY - 6;
    clampPos();
    moveTo(S.x, S.y, 0);
    setState('idle', 'right');
  }

  function setTrip(tripId) {
    tripId = tripId || null;
    if (tripId === S.tripId) return;
    S.tripId = tripId;
    S.tripBound = !!tripId;
    S.convId = null;
    if (S.bubble && S.bubbleOpen) loadHistory();
  }

  function stop() {
    if (S.host) S.host.remove();
    if (S.bubble) S.bubble.remove();
    if (S.mask) S.mask.remove();
    try { document.body.style.overflow = ''; } catch (e) { /* ignore */ }
    S.host = null; S.pet = null; S.bubble = null; S.mask = null;
    S.bubbleOpen = false; S.drag = null; S.snapped = null; S.expanded = false;
  }

  global.TravelOSAi = {
    __loaded: true,
    init: init,
    open: showBubble,
    close: hideBubble,
    showBubble: showBubble,
    hideBubble: hideBubble,
    setTrip: setTrip,
    refresh: refresh,
    stop: stop,
    expand: function () { setExpanded(true); },
    collapse: function () { setExpanded(false); },
    isExpanded: function () { return !!S.expanded; },
    resolvePageTripId: resolvePageTripId,
    lastTripId: readLastTripId,
    clearTripMemory: function () {
      try { localStorage.removeItem(LAST_TRIP_KEY); } catch (e) { /* ignore */ }
    }
  };

  /* 向后兼容别名：原 pet.js 的公开 API */
  global.TravelOSPet = {
    init: init,
    stop: stop,
    showBubble: showBubble,
    hideBubble: hideBubble
  };

  /* ---- 解析期：先按最近行程补齐行程子页 URL 参数，再让页面脚本读取 location ---- */
  injectTripParam();

  /* ---- 自动初始化：引本文件即生效 ----
   * 需要禁用的页面在引脚本前设置：window.TravelOSPetAuto = false;
   */
  function autoInit() {
    if (global.TravelOSPetAuto === false) return;
    init({ tripId: resolvePageTripId() });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', autoInit);
  } else {
    autoInit();
  }
})(window);
