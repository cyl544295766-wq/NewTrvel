/* ============================================================
 * Travel OS · AI 旅行宠物（独立模块）
 *
 * 功能：
 *   1. 宠物角色在屏幕范围内自由漫游（边界可配置，随机游走 + 待机停歇）
 *   2. 点击宠物弹出对话气泡，接现有 AI 助手后端（/api/ai/conversations）
 *
 * 依赖：assets/css/pet.css（样式）、assets/js/api.js（apiGet/apiPost/isLoggedIn）。
 * AI 会话使用全局会话（不带 tripId）；会话 id 存 localStorage 跨页延续。
 *
 * 集成（任意页面）：
 *   <link rel="stylesheet" href="../assets/css/pet.css">
 *   <script src="../assets/js/api.js"></script>
 *   <script src="../assets/js/pet.js"></script>
 *   <script> window.TravelOSPet && TravelOSPet.init({ name: '游游' }); </script>
 *
 * 公开 API：
 *   TravelOSPet.init(options)   初始化（幂等，重复调用只刷新配置）
 *   TravelOSPet.stop()          暂停漫游并移除 DOM
 *   TravelOSPet.showBubble() / hideBubble()  手动开关对话气泡
 * ============================================================ */
(function (global) {
  'use strict';

  var CONV_KEY = 'travelos.pet.convId';

  var DEFAULTS = {
    name: '游游',          /* 宠物名（名牌与气泡标题） */
    role: 'AI 旅行伙伴',
    zIndex: 90,
    pad: 26,              /* 距屏幕四边的最小间距（拖拽边界） */
    dragThreshold: 6,     /* 位移超过该像素判定为拖拽，否则视为点击 */
    snap: { zone: 90 },   /* 松手时距边缘 ≤90px 自动吸附 */
    /* 对话气泡示例问题（空态展示，点击即发送） */
    chips: [
      '帮我想想周末短途去哪玩',
      '旅行打包清单要带什么',
      '怎么记旅行账目最省心'
    ]
  };

  var S = {
    opt: null,
    host: null, pet: null, bubble: null, el: {},
    x: 0, y: 0,                 /* 当前 translate 坐标（左上角；吸附时可为负/超界） */
    facing: 'right',
    state: 'idle',
    snapped: null,              /* 吸附边：null / left / right / top / bottom */
    drag: null,                 /* 拖拽上下文（pointerdown 期间存在） */
    bubbleOpen: false,
    convId: null,
    sending: false
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
  /* 移动：ms > 0 走过渡动画（吸附/悬停恢复），ms = 0 立即到位（拖拽跟随） */
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
  /* 吸附后元素半隐于屏幕外，悬停恢复完整显示；S.snapped 记录吸附边 */
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
    if (cand[0].d > S.opt.snap.zone) return;   /* 不在吸附区：原地停留 */
    snapTo(cand[0].edge, ms);
  }

  /* ---------- 拖拽（Pointer Events，鼠标与触摸统一）---------- */
  function onPointerDown(e) {
    if (e.button !== undefined && e.button !== 0) return;  /* 仅主键 */
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
      S.snapped = null;         /* 真正开始拖拽才离开吸附态 */
    }
    if (!S.drag.moved) return;    /* 未超阈值：仍算点按，不跟随 */
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
      maybeSnap(420);             /* 松手：靠边则平滑吸附，否则原地停留 */
    } else if (ctx.wasSnapped) {
      showFull(240);              /* 点按吸附中的宠物：先恢复完整显示（保持吸附态，关闭气泡后回半隐藏） */
      showBubble();
    } else if (S.bubbleOpen) {
      hideBubble();
    } else {
      showBubble();
    }
  }

  /* ---------- 对话气泡 ---------- */
  function buildBubble() {
    if (S.bubble) return;
    var b = document.createElement('div');
    b.className = 'pet-bubble';
    b.innerHTML = ''
      + '<div class="pb-head">'
      +   '<span class="av">🐾</span>'
      +   '<div><div class="nm">' + escapeHtml(S.opt.name) + '</div>'
      +   '<div class="role">' + escapeHtml(S.opt.role) + '</div></div>'
      +   '<button type="button" class="x" title="收起" aria-label="收起对话">✕</button>'
      + '</div>'
      + '<div class="pb-body"></div>'
      + '<div class="pb-input" hidden>'
      +   '<input type="text" id="petInput" maxlength="200" placeholder="和' + escapeHtml(S.opt.name) + '聊聊旅行…">'
      +   '<button type="button" id="petSend" title="发送">➤</button>'
      + '</div>';
    document.body.appendChild(b);
    S.bubble = b;
    S.el.body = b.querySelector('.pb-body');
    S.el.inputRow = b.querySelector('.pb-input');
    S.el.input = b.querySelector('#petInput');
    S.el.send = b.querySelector('#petSend');
    b.querySelector('.x').addEventListener('click', hideBubble);
    /* 注意：不能把事件处理函数直接绑到 send 上 —— 否则事件对象会冒充 preset 参数 */
    S.el.send.addEventListener('click', function () { send(); });
    S.el.input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
    });
  }

  function placeBubble() {
    if (!S.bubble || !S.bubbleOpen) return;
    var bw = S.bubble.offsetWidth || 316;
    var bh = S.bubble.offsetHeight || 360;
    var left = Math.min(Math.max(S.x + 23 - bw / 2, 10), window.innerWidth - bw - 10);
    S.bubble.style.left = Math.round(left) + 'px';
    var top = S.y - bh - 16;
    S.bubble.style.top = Math.round(Math.max(10, top)) + 'px';
    /* 尾巴（::after）通过 CSS 变量对准宠物横向中心 */
    var tailX = Math.round(S.x + 23 - left - 6);
    S.bubble.style.setProperty('--tail-x', Math.min(Math.max(tailX, 14), bw - 20) + 'px');
  }

  function renderEmpty() {
    S.el.body.innerHTML = ''
      + '<div class="pet-empty">'
      +   '<div class="e1">嗨，我是 ' + escapeHtml(S.opt.name) + ' 🐾</div>'
      +   '<div class="e2">旅行计划、打包、记账…点什么都可以问我</div>'
      + '</div>'
      + '<div class="pet-chips">'
      +   S.opt.chips.map(function (c) {
        return '<button type="button" class="pet-chip">' + escapeHtml(c) + '</button>';
      }).join('')
      + '</div>';
    Array.prototype.forEach.call(S.el.body.querySelectorAll('.pet-chip'), function (btn) {
      btn.addEventListener('click', function () { send(btn.textContent); });
    });
    placeBubble();
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

  function msgHtml(m) {
    var isUser = m.role === 'user';
    var draft = (!isUser && m.draft && m.draftStatus === 'pending')
      ? '<a class="draft-chip" href="'
        + (m.tripId ? '/trip-detail/?id=' + encodeURIComponent(m.tripId) : '/trips/')
        + '" target="_blank" rel="noopener">已生成变更草稿 · 去行程确认</a>'
      : '';
    var nav = (!isUser && !m.draftStatus) ? navChipHtml(m.navigation) : '';
    return '<div class="pet-msg ' + (isUser ? 'user' : 'ai') + '">'
      + '<div class="txt">' + escapeHtml(m.content || '') + '</div>' + draft + nav + '</div>';
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

  async function ensureConversation(firstText) {
    if (S.convId) return S.convId;
    var created = await apiPost('/ai/conversations', {
      title: ('宠物·' + String(firstText || '对话')).slice(0, 24)
    });
    S.convId = created.conversation.id;
    try { localStorage.setItem(CONV_KEY, S.convId); } catch (e) { /* 隐私模式忽略 */ }
    return S.convId;
  }

  async function loadHistory() {
    S.convId = null;
    try { S.convId = localStorage.getItem(CONV_KEY) || null; } catch (e) { /* ignore */ }
    if (!S.convId) { renderEmpty(); return; }
    try {
      var d = await apiGet('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages?page=1&pageSize=30');
      var items = (d.messages || []).slice().reverse();
      if (!items.length) { renderEmpty(); return; }
      renderMessages(items);
    } catch (err) {
      /* 会话可能已不存在：清掉本地引用回到空态 */
      S.convId = null;
      try { localStorage.removeItem(CONV_KEY); } catch (e) { /* ignore */ }
      renderEmpty();
    }
  }

  async function send(preset) {
    if (S.sending) return;
    var text = String(preset != null ? preset : S.el.input.value).trim();
    if (!text) return;
    S.sending = true;
    S.el.send.disabled = true;
    S.el.input.value = '';
    /* 本地乐观回显 */
    var box = S.el.body.querySelector('#petMsgs');
    if (!box) { S.el.body.innerHTML = '<div class="pb-msgs" id="petMsgs"></div>'; box = S.el.body.querySelector('#petMsgs'); }
    var empty = S.el.body.querySelector('.pet-empty');
    if (empty) empty.remove();
    var chips = S.el.body.querySelector('.pet-chips');
    if (chips) chips.remove();
    box.insertAdjacentHTML('beforeend',
      '<div class="pet-msg user"><div class="txt">' + escapeHtml(text) + '</div></div>');
    box.scrollTop = box.scrollHeight;
    appendTyping();
    try {
      await ensureConversation(text);
      await apiPost('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages', { content: text });
      /* 与行程详情 AI 面板同一模式：发送后拉取完整消息列表（草稿状态由后端装配） */
      var d = await apiGet('/ai/conversations/' + encodeURIComponent(S.convId) + '/messages?page=1&pageSize=30');
      var items = (d.messages || []).slice().reverse();
      removeTyping();
      renderMessages(items);
      /* AI 指引了页面跳转（open_page）：稍作停留让用户看到提示，再自动前往 */
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
    if (S.snapped) showFull(240);   /* API 直调时同样先恢复完整显示 */
    buildBubble();
    S.bubbleOpen = true;
    S.pet.classList.add('bubble-open');
    setState('idle', S.facing);
    /* 先渲染再定位（有尺寸才能算位置） */
    S.bubble.classList.add('open');
    placeBubble();
    if (!isLoggedIn()) {
      renderLogin();
      return;
    }
    S.el.inputRow.hidden = false;
    S.el.body.innerHTML = '<div class="pet-msg ai"><div class="txt"><span class="pet-typing"><i></i><i></i><i></i></span></div></div>';
    await loadHistory();
    setTimeout(function () { S.el.input.focus(); }, 120);
  }

  function hideBubble() {
    if (!S.bubble) return;
    S.bubble.classList.remove('open');
    S.bubbleOpen = false;
    S.pet.classList.remove('bubble-open');
    /* 气泡关闭后若仍处于吸附态，回到半隐藏 */
    if (S.snapped) snapTo(S.snapped, 260);
  }

  /* ---------- 初始化 ---------- */
  function ensureDom() {
    if (!S.host) {
      S.host = document.createElement('div');
      S.host.className = 'pet-host';
      document.body.appendChild(S.host);
    }
    if (!S.pet) {
      S.pet = document.createElement('div');
      S.pet.className = 'pet';
      S.pet.setAttribute('data-state', 'idle');
      S.pet.setAttribute('data-facing', 'right');
      S.pet.setAttribute('role', 'button');
      S.pet.setAttribute('tabindex', '0');
      S.pet.setAttribute('aria-label', 'AI 旅行宠物');
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
      /* 拖拽（Pointer Events：鼠标与触摸统一）；点按与拖拽由位移阈值区分 */
      S.pet.addEventListener('pointerdown', onPointerDown);
      S.pet.addEventListener('pointermove', onPointerMove);
      S.pet.addEventListener('pointerup', onPointerUp);
      S.pet.addEventListener('pointercancel', onPointerUp);
      /* 吸附态：悬停恢复完整显示，移开回到半隐藏 */
      S.pet.addEventListener('mouseenter', function () {
        if (S.snapped && !S.bubbleOpen) showFull(240);
      });
      S.pet.addEventListener('mouseleave', function () {
        if (S.snapped && !S.bubbleOpen) snapTo(S.snapped, 240);
      });
      S.pet.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); showBubble(); }
      });
      /* 窗口尺寸变化：吸附态重新贴边，自由态收回边界内 */
      window.addEventListener('resize', function () {
        if (!S.pet) return;
        if (S.snapped) snapTo(S.snapped, 0);
        else { clampPos(); moveTo(S.x, S.y, 0); }
      });
      window.addEventListener('scroll', function () {
        /* fixed 定位不随页面滚动，仅重算气泡挂点 */
        placeBubble();
      }, { passive: true });
    }
  }

  function init(options) {
    if (S.host) { S.opt = Object.assign({}, DEFAULTS, options || {}); return; }  /* 幂等：已初始化只刷新配置 */
    S.opt = Object.assign({}, DEFAULTS, options || {});
    ensureDom();
    /* 初始位置：右下角往内收 */
    var b = bounds();
    S.x = b.maxX - 8;
    S.y = b.maxY - 6;
    clampPos();
    moveTo(S.x, S.y, 0);
    setState('idle', 'right');
  }

  function stop() {
    if (S.host) S.host.remove();
    if (S.bubble) S.bubble.remove();
    S.host = null; S.pet = null; S.bubble = null; S.bubbleOpen = false; S.drag = null; S.snapped = null;
  }

  global.TravelOSPet = {
    init: init,
    stop: stop,
    showBubble: showBubble,
    hideBubble: hideBubble
  };

  /* ---- 自动初始化：引本文件即生效，无需每页手写 init ----
   * 需要禁用的页面（如全屏沉浸场景）在引脚本前设置：
   *   window.TravelOSPetAuto = false;
   */
  function autoInit() {
    if (global.TravelOSPetAuto === false) return;
    init();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', autoInit);
  } else {
    autoInit();
  }
})(window);
