/* ============================================================
 * 织途 · AI 助手（统一组件）
 *
 * 把原「悬浮宠物」升级为全站唯一的 AI 助手组件，并补上原先只在
 * 行程详情抽屉里才有的能力：
 *   1. 宠物角色在屏幕范围内自由漫游 / 拖拽 / 边缘吸附；
 *   2. 点击宠物打开大对话面板，接统一后端（/api/ai/conversations）；
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
 *       options: { name, role, tripId, chips, zIndex, pad, mountEl, inlineLauncher }
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

  /* 发送策略（幂等键 + 失败去向判定）：由 pet.js 先行注入 ai-send-policy.js；
   * 若缺失则使用内置保守兜底（失败一律“对齐而不盲目重发”）。 */
  var policy = global.TravelOSSendPolicy || {
    newRequestId: function () {
      return 'r' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
    },
    decideStreamFallback: function (state) {
      if (!state || !state.streamSupported || !state.hasToken) {
        return { action: 'fallback', reason: 'not-supported' };
      }
      if (state.fetchFailed) return { action: 'align', reason: 'network' };
      if (state.status === 401 || state.status === 403) {
        return { action: 'fallback', reason: 'unauthenticated' };
      }
      if (typeof state.status === 'number' && state.status >= 400) {
        return { action: 'align', reason: 'error' };
      }
      if (!state.bodyReadable) return { action: 'align', reason: 'no-body' };
      return { action: 'read', reason: 'ok' };
    },
    hasUserMessageWithText: function (messages, text) {
      if (!Array.isArray(messages)) return false;
      var target = String(text == null ? '' : text).trim();
      for (var i = 0; i < messages.length; i++) {
        var m = messages[i];
        if (m && m.role === 'user' &&
            String(m.content == null ? '' : m.content).trim() === target) return true;
      }
      return false;
    },
    shouldFallbackAfterAlign: function (messages, text) {
      return !this.hasUserMessageWithText(messages, text);
    }
  };

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

  var DRAFT_KIND = { expense: '变更预览 · 记一笔账', itinerary_change: '变更预览 · 行程调整',
    booking: '变更预览 · 登记预订', packing: '打包清单草稿', receipt_review: '票据核对' };

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
    receiptBusy: false,
    receiptImage: null,
    planning: false,
    streamAbort: null,   /* 当前流式请求的 AbortController（用户主动停止用） */
    expanded: false,    /* 桌面使用右侧抽屉，窄屏使用底部面板 */
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
    var w = S.pet.offsetWidth;
    var h = S.pet.offsetHeight;
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
    var w = S.pet.offsetWidth;
    var h = S.pet.offsetHeight;
    var b = bounds();
    var x = S.x, y = S.y;
    /* 吸附位改为完全在视口内（x=0 / x=innerWidth-w），保证吸附后仍可命中拖拽；
       原半隐藏（x=-w/2）导致 pet 不可命中（elementFromPoint 命中其他元素） */
    if (edge === 'left')   { x = 0; y = Math.min(b.maxY, Math.max(b.minY, S.y)); }
    if (edge === 'right')  { x = window.innerWidth - w; y = Math.min(b.maxY, Math.max(b.minY, S.y)); }
    if (edge === 'top')    { x = Math.min(b.maxX, Math.max(b.minX, S.x)); y = 0; }
    if (edge === 'bottom') { x = Math.min(b.maxX, Math.max(b.minX, S.x)); y = window.innerHeight - h; }
    /* 顶栏 z-index:60 > pet z-index:55，吸附位 y 不得进入顶栏区域（否则不可命中） */
    var topbar = document.querySelector('.topbar, .tb-inner');
    if (topbar) {
      var th = topbar.getBoundingClientRect().height || 0;
      if (th > 0 && y < th) y = th + 2;
    }
    return { x: x, y: y };
  }
  function snapTo(edge, ms) {
    S.snapped = edge;
    S.pet.setAttribute('data-edge', edge);
    var t = snapTarget(edge);
    moveTo(t.x, t.y, ms);
  }
  function showFull(ms) {
    var w = S.pet.offsetWidth;
    var h = S.pet.offsetHeight;
    var x = S.x, y = S.y;
    if (S.snapped === 'left')   x = 0;
    if (S.snapped === 'right')  x = window.innerWidth - w;
    if (S.snapped === 'top')    y = 0;
    if (S.snapped === 'bottom') y = window.innerHeight - h;
    moveTo(x, y, ms);
  }
  function maybeSnap(ms) {
    var w = S.pet.offsetWidth;
    var h = S.pet.offsetHeight;
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
      S.pet.removeAttribute('data-edge');
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
    b.inert = true;
    b.setAttribute('role', 'dialog');
    b.setAttribute('aria-label', S.opt.name + ' · AI 助手');
    b.innerHTML = ''
      + '<div class="pb-head">'
      +   '<span class="av" aria-hidden="true"></span>'
      +   '<div class="who"><div class="nm">' + escapeHtml(S.opt.name) + '</div>'
      +   '<div class="role">' + escapeHtml(S.opt.role) + '</div></div>'
      +   '<button type="button" class="ex" title="展开 / 收起对话面板" aria-label="展开对话面板" aria-expanded="false">⤢</button>'
      +   '<button type="button" class="x" aria-label="关闭对话">关闭</button>'
      + '</div>'
      + '<div class="pb-bar" hidden>'
      +   '<select class="conv" aria-label="选择会话"></select>'
      +   '<button type="button" class="new" title="开始新对话">＋ 新对话</button>'
      +   '<button type="button" class="del" title="删除当前会话">删除</button>'
      + '</div>'
      + '<div class="pb-plan" hidden><span role="status"></span>'
      +   '<button type="button" hidden>重试规划</button></div>'
      + '<div class="pb-body"></div>'
      + '<div class="pb-input" hidden>'
      +   '<button type="button" id="petReceipt" title="识别票据图片" hidden>票据</button>'
      +   '<input type="file" id="petReceiptFile" accept="image/jpeg,image/png" hidden>'
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
    S.el.receipt = b.querySelector('#petReceipt');
    var receiptFile = b.querySelector('#petReceiptFile');
    S.el.receipt.addEventListener('click', function () { receiptFile.click(); });
    receiptFile.addEventListener('change', function () {
      var file = this.files[0];
      this.value = '';
      if (file) sendReceipt(file);
    });
    S.el.plan = b.querySelector('.pb-plan');
    S.el.planRetry = S.el.plan.querySelector('button');
    S.el.planRetry.addEventListener('click', function () { runPlan(true); });
    S.el.close = b.querySelector('.pb-head .x');
    S.el.close.addEventListener('click', hideBubble);
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
      if (S.expanded && !S.mobile) setExpanded(false); else hideBubble();
    });
    /* 注意：不能把事件处理函数直接绑到 send 上 —— 否则事件对象会冒充 preset 参数
     * 发送中再次点击 = 主动停止（中断流 → 服务端同步取消生成任务） */
    S.el.send.addEventListener('click', function () {
      if (S.sending) { stopSending(); } else { send(); }
    });
    S.el.input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); if (!S.sending) send(); }
    });
    /* 消息区内的事件委托：草稿「应用/放弃」 */
    S.el.body.addEventListener('click', function (e) {
      var btn = e.target.closest && e.target.closest('button[data-draft]');
      if (!btn) return;
      var action = btn.getAttribute('data-draft');
      if (action === 'confirm-receipt') confirmReceipt(btn);
      else pdAction(action, btn.getAttribute('data-mid'), btn);
    });
    S.el.body.addEventListener('change', function (e) {
      var form = e.target.closest && e.target.closest('.pd-packing-form');
      if (form) syncPackingChoices(form);
      var receipt = e.target.closest && e.target.closest('.pd-receipt');
      if (receipt) {
        syncReceiptControls(receipt);
        if (!e.target.closest('.pd-receipt-duplicate')) {
          receipt.querySelector('.pd-receipt-duplicate input').checked = false;
        }
      }
    });
  }

  /* 呈现方式（气泡 / 抽屉 / 底部 sheet）与无障碍状态的同步 */
  function applyLayout() {
    if (!S.bubble || !S.mask) return;
    var modal = modalActive();
    S.bubble.classList.toggle('expanded', !!S.expanded && !S.mobile);
    S.bubble.inert = !S.bubbleOpen;
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
      ex.setAttribute('aria-label', S.expanded ? '缩小聊天面板' : '放大聊天面板');
      ex.title = ex.getAttribute('aria-label');
      ex.hidden = S.mobile;   /* 移动端本身就是 sheet，无需展开按钮 */
    }
    var bar = S.bubble.querySelector('.pb-bar');
    if (bar) bar.hidden = !modal || !isLoggedIn();
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
      else if (S.bubbleOpen) S.el.close.focus();
    } else {
      placeBubble();
      if (S.pet) S.pet.focus();
    }
  }

  function syncMobile() {
    var m = isMobile();
    if (m === S.mobile) return;
    S.mobile = m;
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
    var anchor = S.opt.inlineLauncher ? S.pet.getBoundingClientRect() : null;
    var centerX = (anchor ? anchor.left : S.x) + S.pet.offsetWidth / 2;
    var left = Math.min(Math.max(centerX - bw / 2, 10), window.innerWidth - bw - 10);
    S.bubble.style.left = Math.round(left) + 'px';
    var top = (anchor ? anchor.top : S.y) - bh - 16;
    S.bubble.style.top = Math.round(Math.max(10, top)) + 'px';
    var tailX = Math.round(centerX - left - 6);
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
    if (!isLoggedIn()) return;
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
    S.bubble.querySelector('.pb-bar .new').disabled = S.planning;
    if (!S.convs.length) {
      sel.innerHTML = '<option value="">（还没有会话）</option>';
      sel.disabled = true;
      if (del) del.disabled = true;
      return;
    }
    sel.disabled = S.planning;
    if (del) del.disabled = S.planning;
    sel.innerHTML = S.convs.map(function (c) {
      return '<option value="' + escapeHtml(c.id) + '">' + escapeHtml(c.title || '未命名会话') + '</option>';
    }).join('');
    if (S.convId) sel.value = S.convId;
  }
  function selectConv(id) {
    if (S.planning) { renderBar(); return; }
    if (!id || id === S.convId) return;
    S.convId = id;
    saveConvId(id);
    loadHistory();
  }
  function newConv() {
    if (S.planning) return;
    S.convId = null;
    clearConvId();
    renderEmpty();
    if (S.el.input && !S.el.inputRow.hidden) S.el.input.focus();
  }
  async function delConv() {
    if (S.planning) return;
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
      +   '<div class="e1">嗨，我是 ' + escapeHtml(S.opt.name)
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
      +   '<div class="t">登录后即可和我聊旅行</div>'
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

  function routeLocalTime(value, onDate) {
    if (!value) return '时间未填';
    var stamp = String(value);
    return (stamp.slice(0, 10) === onDate ? '' : stamp.slice(0, 10) + ' ') + stamp.slice(11, 16);
  }

  function routePlaceLabel(place, date, showCoordinates) {
    if (!place) return '—';
    var details = [place.name, routeLocalTime(place.start, date) + '–' + routeLocalTime(place.end, date),
      place.address, place.notes];
    if (showCoordinates && place.latitude != null && place.longitude != null) {
      details.push(place.latitude + ', ' + place.longitude);
    }
    return details.filter(Boolean).join(' · ');
  }

  function routePreviewHtml(preview) {
    var report = preview.report || {};
    var modes = { driving: '驾车估时', walking: '步行估时', transit: '公共交通估时' };
    var states = { checked: '已核对可用数据', partial: '部分数据待核实', conflict: '仍有时间冲突' };
    var actions = { add: '新增', update: '调整', remove: '移除' };
    var html = '<div class="pd-route"><p>' + escapeHtml(preview.tripTitle + ' · ' + preview.date + ' · ' + preview.timezone)
      + '</p><p>' + escapeHtml(preview.reason || '') + '</p>';
    html += '<ul class="pd-list">' + (preview.differences || []).map(function (item) {
      var before = item.before;
      var after = item.after;
      var showCoordinates = before && after &&
        (before.latitude !== after.latitude || before.longitude !== after.longitude);
      var changedType = before && after && before.type !== after.type;
      return '<li><span class="op">' + escapeHtml(actions[item.action] || '变更') + '</span><div>'
        + (before ? '<div><b>原安排</b> ' + escapeHtml(routePlaceLabel(before, preview.date, showCoordinates)
          + (changedType ? ' · ' + before.type : '')) + '</div>' : '')
        + (after ? '<div><b>调整后</b> ' + escapeHtml(routePlaceLabel(after, preview.date, showCoordinates)
          + (changedType ? ' · ' + after.type : '')) + '</div>' : '') + '</div></li>';
    }).join('') + '</ul>';
    var locked = (preview.bookings || []).map(function (booking) {
      return booking.title + ' ' + routeLocalTime(booking.start, preview.date)
        + (booking.end ? ' → ' + routeLocalTime(booking.end, preview.date) : '');
    }).concat(preview.lockedPlaces || []);
    if (locked.length) {
      html += '<p><b>保留</b> ' + escapeHtml(locked.join('；')) + '</p>';
    }
    var heading = [modes[report.mode], states[report.status]].filter(Boolean).join(' · ');
    html += '<details open><summary>' + escapeHtml(heading || '路线校验') + '</summary><ul>';
    var legs = report.legs || [];
    legs.forEach(function (leg) {
      var duration = leg.durationSeconds == null ? '耗时待核实'
        : (leg.durationSeconds === 0 ? '同一位置' : '约 ' + Math.ceil(leg.durationSeconds / 60) + ' 分钟');
      var gap = leg.availableSeconds == null ? '' : ' · 空档 ' + Math.floor(leg.availableSeconds / 60) + ' 分钟';
      var buffer = leg.arrivalBufferMinutes ? ' · 已预留到站 ' + leg.arrivalBufferMinutes + ' 分钟' : '';
      html += '<li>' + escapeHtml(leg.from + ' → ' + leg.to + ' · ' + duration + gap + buffer) + '</li>';
    });
    (report.issues || []).forEach(function (issue) {
      html += '<li>' + escapeHtml(issue.message) + '</li>';
    });
    if (preview.weatherNote) {
      html += '<li>' + escapeHtml(preview.weatherNote) + '</li>';
    }
    return html + '</ul></details></div>';
  }

  function packingPreviewHtml(preview, done, result) {
    var html = '<div class="pd-packing"><p>' + escapeHtml(preview.tripTitle + ' · ' + preview.days + ' 天') + '</p>';
    if (result) {
      html += '<p>已加入「' + escapeHtml(result.listName) + '」的物品</p><ul class="pd-list">';
      html += (result.items || []).map(function (item) {
        return '<li>' + escapeHtml(item.name + ' × ' + item.quantity) + '</li>';
      }).join('');
      html += '</ul>';
      if (!(result.items || []).length) html += '<p>这次没有新增物品</p>';
      if ((result.skipped || []).length) html += '<p>确认时跳过已有物品：' + escapeHtml(result.skipped.join('、')) + '</p>';
      return html + '</div>';
    }
    html += '<p>' + escapeHtml(preview.weatherNote) + '</p>';
    if ((preview.duplicateSuggestions || []).length) {
      html += '<p>已合并重复建议：' + escapeHtml(preview.duplicateSuggestions.join('、')) + '</p>';
    }
    if (done) {
      return html + '<ul class="pd-list">' + (preview.items || []).map(function (item) {
        return '<li>' + escapeHtml(item.name + ' × ' + item.quantity) + '</li>';
      }).join('') + '</ul></div>';
    }
    html += '<div class="pd-packing-form"><label>目标清单<select class="pd-packing-target">'
      + '<option value="" disabled' + (preview.defaultTarget ? '' : ' selected') + '>请选择目标清单</option>'
      + (preview.targets || []).map(function (target) {
        return '<option value="' + escapeHtml(target.id) + '" data-duplicates="'
          + escapeHtml(JSON.stringify(target.duplicateIds || [])) + '"'
          + (target.id === preview.defaultTarget ? ' selected' : '') + '>'
          + escapeHtml(target.name + ' · ' + target.itemCount + ' 项') + '</option>';
      }).join('')
      + '<option value="new"' + (preview.defaultTarget === 'new' ? ' selected' : '') + '>创建新清单</option></select></label>'
      + '<label class="pd-packing-new" hidden>新清单名称<input type="text" maxlength="100" required value="'
      + escapeHtml(preview.newListName) + '"></label><ul class="pd-list">';
    html += (preview.items || []).map(function (item) {
      return '<li data-packing-item="' + escapeHtml(item.id) + '"><label><input type="checkbox" checked>'
        + '<span>' + escapeHtml(item.name) + '</span></label>'
        + '<input type="number" aria-label="' + escapeHtml(item.name + '数量') + '" min="1" max="999" step="1" required value="'
        + escapeHtml(item.quantity) + '"><span class="pd-packing-existing" hidden>已有，跳过</span></li>';
    }).join('');
    return html + '</ul><p class="pd-packing-status" role="status"></p></div></div>';
  }

  function syncPackingChoices(form) {
    var target = form.querySelector('.pd-packing-target');
    var existing = JSON.parse(target.selectedOptions[0].getAttribute('data-duplicates') || '[]');
    var create = target.value === 'new';
    form.querySelector('.pd-packing-new').hidden = !create;
    var selected = 0;
    form.querySelectorAll('[data-packing-item]').forEach(function (row) {
      var duplicate = existing.includes(row.getAttribute('data-packing-item'));
      var checkbox = row.querySelector('[type="checkbox"]');
      checkbox.disabled = duplicate;
      row.querySelector('[type="number"]').disabled = duplicate || !checkbox.checked;
      row.querySelector('.pd-packing-existing').hidden = !duplicate;
      if (checkbox.checked && !duplicate) selected++;
    });
    form.querySelector('.pd-packing-status').textContent = target.value
      ? '已选 ' + selected + ' 项' + (existing.length ? ' · 跳过 ' + existing.length + ' 项已有物品' : '')
      : '请选择已有清单或创建新清单';
    var apply = form.closest('.pet-draft').querySelector('.pd-btn.apply');
    apply.disabled = !target.value || !selected || form.dataset.busy === 'true';
    apply.textContent = create ? '创建并添加' : '添加所选物品';
  }

  function packingChoices(form) {
    var targetId = form.querySelector('.pd-packing-target').value;
    var items = [];
    for (var row of form.querySelectorAll('[data-packing-item]')) {
      var number = row.querySelector('[type="number"]');
      if (number.disabled) continue;
      if (!number.reportValidity()) return null;
      items.push({ id: row.getAttribute('data-packing-item'), quantity: number.valueAsNumber });
    }
    if (!targetId || !items.length) return null;
    var choices = { targetId: targetId, items: items };
    if (targetId === 'new') {
      var name = form.querySelector('.pd-packing-new input');
      name.value = name.value.trim();
      if (!name.reportValidity()) return null;
      choices.newListName = name.value;
    }
    return choices;
  }

  var RECEIPT_KINDS = { expense: '消费小票', flight: '机票', hotel: '酒店预订', train: '火车票',
    ticket: '门票', other: '其他预订' };

  function receiptReviewHtml(review, canEdit, messageId) {
    if (!canEdit) return '<div class="pd-receipt">已放弃票据核对</div>';
    var fields = review.fields || {};
    var uncertain = review.uncertain || [];
    function field(key, label, attrs, fallback) {
      return '<label>' + label + (uncertain.includes(key) ? ' <span>待核对</span>' : '')
        + '<input data-receipt-field="' + key + '" value="' + escapeHtml(fields[key] || fallback || '') + '" ' + attrs + '></label>';
    }
    var people = review.members || [];
    var html = '<div class="pd-receipt"><p>' + escapeHtml(review.tripTitle + ' · ' + review.timezone)
      + '</p><p>' + (review.recognition === 'manual' ? '没有可用的识别结果，可以对照图片手动填写。'
        : '请对照原图核对类别、日期和金额。') + '</p>';
    if (S.receiptImage && S.receiptImage.id === messageId) {
      html += '<details class="pd-receipt-image"><summary>查看所选图片</summary><img alt="所选票据图片" src="'
        + escapeHtml(S.receiptImage.url) + '"></details>';
    }
    html += '<label>票据类别<select data-receipt-field="kind"><option value="">请选择</option>'
      + Object.keys(RECEIPT_KINDS).map(function (key) {
        return '<option value="' + key + '"' + (key === fields.kind ? ' selected' : '') + '>' + RECEIPT_KINDS[key] + '</option>';
      }).join('') + '</select></label>'
      + field('title', '商户或服务名称', 'maxlength="120"')
      + '<div class="pd-receipt-expense">'
      + field('date', '消费日期', 'type="date"')
      + '<label>费用类别<select data-receipt-field="category"><option value="other">其他</option><option value="food">餐饮</option>'
      + '<option value="transport">交通</option><option value="hotel">住宿</option><option value="ticket">门票</option>'
      + '<option value="shopping">购物</option></select></label></div>'
      + '<div class="pd-receipt-booking">'
      + field('startTime', '开始日期或时间', 'placeholder="YYYY-MM-DD 或 YYYY-MM-DD HH:mm"')
      + field('startTimezone', '开始时区', 'maxlength="60"', review.timezone)
      + field('endTime', '结束日期或时间（选填）', 'placeholder="YYYY-MM-DD 或 YYYY-MM-DD HH:mm"')
      + field('endTimezone', '结束时区（选填）', 'maxlength="60" placeholder="默认与开始时区相同"')
      + '</div>'
      + field('amount', '实付金额', 'inputmode="decimal"')
      + field('currency', '原币代码', 'maxlength="3" placeholder="如 CNY、USD、JPY"');
    var totals = [fields.subtotal != null ? '小计 ' + fields.subtotal : '',
      fields.tax != null ? '税额 ' + fields.tax : ''].filter(Boolean);
    if (totals.length) html += '<p class="pd-receipt-totals">' + escapeHtml(totals.join(' · ')) + ' · 仅供核对</p>';
    html += '<div class="pd-receipt-expense"><label>付款和分摊<select class="pd-receipt-split">'
      + '<option value="">请选择</option><option value="default">本人付款、全员均分</option>'
      + '<option value="equal">指定成员均分</option><option value="ratio">按比例</option><option value="amount">按金额</option>'
      + '</select></label><div class="pd-receipt-custom" hidden><label>付款人<select data-receipt-field="payer">'
      + '<option value="">请选择付款人</option>'
      + people.map(function (person) {
        return '<option value="' + escapeHtml(person.userId) + '">' + escapeHtml(receiptMemberLabel(person)) + '</option>';
      }).join('') + '</select></label><ul class="pd-list">'
      + people.map(function (person) {
        var name = receiptMemberLabel(person);
        return '<li class="pd-receipt-person"><label><input type="checkbox" value="' + escapeHtml(person.userId) + '">'
          + escapeHtml(name) + '</label><input type="text" inputmode="decimal" aria-label="'
          + escapeHtml(name + '分摊值') + '" placeholder="份额" hidden></li>';
      }).join('') + '</ul></div></div>'
      + '<label class="pd-receipt-duplicate" hidden><input type="checkbox"> 已核对疑似重复项，仍生成草稿</label></div>';
    return html;
  }

  function receiptMemberLabel(person) {
    return person.displayName && person.username && person.displayName !== person.username
      ? person.displayName + '（' + person.username + '）' : (person.displayName || person.username || '行程成员');
  }

  function syncReceiptControls(form) {
    var kind = form.querySelector('[data-receipt-field="kind"]').value;
    var split = form.querySelector('.pd-receipt-split').value;
    form.querySelectorAll('.pd-receipt-expense').forEach(function (node) { node.hidden = kind !== 'expense'; });
    form.querySelector('.pd-receipt-booking').hidden = !kind || kind === 'expense';
    form.querySelector('.pd-receipt-custom').hidden = !split || split === 'default';
    form.querySelectorAll('.pd-receipt-person input[type="text"]').forEach(function (node) {
      node.hidden = split !== 'ratio' && split !== 'amount';
    });
  }

  async function confirmReceipt(button) {
    var form = button.closest('.pet-draft').querySelector('.pd-receipt');
    var data = {};
    form.querySelectorAll('[data-receipt-field]').forEach(function (field) {
      data[field.getAttribute('data-receipt-field')] = field.value.trim();
    });
    if (data.kind === 'expense') {
      var split = form.querySelector('.pd-receipt-split').value;
      if (!split) { toast('请选择付款人和分摊方式'); return; }
      if (split === 'default') data.useDefaultSplit = true;
      else {
        data.splitType = split;
        data.participants = Array.from(form.querySelectorAll('.pd-receipt-person input:checked'), function (check) {
          var participant = { member: check.value };
          var value = check.closest('li').querySelector('input[type="text"]').value.trim();
          if (split === 'ratio') participant.ratio = value;
          if (split === 'amount') participant.shareAmount = value;
          return participant;
        });
      }
    }
    data.acknowledgeDuplicate = form.querySelector('.pd-receipt-duplicate input').checked;
    button.disabled = true;
    var conversation = S.convId;
    try {
      var messageId = button.getAttribute('data-mid');
      await apiPost('/ai/conversations/' + encodeURIComponent(conversation) + '/receipts/'
        + encodeURIComponent(messageId) + '/confirm', data);
      if (S.receiptImage && S.receiptImage.id === messageId) clearReceiptImage();
      if (S.convId === conversation) await refresh();
    } catch (err) {
      if (err.status === 409 && humanError(err).includes('疑似重复')) {
        form.querySelector('.pd-receipt-duplicate').hidden = false;
        form.querySelector('.pd-receipt-duplicate input').checked = false;
      }
      toast(humanError(err));
    } finally {
      button.disabled = false;
    }
  }

  function clearReceiptImage() {
    if (!S.receiptImage) return;
    URL.revokeObjectURL(S.receiptImage.url);
    S.receiptImage = null;
  }

  async function sendReceipt(file) {
    if (S.receiptBusy || !S.tripBound || !S.tripId || !paramTripId()) return;
    if (file.size > 4 * 1024 * 1024 || !['image/jpeg', 'image/png'].includes(file.type)) {
      toast('请选择 4 MB 以内的 JPEG 或 PNG 图片');
      return;
    }
    S.receiptBusy = true;
    S.el.receipt.disabled = true;
    S.el.receipt.textContent = '识别中';
    try {
      var tripId = S.tripId;
      var conversation = await ensureConversation('核对票据截图', true);
      var image = await new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = function () { reject(new Error('图片读取失败，请重新选择')); };
        reader.readAsDataURL(file);
      });
      var result = await apiPost('/ai/conversations/' + encodeURIComponent(conversation) + '/receipts',
        { image: image, tripId: tripId });
      if (S.convId === conversation) {
        clearReceiptImage();
        if (S.bubbleOpen && result.status === 'pending') {
          S.receiptImage = { id: result.messageId, url: URL.createObjectURL(file) };
        }
        await refresh();
        if (result.status === 'discarded') toast('这张图片的核对草稿已放弃');
      }
    } catch (err) {
      toast(humanError(err));
    } finally {
      S.receiptBusy = false;
      S.el.receipt.disabled = false;
      S.el.receipt.textContent = '票据';
    }
  }

  /* 草稿卡片：与行程详情抽屉一致的视觉与字段（含 时间/票价/营业时间） */
  function draftHtml(m) {
    var d = m.draft || {};
    var status = m.draftStatus;
    var done = status === 'applied' || status === 'discarded';
    var html = '<div class="pet-draft' + (done ? ' done' : '') + '">';
    html += '<div class="pd-h">' + escapeHtml(DRAFT_KIND[d.type] || '变更预览') + '</div>';
    html += '<div class="pd-b">';
    if (d.type === 'packing' && d.packingPreview) {
      html += packingPreviewHtml(d.packingPreview, done, m.packingResult);
    } else if (d.type === 'receipt_review' && d.receiptReview) {
      html += receiptReviewHtml(d.receiptReview, !done, m.id);
    } else {
      html += '<div class="pd-s">' + escapeHtml(d.summary || m.content || '') + '</div>';
    }
    if (d.type === 'expense' && d.expense) {
      var e = d.expense;
      if (e.spentAt) html += '<div class="pd-s">消费日期 ' + escapeHtml(String(e.spentAt).slice(0, 10)) + '</div>';
      var p = d.expensePreview;
      if (p) {
        var names = p.memberLabels || {};
        var splitLabels = { equal: p.allMembers ? '全员均分' : '均分', ratio: '按比例', amount: '按金额' };
        var lines = [
          ['行程', p.tripTitle],
          ['付款人', names[p.payerUserId]],
          ['分摊方式', splitLabels[p.splitType]]
        ];
        html += '<ul class="pd-list pd-expense">' + lines.map(function (line) {
          return '<li><span class="op">' + escapeHtml(line[0]) + '</span><span>' + escapeHtml(line[1] || '—') + '</span></li>';
        }).join('') + (p.shares || []).map(function (share) {
          return '<li><span class="pd-share-name">' + escapeHtml(names[share.userId] || '行程成员') + '</span>'
            + '<strong>' + escapeHtml(p.currency + ' ' + share.shareAmount) + '</strong></li>';
        }).join('') + '</ul>';
      } else {
        var bits = [];
        if (e.amount) bits.push('CNY ' + e.amount);
        if (e.category) bits.push(e.category);
        if (e.title) bits.push(e.title);
        if (bits.length) html += '<ul class="pd-list"><li><span class="op">记账</span>' + escapeHtml(bits.join(' · ')) + '</li></ul>';
      }
    }
    if (d.type === 'booking' && d.booking) {
      var b = d.booking;
      var bkLabels = { flight: '机票', hotel: '酒店', train: '火车票', ticket: '门票', other: '预订' };
      var bkBits = [];
      if (b.startTime) bkBits.push(String(b.startTime) + (b.endTime ? ' ~ ' + String(b.endTime) : ''));
      if (b.amount != null && b.amount !== '') bkBits.push((b.currency ? b.currency + ' ' : '¥') + String(b.amount));
      if (b.orderNo) bkBits.push('订单号 ' + String(b.orderNo));
      if (b.confirmCode) bkBits.push('确认码 ' + String(b.confirmCode));
      if (b.dayIndex != null) bkBits.push('第 ' + b.dayIndex + ' 天');
      html += '<ul class="pd-list"><li><span class="op">' + (bkLabels[b.type] || '预订') + '</span>'
        + escapeHtml(b.title || '')
        + (bkBits.length ? '<span class="dmeta">' + escapeHtml(bkBits.join(' · ')) + '</span>' : '')
        + '</li></ul>';
    }
    if (d.routeAdjustment && d.routeAdjustment.preview) {
      html += routePreviewHtml(d.routeAdjustment.preview);
    } else if (d.type === 'itinerary_change' && Array.isArray(d.changes)) {
      html += '<ul class="pd-list">' + d.changes.slice(0, 12).map(function (c) {
        var op = c.action === 'update' ? '修改' : (c.action === 'remove' || c.action === 'delete' ? '删除' : '新增');
        var txt = (c.name || c.placeId || '');
        if (c.dayIndex != null) txt += ' · 第 ' + c.dayIndex + ' 天';
        var meta = [];
        if (c.startTime && c.endTime) meta.push(escapeHtml(c.startTime + '–' + c.endTime));
        else if (c.startTime) meta.push(escapeHtml(c.startTime));
        if (c.estimatedCost != null && c.estimatedCost !== '') meta.push('¥' + escapeHtml(String(c.estimatedCost)));
        if (c.openTime) meta.push('营业 ' + escapeHtml(c.openTime));
        if (c.address) meta.push(escapeHtml(String(c.address).slice(0, 30)));
        if (c.notes) {
          var note = String(c.notes);
          meta.push(escapeHtml(note.length > 28 ? note.slice(0, 28) + '…' : note));
        }
        return '<li><span class="op">' + op + '</span><span class="pd-name">' + escapeHtml(txt) + '</span>'
          + (meta.length ? '<span class="dmeta">' + meta.join(' · ') + '</span>' : '') + '</li>';
      }).join('') + '</ul>';
      if (d.changes.length > 12) {
        html += '<div class="pd-more">另有 ' + (d.changes.length - 12) + ' 项未展开</div>';
      }
      var addDays = {};
      d.changes.forEach(function (c) {
        if (c.action === 'add' && c.dayIndex != null) addDays[c.dayIndex] = (addDays[c.dayIndex] || 0) + 1;
      });
      var coverDays = Object.keys(addDays).filter(function (k) {
        return d.replaceDay === true || (d.replaceDay !== false && addDays[k] >= 2);
      });
      if (coverDays.length) {
        html += '<div class="pd-cover">应用后将覆盖第 ' + escapeHtml(coverDays.join('、')) + ' 天原有安排</div>';
      }
    }
    html += '</div>';
    if (done) {
      html += '<div class="pd-stat ' + (status === 'applied' ? 'ok' : 'no') + '">'
        + (status === 'applied' ? '✓ 已应用' : '已放弃') + '</div>';
    } else {
      html += '<div class="pd-acts">'
        + '<button type="button" class="pd-btn apply" data-draft="' + (d.type === 'receipt_review' ? 'confirm-receipt' : 'apply')
        + '" data-mid="' + escapeHtml(m.id) + '">' + (d.type === 'receipt_review' ? '生成待确认草稿' : '应用') + '</button>'
        + '<button type="button" class="pd-btn discard" data-draft="discard" data-mid="' + escapeHtml(m.id) + '">放弃</button>'
        + '</div>';
    }
    html += '</div>';
    return html;
  }

  function optionsHtml(opt) {
    var list = opt && opt.options;
    if (!Array.isArray(list) || !list.length) return '';
    return '<div class="pet-opts">' + list.map(function (o) {
      var isObj = o && typeof o === 'object';
      var label = isObj ? String(o.label || '') : String(o);
      var desc = isObj && o.description ? String(o.description) : '';
      if (!label) return '';
      return '<button type="button" class="pet-opt' + (desc ? ' has-desc' : '') + '"'
        + ' data-send="' + escapeHtml(label) + '"'
        + ' title="' + escapeHtml(desc || label) + '">'
        + '<span class="po-label">' + escapeHtml(label) + '</span>'
        + (desc ? '<span class="po-desc">' + escapeHtml(desc) + '</span>' : '')
        + '</button>';
    }).join('') + '</div>';
  }

  /* AI 文本轻量渲染：**加粗**、Markdown 表格→列表行、去掉标题井号；其余按纯文本（换行由 pre-wrap 保留） */
  function mdText(src) {
    var t = escapeHtml(String(src || ''));
    t = t.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    if (/(^|\n)[ \t]*\|/.test(t)) {
      t = t.split('\n').map(function (ln) {
        var s = ln.trim();
        if (/^\|/.test(s)) {
          if (/^\|[\s:|-]+\|?$/.test(s)) return ''; /* 表头分隔行 */
          var cells = s.replace(/^\|/, '').replace(/\|$/, '').split('|')
            .map(function (c) { return c.trim(); }).filter(Boolean);
          return cells.length ? '· ' + cells.join(' · ') : '';
        }
        return ln;
      }).join('\n').replace(/\n{3,}/g, '\n\n');
    }
    t = t.replace(/^#{1,6}[ \t]+/gm, '');
    return t;
  }

  function msgHtml(m, isLast) {
    var isUser = m.role === 'user';
    if (!isUser && m.draft && m.draftStatus !== undefined) {
      /* 草稿消息：直接渲染卡片（卡片已含摘要与字段） */
      return '<div class="pet-msg ai msg-draft">' + draftHtml(m) + '</div>';
    }
    var nav = (!isUser && !m.draftStatus && m.navigation) ? navChipHtml(m.navigation) : '';
    /* 选择按钮仅在最新一条消息上可点，历史消息不再给出过时选项 */
    var opts = (!isUser && !m.draftStatus && isLast && m.options) ? optionsHtml(m.options) : '';
    var txt = isUser ? escapeHtml(m.content || '') : mdText(m.content);
    return '<div class="pet-msg ' + (isUser ? 'user' : 'ai') + '">'
      + '<div class="txt">' + txt + '</div>' + nav + opts + '</div>';
  }

  function renderMessages(items) {
    if (!items.length) { renderEmpty(); return; }
    S.el.body.innerHTML = ''
      + '<div class="pb-msgs" id="petMsgs">'
      +   items.map(function (m, i) { return msgHtml(m, i === items.length - 1); }).join('')
      + '</div>';
    var box = S.el.body.querySelector('#petMsgs');
    Array.prototype.forEach.call(box.querySelectorAll('.pet-opt'), function (btn) {
      btn.addEventListener('click', function () {
        var wrap = btn.closest('.pet-opts');
        if (wrap) wrap.remove();
        send(btn.getAttribute('data-send') || btn.textContent);
      });
    });
    box.querySelectorAll('.pd-packing-form').forEach(syncPackingChoices);
    box.querySelectorAll('.pd-receipt [data-receipt-field="kind"]').forEach(function (kind) {
      syncReceiptControls(kind.closest('.pd-receipt'));
    });
    box.scrollTop = box.scrollHeight;
    /* 多天规划草稿卡片较长时，末尾的「应用」按钮可能被挤出可视区，这里再把卡片底部拉进视野 */
    var applyBtns = box.querySelectorAll('.msg-draft .pet-draft .pd-btn.apply');
    var applyBtn = applyBtns[applyBtns.length - 1];
    if (applyBtn) {
      var overflow = applyBtn.getBoundingClientRect().bottom - box.getBoundingClientRect().bottom;
      if (overflow > 0) box.scrollTop += overflow + 12;
    }
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

  async function ensureConversation(firstText, requireTrip) {
    if (S.convId) return S.convId;
    var payload = { title: newTitle(firstText) };
    if (S.tripId && S.tripBound) payload.tripId = S.tripId;
    var created;
    try {
      created = await apiPost('/ai/conversations', payload);
    } catch (err) {
      if (requireTrip) throw err;
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

  function fetchMessages(conversationId) {
    return apiGet('/ai/conversations/' + encodeURIComponent(conversationId || S.convId)
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
    var form = kind === 'apply' && btn && btn.closest('.pet-draft').querySelector('.pd-packing-form');
    var choices = form ? packingChoices(form) : {};
    if (!choices) return;
    if (form) form.dataset.busy = 'true';
    if (btn) btn.disabled = true; // 体验优化：请求在途时禁用按钮（后端条件更新才是并发保证）
    try {
      var result = await apiPost('/ai/conversations/' + encodeURIComponent(S.convId)
        + '/' + kind + '/' + encodeURIComponent(messageId), choices);
      var packing = result && result.packing;
      if (kind === 'discard' && S.receiptImage && S.receiptImage.id === messageId) clearReceiptImage();
      toast(packing ? '新增 ' + result.appliedCount + ' 项打包物品'
        : (kind === 'apply' ? '已应用变更' : '已放弃该草稿'));
      if (packing) dispatchApplied(kind, messageId, packing);
      await refresh();
      if (!packing) dispatchApplied(kind, messageId);
    } catch (err) {
      if (btn) btn.disabled = false;
      if (form) {
        delete form.dataset.busy;
        syncPackingChoices(form);
      }
      toast(humanError(err));
      // 409（正在应用中/状态已变化）等：同步服务端权威状态，避免用户基于过期状态重复点击
      try { await refresh(); } catch (e) { /* ignore */ }
    }
  }

  function dispatchApplied(kind, messageId, packing) {
    var detail = { kind: kind, messageId: messageId, conversationId: S.convId,
      tripId: packing ? packing.tripId : S.tripId, packing: packing };
    try {
      window.dispatchEvent(new CustomEvent('travelos:ai-applied', { detail: detail }));
    } catch (e) { /* ignore */ }
    if (typeof window.TravelOSOnAiApplied === 'function') {
      try { window.TravelOSOnAiApplied(detail); } catch (e) { /* ignore */ }
    }
  }

  /* 流式发送：每次发送携带稳定 clientRequestId（与普通接口共享服务端幂等键）。
   * 失败去向严格遵循 policy：
   *  - 能确认未被服务端接受（不支持流式 / 无 Token / 401 / 403）→ 自动改用普通接口（同一 ID）；
   *  - 可能已被接受（网络中断 / 5xx / 读体失败 / 流中断）→ 只拉权威消息列表对齐，不盲目重发；
   *  - 服务端明确拒绝（429 等）→ 抛出展示，不重发；
   *  - 用户主动停止 → 中断请求（服务端同步取消），既不重发也不恢复。 */
  async function streamMessage(text, box, requestId) {
    var endpoint = '/ai/conversations/' + encodeURIComponent(S.convId) + '/messages/stream';
    var normalSend = function () {
      return apiPost('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages',
        { content: text, clientRequestId: requestId });
    };
    var token = getToken();
    var controller = (typeof AbortController === 'function') ? new AbortController() : null;
    S.streamAbort = controller;
    try {
      var decision = policy.decideStreamFallback({
        streamSupported: typeof fetch === 'function' && typeof ReadableStream !== 'undefined',
        hasToken: !!token
      });
      if (decision.action === 'fallback') return await normalSend();

      var res = null;
      var fetchFailed = false;
      try {
        res = await fetch(API_BASE + endpoint, {
          method: 'POST',
          credentials: 'same-origin',
          signal: controller ? controller.signal : undefined,
          headers: {
            'Accept': 'text/event-stream',
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + token,
          },
          body: JSON.stringify({ content: text, clientRequestId: requestId }),
        });
      } catch (e) {
        fetchFailed = true; // 请求可能已被服务端接受：禁止直接重发
      }
      if (controller && controller.signal.aborted) return { cancelled: true };

      decision = policy.decideStreamFallback({
        streamSupported: true,
        hasToken: !!token,
        fetchFailed: fetchFailed,
        status: res ? res.status : undefined,
        bodyReadable: !!(res && res.body),
      });
      if (decision.action === 'fallback') return await normalSend();
      if (decision.action === 'abort') {
        var message = '发送失败，请稍后重试';
        if (res) {
          try {
            var body = await res.json();
            if (body && body.message) message = body.message;
          } catch (e) { /* ignore */ }
        }
        var err = new Error(message);
        err.status = res ? res.status : 0;
        err.reason = decision.reason;
        throw err;
      }
      if (decision.action === 'align') return await alignOrFallback(text, requestId);

      removeTyping();
      box.insertAdjacentHTML('beforeend',
        '<div class="pet-msg ai msg-streaming"><div class="txt"><span class="pet-typing"><i></i><i></i><i></i></span></div></div>');
      var streamEl = box.querySelector('.msg-streaming .txt');
      var acc = '';
      var buf = '';
      var decoder = new TextDecoder();
      var reader = res.body.getReader();
      var eventName = '';
      var result;
      try {
        for (;;) {
          var step = await reader.read();
          if (step.done) break;
          buf += decoder.decode(step.value, { stream: true });
          var parts = buf.split('\n');
          buf = parts.pop();
          for (var i = 0; i < parts.length; i++) {
            var line = parts[i].trim();
            if (line.slice(0, 6) === 'event:') { eventName = line.slice(6).trim(); continue; }
            if (line.slice(0, 5) !== 'data:') continue;
            var payload = line.slice(5).trim();
            if (!payload) continue;
            var obj = null;
            try { obj = JSON.parse(payload); } catch (e) { continue; }
            if (obj && typeof obj.text === 'string' && obj.text) {
              acc += obj.text;
              streamEl.innerHTML = mdText(acc);
              box.scrollTop = box.scrollHeight;
            } else if (obj && ['done', 'error', 'inprogress', 'cancelled'].includes(eventName)) {
              result = Object.assign({ streamEvent: eventName }, obj);
            }
            eventName = '';
          }
        }
      } catch (e) {
        // 中途失败：区分「用户主动停止」与「意外中断」——
        // 停止 = 绝不恢复/重发（服务端已取消任务，且再次发送必须是新 ID）；
        // 意外中断 = 只对齐权威状态，不盲目重发（可能已部分生成）。
        if (controller && controller.signal.aborted) return { cancelled: true };
        return await alignOrFallback(text, requestId);
      }
      // 正常/服务端 error 事件结束：由 send() 拉取权威消息列表渲染
      return result;
    } finally {
      S.streamAbort = null;
    }
  }

  /** 流式失败后的“对齐优先”处理：读取权威消息列表，仅在确认未落库时用同一 ID 回退。 */
  async function alignOrFallback(text, requestId) {
    var messages = null;
    try {
      var d = await fetchMessages();
      messages = d && d.messages;
    } catch (e) {
      messages = null; // 会话/网络异常：无法确认 → 不重发
    }
    if (messages === null) return;
    if (policy.shouldFallbackAfterAlign(messages, text)) {
      return apiPost('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages',
        { content: text, clientRequestId: requestId });
    }
    // 已存在该用户消息：仅对齐（send() 随后会刷新渲染）
  }

  /** 用户主动停止：中断流式请求 → 服务端 SSE 回调立即取消生成任务。 */
  function stopSending() {
    if (S.streamAbort) {
      try { S.streamAbort.abort(); } catch (e) { /* ignore */ }
    }
  }

  async function send(preset, plan, requireTrip) {
    if (S.sending || (S.planning && !plan)) return;
    var text = String(preset != null ? preset : S.el.input.value).trim();
    if (!text) return;
    /* 每次发送生成稳定 clientRequestId（普通/流式共享幂等键；停止后的新发送必然是新 ID） */
    var clientRequestId = plan ? plan.turnId : policy.newRequestId();
    S.sending = true;
    var sendBtn = S.el.send;
    var sendLabel = sendBtn ? sendBtn.textContent : '';
    if (sendBtn) sendBtn.textContent = '■ 停止'; // 发送中按钮变为停止
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
      if (!plan) await ensureConversation(text, requireTrip);
      var result = await streamMessage(text, box, clientRequestId);
      /* 与行程详情 AI 面板同一模式：发送后拉取完整消息列表（草稿状态由后端装配，
       * 同时也是流式中断/停止后的权威对齐——不会再次发送同一条消息） */
      var d = await fetchMessages();
      var items = (d.messages || []).slice();
      removeTyping();
      renderMessages(items);
      var last = items[items.length - 1];
      if (!plan && last && last.role === 'assistant' && last.navigation && last.navigation.url) {
        setTimeout(function () { window.location.href = last.navigation.url; }, 1200);
      }
      return { messages: items, result: result };
    } catch (err) {
      removeTyping();
      box.insertAdjacentHTML('beforeend',
        '<div class="pet-msg ai"><div class="txt">' + escapeHtml(humanError(err)) + '</div></div>');
      box.scrollTop = box.scrollHeight;
      if (plan) throw err;
    } finally {
      S.sending = false;
      if (sendBtn) sendBtn.textContent = sendLabel;
      if (S.bubbleOpen) S.el.input.focus();
    }
  }

  async function showBubble(knownMessages) {
    if (S.bubbleOpen) {
      if (!S.mobile) setExpanded(true);
      return;
    }
    if (S.snapped) showFull(240);
    buildBubble();
    S.expanded = true;
    S.bubbleOpen = true;
    S.pet.classList.add('bubble-open');
    setState('idle', S.facing);
    S.bubble.classList.add('open');
    applyLayout();
    if (modalActive()) loadConversations(); else placeBubble();
    if (!isLoggedIn()) {
      renderLogin();
      S.el.close.focus();
      return;
    }
    S.el.inputRow.hidden = false;
    S.el.receipt.hidden = !S.tripBound || !paramTripId();
    S.el.input.focus();
    if (Array.isArray(knownMessages)) {
      if (knownMessages.length) renderMessages(knownMessages); else renderEmpty();
      return;
    }
    S.el.body.innerHTML = '<div class="pet-msg ai"><div class="txt"><span class="pet-typing"><i></i><i></i><i></i></span></div></div>';
    await loadHistory();
  }

  function planStatus(message, retry) {
    S.el.plan.hidden = false;
    S.el.plan.querySelector('[role="status"]').textContent = message;
    S.el.planRetry.hidden = !retry;
  }

  function planHasDraft(messages, tripId) {
    return messages.some(function (message) {
      return message.draft && message.draft.type === 'itinerary_change' && message.draft.tripId === tripId;
    });
  }

  async function runPlan(retry) {
    if (S.planning || S.sending || !isLoggedIn() || !global.TravelOSPlanning) return;
    var params = new URLSearchParams(global.location.search);
    if (currentPath() !== '/trip-detail/' || params.get('aiAuto') !== '1') return;
    var plan = TravelOSPlanning.read();
    if (!plan || plan.tripId !== params.get('id') || !plan.conversationId ||
        !['ready', 'sending', 'retry'].includes(plan.stage)) return;

    S.planning = true;
    try {
      await showBubble([]);
      planStatus('正在核对本行程的规划进度…', false);
      var history = await fetchMessages(plan.conversationId);
      if (!history.conversation || history.conversation.id !== plan.conversationId ||
          history.conversation.tripId !== plan.tripId || S.tripId !== plan.tripId) {
        throw new Error('规划会话与当前行程不一致，请返回首页查看本次规划。');
      }
      S.convId = plan.conversationId;
      S.tripBound = true;
      saveConvId(S.convId);
      renderBar();
      var messages = history.messages || [];
      if (messages.length) renderMessages(messages);
      if (!planHasDraft(messages, plan.tripId) && (retry || plan.stage === 'ready')) {
        plan.stage = 'sending';
        TravelOSPlanning.save(plan);
        planStatus('正在生成行程草稿…', false);
        var text = '请为这次' + plan.destination + '之旅规划行程（' + plan.start + ' 至 ' + plan.end
          + '，共 ' + plan.days + ' 天）。结合可用的天气和地点资料规划每日景点、用餐及住宿，'
          + '并在本轮生成行程变更草稿供我确认。';
        var outcome = await send(text, plan);
        messages = outcome.messages;
        var result = outcome.result || {};
        if (!planHasDraft(messages, plan.tripId)) {
          // 已完成但未产出草稿，或上次已取消时，用户下一次重试应发起新一轮。
          if (result.userMessage || result.streamEvent === 'done' || result.cancelled || result.streamEvent === 'cancelled') {
            plan.turnId = policy.newRequestId();
          }
          if (result.inProgress || result.streamEvent === 'inprogress') {
            throw new Error('上一轮规划仍在运行，请稍后查看结果。');
          }
          throw new Error(result.message || '暂未生成草稿，可以重试或在对话中补充要求。');
        }
      }
      if (planHasDraft(messages, plan.tripId)) {
        plan.stage = 'done';
        delete plan.error;
        TravelOSPlanning.save(plan);
        params.delete('aiAuto');
        global.history.replaceState(global.history.state, '', global.location.pathname + '?' + params + global.location.hash);
        planStatus('行程草稿已生成，请预览后应用。', false);
      } else {
        planStatus('上次规划未完成。重试会继续使用当前行程。', true);
      }
    } catch (err) {
      plan.stage = 'retry';
      try { TravelOSPlanning.save(plan); } catch (storageError) { err = storageError; }
      planStatus(humanError(err), true);
    } finally {
      S.planning = false;
      renderBar();
    }
  }

  function hideBubble() {
    if (!S.bubbleOpen) return;
    clearReceiptImage();
    S.expanded = false;
    S.bubble.classList.remove('open');
    S.bubbleOpen = false;
    S.pet.classList.remove('bubble-open');
    applyLayout();
    if (S.snapped) snapTo(S.snapped, 260);
    if (S.pet) S.pet.focus();
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
      var inline = S.opt.inlineLauncher === true;
      S.pet = document.createElement(inline ? 'button' : 'div');
      S.pet.className = 'pet';
      S.pet.setAttribute('data-state', 'idle');
      S.pet.setAttribute('data-facing', 'right');
      S.pet.setAttribute('aria-label', 'AI 旅行助手');
      S.pet.setAttribute('aria-haspopup', 'dialog');
      S.pet.setAttribute('aria-expanded', 'false');
      if (inline) {
        S.pet.type = 'button';
        S.pet.innerHTML = '<span class="body-wrap" aria-hidden="true"></span><span>AI 旅行助手</span>';
        S.pet.addEventListener('click', showBubble);
      } else {
        S.pet.setAttribute('role', 'button');
        S.pet.setAttribute('tabindex', '0');
        S.pet.innerHTML = ''
          + '<div class="shadow" aria-hidden="true"></div>'
          + '<div class="body-wrap" aria-hidden="true"></div>'
          + '<span class="name-chip" aria-hidden="true">' + escapeHtml(S.opt.name) + ' · 点击聊聊</span>';
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
      }
      S.host.appendChild(S.pet);
      window.addEventListener('resize', function () {
        if (!S.pet) return;
        syncMobile();
        if (!inline) {
          if (S.snapped) snapTo(S.snapped, 0);
          else { clampPos(); moveTo(S.x, S.y, 0); }
        }
        placeBubble();
      });
      window.addEventListener('scroll', function () { placeBubble(); }, { passive: true });
      document.addEventListener('keydown', function (e) {
        if (!S.bubbleOpen || e.defaultPrevented) return;
        if (e.key === 'Escape') {
          hideBubble();
        } else if (e.key === 'Tab' && modalActive()) {
          var focusable = Array.from(S.bubble.querySelectorAll('a[href], button, input, select')).filter(function (el) {
            return !el.disabled && el.getClientRects().length > 0;
          });
          var edge = e.shiftKey ? focusable[0] : focusable[focusable.length - 1];
          var next = e.shiftKey ? focusable[focusable.length - 1] : focusable[0];
          if (next && (document.activeElement === edge || !S.bubble.contains(document.activeElement))) {
            e.preventDefault();
            next.focus();
          }
        }
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
    if (!S.opt.inlineLauncher) {
      var b = bounds();
      S.x = b.maxX - 8;
      S.y = b.maxY - 6;
      clampPos();
      moveTo(S.x, S.y, 0);
    }
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

  async function generatePacking(tripId) {
    if (!S.host || !S.tripBound || S.tripId !== tripId) return false;
    await showBubble();
    await send('请根据当前行程生成打包清单草稿。', null, true);
    return true;
  }

  global.TravelOSAi = {
    __loaded: true,
    init: init,
    open: showBubble,
    close: hideBubble,
    showBubble: showBubble,
    hideBubble: hideBubble,
    setTrip: setTrip,
    generatePacking: generatePacking,
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

  /* 自动初始化。只有匹配当前行程的一键规划请求可以自动发送。 */
  function autoInit() {
    if (global.TravelOSPetAuto === false) return;
    init({ tripId: resolvePageTripId() });
    runPlan(false);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', autoInit);
  } else {
    autoInit();
  }
})(window);
