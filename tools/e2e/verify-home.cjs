/* Travel OS · 首页（/home/）端到端验证
 *
 * 自包含夹具：脚本自行注册测试账号 → 创建行程/行程天/地点（走正常权限 + edit-lock 流程）
 * → 花费/照片/游记，验证首页空态与数据态，结束按【本次记录的准确 ID】硬删除全部夹具，
 * 成功或失败都会执行清理，清理失败明确报错（不用用户名前缀、不动其他批次数据）。
 *
 * 运行（详见 tools/e2e/README.md）：
 *   NODE_PATH=<managed node_modules> node tools/e2e/verify-home.cjs
 * 环境变量：
 *   E2E_ORIGIN  测试地址，默认 http://127.0.0.1:3001（仅允许回环地址，防误伤生产）
 *   E2E_SHOTS   截图目录，默认 D:/tmp/home-shots
 *
 * 失败分类（证据驱动，超时不武断归因；唯一实现见 lib/error-classify.cjs，selftest 直接回归该模块）：
 *   biz   业务失败：页面脚本运行时异常（TypeError/ReferenceError 等，消息含 网络/域名/加载失败/
 *         exceeded/amap 等普通字样不改分类）、页面 /api/ 返回 4xx/5xx、DOM 状态不符、业务断言失败。
 *   env   环境失败：仅凭明确证据——net::ERR_* 网络错误码、SDK 加载失败（AMap is not defined 与
 *         amap.js 加载路径的确定字符串）、Key 无效、高德限流(CUQPS/10021)、高德域名请求失败。
 *   undet 原因未定的超时：既无环境证据也无业务证据的超时，单独计数，保持非零退出，不武断归为环境。
 *   任一非 0 都不通过（exit 1）；业务与环境证据分别保留互不覆盖，汇总层业务失败优先定 FAILED 标签。
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { execSync } = require('child_process');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/home-shots';
const PG = 'docker exec travel-os-postgres psql -U travel_os -d travel_os -t -A -c';

/* ---- 会创建数据的测试只允许回环地址（防默认打到生产） ---- */
try {
  const host = new URL(ORIGIN).hostname;
  if (!['127.0.0.1', 'localhost', '::1', '[::1]'].includes(host)) {
    console.error('[REFUSE] 本测试会创建/删除数据，仅允许本地回环目标：' + ORIGIN);
    console.error('         如需打本地其他端口：E2E_ORIGIN=http://127.0.0.1:<port> 重新指定');
    process.exit(1);
  }
} catch (e) {
  console.error('[REFUSE] E2E_ORIGIN 不是合法 URL: ' + ORIGIN);
  process.exit(1);
}

/* ---- 结果计数与失败出口 ---- */
let biz = 0;      /* 业务断言失败：页面脚本异常 / API 4xx5xx / DOM 与断言不符 */
let env = 0;      /* 环境失败：有明确证据（SDK/网络/Key）才计入 */
let undet = 0;    /* 原因未定的超时：无环境证据也无业务证据，不武断归因 */
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function envCheck(cond, msg) { if (cond) ok(msg); else { env++; console.error('  [ENV] ' + msg); } }
function fatal(msg) { throw new Error('[FAIL] ' + msg); }
function psql(sql) { return execSync(`${PG} "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' }).trim(); }
function ymd(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
/* 有界等待：条件达成即返回，超时抛错（替代固定 sleep） */
async function waitFor(desc, fn, timeout = 15000, interval = 200) {
  const t0 = Date.now();
  for (;;) {
    let v = null;
    try { v = await fn(); } catch (e) { v = null; }
    if (v) return v;
    if (Date.now() - t0 > timeout) throw new Error('等待超时(' + timeout + 'ms): ' + desc);
    await new Promise((r) => setTimeout(r, interval));
  }
}

/* ---------- 生成一张纯色 PNG（不依赖外部素材） ---------- */
function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
function solidPng(size, rgb) {
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y++) {
    const off = y * (size * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < size; x++) {
      raw[off + 1 + x * 3] = rgb[0];
      raw[off + 2 + x * 3] = rgb[1];
      raw[off + 3 + x * 3] = rgb[2];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const t = Buffer.from(type, 'ascii');
    const c = Buffer.alloc(4); c.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
    return Buffer.concat([len, t, data, c]);
  };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const dataUrl = (buf) => 'data:image/png;base64,' + buf.toString('base64');

/* ---- 本次夹具 ID（注册/创建成功即记录，供清理精确定位） ---- */
const created = { userId: null, tripId: null, dayIds: [] };
let browser = null;

/* ================= 夹具准备（API，正常权限 + edit-lock 流程） ================= */
async function seed(H, jpost, jget) {
  console.log('--- seed ---');
  const tripRes = (await jpost('/api/trips', {
    title: '首页验证行程（临时）', destination: '阿斯哈图',
    startDate: ymd(12), endDate: ymd(16),
    description: '首页 e2e 临时数据'
  })).trip;
  if (!tripRes || !tripRes.id) fatal('行程创建失败');
  created.tripId = tripRes.id;
  console.log('  tripId =', created.tripId);

  await jpost(`/api/trips/${created.tripId}/days/generate`, {});
  const days = (await jget(`/api/trips/${created.tripId}/days`)).days || [];
  if (days.length < 3) fatal('行程天生成失败，得到 ' + days.length);
  created.dayIds = days.map((d) => d.id);

  /* 地点写入需要持有当天编辑锁（TripPlaceController 天级互斥）：取锁 → 加点 → 释放 */
  const PLACES = [
    { day: 0, name: '阿斯哈图石林', type: 'attraction', lng: 117.53, lat: 43.63, address: '克什克腾旗' },
    { day: 1, name: '达里诺尔湖', type: 'attraction', lng: 116.62, lat: 43.29, address: '克什克腾旗西部' },
    { day: 2, name: '乌兰布统草原', type: 'attraction', lng: 117.42, lat: 42.45, address: '克什克腾旗南部' }
  ];
  for (const p of PLACES) {
    const dayId = days[p.day].id;
    await jpost(`/api/trips/${created.tripId}/days/${dayId}/edit-lock`, {});
    try {
      await jpost(`/api/trips/${created.tripId}/places`, {
        tripDayId: dayId, name: p.name, type: p.type,
        address: p.address, longitude: p.lng, latitude: p.lat
      });
    } finally {
      await jpost2(`/api/trips/${created.tripId}/days/${dayId}/edit-lock`, 'DELETE');
    }
  }
  console.log('  places =', PLACES.length, '（逐天 edit-lock 取/还）');

  await jpost(`/api/trips/${created.tripId}/expenses`, {
    amount: '480', category: 'ticket', title: '首页验证·门票支出',
    payerUserId: created.userId, shareUserIds: [created.userId]
  });

  const photos = [];
  for (let i = 0; i < 3; i++) {
    const res = await jpost(`/api/trips/${created.tripId}/photos`, {
      url: dataUrl(solidPng(240, [13 + i * 60, 110, 110 + i * 20])),
      caption: '首页验证照片 ' + (i + 1),
      shotAt: ymd(12 + i) + ' 10:00:00'
    });
    photos.push((res.photo || res).id);
  }
  console.log('  photos =', photos.length);

  await jpost(`/api/trips/${created.tripId}/journals`, {
    title: '首页验证·林间的第一天',
    content: '早上六点从帐篷里钻出来，石林的轮廓还浸在雾里。风从北面吹过来，带着草和松脂的气味。',
    mood: 'happy', isDraft: false, photoIds: photos.slice(0, 1)
  });
  console.log('  journal seeded');
}

/* DELETE 小工具（锁释放；不参与断言） */
let jpost2 = async () => ({});
async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  /* ---- 注册独立测试账号（不依赖个人账号/固定缓存；密码仅驻内存，不打印） ---- */
  const username = 'e2eh' + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
  const password = 'Hm' + Date.now().toString(36) + 'a7';
  const regRes = await fetch(ORIGIN + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const regBody = await regRes.json().catch(() => null);
  if (regRes.status !== 200 || !regBody || !regBody.token || !regBody.user || !regBody.user.id) {
    fatal('注册失败: ' + regRes.status + ' ' + JSON.stringify(regBody || {}).slice(0, 160));
  }
  created.userId = regBody.user.id; /* 成功即记录 → 无论成败都能按准确 ID 清理 */
  const token = regBody.token;
  const user = regBody.user;
  console.log('--- 注册测试账号 ok（id=' + created.userId + '）---');

  const H = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const jpost = async (p, body) => {
    const r = await fetch(ORIGIN + p, { method: 'POST', headers: H, body: JSON.stringify(body) });
    const t = await r.text();
    if (!r.ok) fatal('POST ' + p + ' -> ' + r.status + ' ' + t.slice(0, 160));
    return t ? JSON.parse(t) : {};
  };
  jpost2 = async (p, method) => {
    const r = await fetch(ORIGIN + p, { method, headers: H });
    if (!r.ok) console.error('  (warn) ' + method + ' ' + p + ' -> ' + r.status); /* 锁释放失败只告警，清理阶段兜底删锁 */
    return {};
  };
  const jget = async (p) => (await (await fetch(ORIGIN + p, { headers: H })).json());

  /* ================= 浏览器 ================= */
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  /* /files/** 缩略图走同源 Cookie 通道（WebConfig：img 标签无法带 Bearer 头）——
     与真实登录流程一致，注入 access_token cookie 使照片资源可加载 */
  await ctx.addCookies([{ name: 'access_token', value: token, url: ORIGIN }]);

  const page = await ctx.newPage();
  /* ---- 证据收集与分类（业务/环境分别保留；超时不武断归因） ----
     分类唯一实现在 lib/error-classify.cjs（selftest 直接调用同模块做回归）：
     页面运行时异常（TypeError/ReferenceError 等）一律业务，消息含 网络/域名/加载失败/exceeded/amap
     等普通字样不改变分类；环境只认 net::ERR_* 网络错误码、SDK 加载失败（AMap is not defined、
     amap.js 加载路径的确定字符串）、Key 错误、高德限流错误码。 */
  const { createErrorCollector } = require('./lib/error-classify.cjs');
  const collector = createErrorCollector();
  const pageJsErrors = collector.pageErrors;   /* 业务：页面脚本运行时异常等 */
  const mapEnvErrors = collector.envErrors;    /* 环境：网络错误码 / SDK 加载失败 / Key / 限流 */
  const apiBizErrors = [];   /* 业务：页面 /api/ 请求返回 4xx/5xx */
  const sanitizeUrl = (u) => String(u).split('?')[0].slice(0, 120);   /* 只留协议+路径，去掉 query */
  const classify = collector.classify;
  /* pageerror 传 stack：首行含「TypeError: xxx」等异常名，供「异常名优先」分类 */
  page.on('pageerror', (e) => classify('pageerror', (e && e.stack) || (e && e.message) || String(e)));
  page.on('console', (m) => { if (m.type() === 'error') classify('console', m.text()); });
  /* 请求来源证据：高德/autonavi 请求失败归环境（瓦片与页面切换的 ERR_ABORTED 不算） */
  page.on('requestfailed', (r) => {
    const u = r.url();
    const why = (r.failure() && r.failure().errorText) || '';
    if (/amap|autonavi/.test(u) && !/\/tile\//.test(u) && why !== 'net::ERR_ABORTED') {
      mapEnvErrors.push('requestfailed: ' + sanitizeUrl(u) + ' ' + why);
      console.log('[reqfail amap]', sanitizeUrl(u), why);
    }
  });
  page.on('response', (r) => {
    try {
      const u = r.url();
      if (/\/api\//.test(u) && r.status() >= 400) apiBizErrors.push(r.status() + ' ' + sanitizeUrl(u));
    } catch (e) { /* 忽略取 URL 失败 */ }
  });

  /* ================= 阶段 A：空态（新账号尚无数据） ================= */
  console.log('--- 阶段A：空态 ---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await waitFor('首页数据加载（骨架消失）',
    () => page.evaluate(() => !document.querySelector('#expBody .skeleton')));
  await waitFor('行程区渲染（行或空态）',
    () => page.evaluate(() => !!document.querySelector('#tripBody .trip-row') || !!document.querySelector('#tripBody .empty')));

  const greetA = (await page.locator('#greet').textContent()).trim();
  /* greeting() 按时段返回：夜深了/早上好/中午好/下午好/晚上好（<6 点无「好」字）——
     断言时段词 + 逗号 + 真实用户名，避免把时段文案差异当失败 */
  check(/^(夜深了|早上好|中午好|下午好|晚上好)，/.test(greetA) && greetA.endsWith(username),
    '问候语渲染：' + greetA);
  const subA = (await page.locator('#heroSub').textContent()).trim();
  check(/月\d+日\s*周/.test(subA), '日期副标：' + subA);

  const cdA = (await page.locator('.cd-num').textContent()).replace(/\s+/g, ' ').trim();
  check(cdA.includes('还没有即将开始的旅行'), '空态倒计时文案：' + cdA);
  check(await page.locator('#nextTrip').isHidden(), '空态不显示「下一趟旅程」登机牌（不造虚假倒计时）');
  check(await page.locator('.cd-cta a[href="/trip-form/"]').isVisible(), '空态提供「创建新旅行」入口');

  check((await page.locator('#tripBody .empty').textContent()).includes('还没有旅行'), '行程区空态');
  check((await page.locator('#expBody .empty').textContent()).includes('还没有记账'), '花费区空态');
  check((await page.locator('#photoBody .empty').textContent()).includes('还没有照片'), '照片区空态');
  check((await page.locator('#journalBody .empty').textContent()).includes('还没有游记'), '游记区空态');
  check(await page.locator('#sampleSection').isVisible(), '空态展示示例行程区（新用户引导）');
  check((await page.locator('#stTrips').textContent()).trim() === '0', '空态统计·旅行次数=0');
  check((await page.locator('#stSpent').textContent()).trim() === '0', '空态统计·累计花费=0');
  check((await page.locator('#stDays').textContent()).trim() === '0', '空态统计·累计天数=0');
  check((await page.locator('#stPacking').textContent()).trim() === '0', '空态统计·待打包=0');
  await page.screenshot({ path: path.join(SHOTS, 'empty.png'), fullPage: true });
  console.log('  shot -> empty.png');

  /* ================= 夹具播种（空态断言完成后） ================= */
  await seed(H, jpost, jget);
  /* 期望值取自 API（不硬编码） */
  const dash = await jget('/api/dashboard');
  const expected = {
    trips: String(dash.stats.tripCount),
    days: String(dash.stats.totalDays),
    spent: Number(dash.stats.totalExpenseAmount).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 0 }),
    packing: String(dash.stats.pendingPackingItemCount)
  };
  console.log('  expected =', JSON.stringify(expected));

  /* ================= 阶段 B：数据态 ================= */
  console.log('--- 阶段B：数据态（重载）---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await waitFor('行程速览出现', () => page.locator('.trip-row').count().then((n) => n >= 1).catch(() => false), 20000);
  await waitFor('花费骨架消失', () => page.evaluate(() => !document.querySelector('#expBody .skeleton')));

  /* ---- Hero ---- */
  const greet = (await page.locator('#greet').textContent()).trim();
  check(/^(夜深了|早上好|中午好|下午好|晚上好)，/.test(greet) && greet.endsWith(username),
    '问候语渲染：' + greet);
  const sub = (await page.locator('#heroSub').textContent()).trim();
  check(/月\d+日\s*周/.test(sub), '日期副标：' + sub);
  const cdNum = (await page.locator('.cd-num').textContent()).replace(/\s+/g, ' ').trim();
  check(/距离出发还有\s*12\s*天/.test(cdNum), '倒计时（+12 天出发）：' + cdNum);
  const cdDest = (await page.locator('.cd-dest').textContent()).trim();
  check(cdDest.includes('阿斯哈图') && cdDest.includes('出发'), '倒计时目的地与出发日：' + cdDest);
  /* 旧版 Hero 微统计 #hrTrips/#hrDays/#hrFarthest（段数/在路上/最远）已随顶栏化改版移除，
     无同位替代；等价数据由统计四联（#stTrips/#stDays）与登机牌左栏覆盖，见下两条。 */
  const ntTitle = (await page.locator('#ntTitle').textContent()).trim();
  check(ntTitle.includes('首页验证行程（临时）'), '登机牌左栏=下一趟旅程名：' + ntTitle);
  const ntMeta = (await page.locator('#ntMeta').textContent()).trim();
  check(ntMeta.includes('阿斯哈图') && ntMeta.includes('出发'), '登机牌左栏元信息：' + ntMeta);

  /* ---- 统计四联（与 /api/dashboard 一致） ---- */
  check((await page.locator('#stTrips').textContent()).trim() === expected.trips, '统计·旅行次数 = ' + expected.trips);
  check((await page.locator('#stSpent').textContent()).trim() === expected.spent, '统计·累计花费 = ' + expected.spent);
  check((await page.locator('#stDays').textContent()).trim() === expected.days, '统计·累计天数 = ' + expected.days);
  check((await page.locator('#stPacking').textContent()).trim() === expected.packing, '统计·待打包 = ' + expected.packing);
  check(await page.locator('.stat .foot').first().isVisible(), '统计卡脚注可见');

  /* ---- 行程地图面板 ---- */
  check((await page.locator('#mapTrip').textContent()).trim() === '首页验证行程（临时）', '地图面板副标 = 行程名');
  check((await page.locator('#mapLink').getAttribute('href')).includes('/map/?trip=' + created.tripId), '「完整地图」带行程参数');

  let mapReady = true;
  try {
    await waitFor('首页地图 AMap 就绪', () => page.evaluate(() => {
      const host = document.getElementById('mapAmap');
      return host && !host.hidden && !!host.querySelector('.amap-maps, canvas, .amap-container');
    }), 30000);
  } catch (e) {
    mapReady = false;
    /* 证据在先、分类在后：有明确环境证据（SDK/网络/Key）才归 env；
       有业务证据（页面脚本异常 / API 4xx5xx）归 biz；两者皆无 → 原因未定的超时 */
    const bizEvidence = pageJsErrors.length > 0 || apiBizErrors.length > 0;
    if (bizEvidence) {
      biz++;
      console.error('  !!  [biz] 首页地图面板 AMap 未就绪，且存在业务失败证据（页面脚本异常/API 4xx5xx）: ' + e.message);
      if (pageJsErrors.length) console.error('       业务证据: ' + pageJsErrors.slice(0, 3).join(' | '));
      if (apiBizErrors.length) console.error('       API 证据: ' + apiBizErrors.slice(0, 3).join(' | '));
      if (mapEnvErrors.length) { env++; console.error('  [ENV] （同时存在环境证据，分别保留: ' + mapEnvErrors.slice(0, 2).join(' | ') + '）'); }
    } else if (mapEnvErrors.length > 0) {
      env++; console.error('  [ENV] 首页地图面板 AMap 未就绪，有明确环境证据（SDK/网络/Key）: '
        + mapEnvErrors.slice(0, 3).join(' | '));
    } else {
      undet++; console.error('  [?] 原因未定的超时：首页地图面板未就绪，无环境失败证据也无业务异常证据（不武断归因）: ' + e.message);
    }
  }
  if (mapReady) {
    check(await page.locator('#mapLoading').isHidden(), '加载态已隐藏');
    await waitFor('3 个标点渲染', () => page.locator('#mapAmap .pin').count().then((n) => n === 3).catch(() => false));
    const pinTags = await page.locator('#mapAmap .pin .tag').count();
    check(pinTags === 3, '标点名称标签数 = ' + pinTags);
    const firstTag = (await page.locator('#mapAmap .pin .tag').first().textContent()).trim();
    check(firstTag === '阿斯哈图石林', '标点名称渲染真实地点：' + firstTag);
    const isDense = await page.locator('#mapCanvas').evaluate((el) => el.classList.contains('dense'));
    check(!isDense, '3 个标点不启用紧凑模式（名称常显）');
    check((await page.locator('#mapBadgeT').textContent()).trim() === 'DAY 1 — 3', '地图徽标：DAY 1 — 3');
    const badgeD = (await page.locator('#mapBadgeD').textContent()).trim();
    check(badgeD.includes('阿斯哈图石林') && badgeD.includes('乌兰布统草原'), '徽标路线摘要：' + badgeD);
    check(await page.locator('#mapBadge').isVisible(), '地图徽标可见');
    check((await page.locator('#mapHint').isHidden()) === false, '连线示意提示可见');

    /* 卫星底图切换（属性态 + 图层挂载） */
    await page.locator('.map-ctl span[data-layer="satellite"]').click();
    await waitFor('底图切到卫星', () => page.locator('#mapCanvas').getAttribute('data-layer').then((v) => v === 'satellite').catch(() => false));
    check((await page.locator('.map-ctl span[data-layer="satellite"]').getAttribute('data-on')) === 'true', '卫星按钮选中态');
    const satVisible = await page.evaluate(() => !!document.querySelector('#mapAmap .amap-layers .amap-layer'));
    check(satVisible, 'AMap 图层容器存在（卫星/路网 TileLayer 已挂载）');
    await page.screenshot({
      path: path.join(SHOTS, 'map-satellite.png'),
      clip: { x: 0, y: await page.locator('.map-canvas').evaluate((el) => el.getBoundingClientRect().top + window.scrollY) - 8, width: 1280, height: 430 }
    }).catch(() => {});
    await page.locator('.map-ctl span[data-layer="standard"]').click();
    await waitFor('底图切回标准', () => page.locator('#mapCanvas').getAttribute('data-layer').then((v) => v === 'standard').catch(() => false));
    check(true, '底图切回标准');
  } else {
    console.log('  (skip) 地图面板相关断言因环境失败跳过，整体不计通过');
  }

  /* ---- 行程速览 ---- */
  const tripRows = await page.locator('#tripBody .trip-row').count();
  check(tripRows >= 1, '行程速览行数 = ' + tripRows);
  const firstNm = (await page.locator('#tripBody .trip-row').first().locator('.nm').textContent()).trim();
  check(firstNm.includes('首页验证行程（临时）'), '首行是最新行程：' + firstNm);
  const firstDt = (await page.locator('#tripBody .trip-row').first().locator('.dt').textContent()).trim();
  check(/\d{2}\/\d{2} – \d{2}\/\d{2} · 5 天/.test(firstDt), '行程日期行：' + firstDt);
  const stPill = (await page.locator('#tripBody .trip-row').first().locator('.st').textContent()).trim();
  check(stPill === '草稿', '状态胶囊（新建即草稿）：' + stPill);
  /* 缩略图现为首字母牌（原 3D svg 图标已随视觉改版移除）：目的地首字渲染 */
  const thumbIni = (await page.locator('#tripBody .trip-row').first().locator('.thumb .ini').textContent()).trim();
  check(thumbIni === '阿', '行程缩略首字母牌 = 目的地首字（实为「' + thumbIni + '」）');
  check((await page.locator('#tripBody .trip-row').first().getAttribute('role')) === 'link', '行程行可访问（role=link）');

  /* ---- 行程入口真实跳转（点击 + 键盘激活）----
     交互实现（home/index.html renderTrips）：.trip-row[data-id] role=link tabindex=0，
     click 与 keydown(Enter/Space) 都跳 /trip-detail/?id=<data-id>。
     role=link + tabindex=0 代表可键盘访问 → 用 Enter 真实验证键盘激活行为。 */
  console.log('--- 行程入口真实跳转（点击 + 键盘） ---');
  const TRIP_TITLE = '首页验证行程（临时）';
  const rowSel = '#tripBody .trip-row[data-id="' + created.tripId + '"]';
  check((await page.locator(rowSel).count()) === 1, '夹具行程行按 data-id 精确存在（' + rowSel + '）');
  check((await page.locator(rowSel).getAttribute('tabindex')) === '0', '行程行 tabindex=0（键盘可达）');
  const rowLabel = ((await page.locator(rowSel).getAttribute('aria-label')) || '').trim();
  check(rowLabel.includes(TRIP_TITLE), '行程行 aria-label 指向夹具行程：' + rowLabel);

  /* 跳转后的共同断言：路径 /trip-detail/ + 实际使用的行程参数=夹具 ID + 详情页真实渲染夹具标题
     （排除只改了 URL 或跳到别的行程的情况） */
  const assertOnDetail = async (how) => {
    await page.waitForURL((u) => /\/trip-detail\/$/.test(u.pathname)
      && u.searchParams.get('id') === created.tripId, { timeout: 15000 }).catch(() => null);
    const u = new URL(page.url());
    check(/\/trip-detail\/$/.test(u.pathname), how + '：落在 /trip-detail/（实际 ' + u.pathname + '）');
    check(u.searchParams.get('id') === created.tripId, how + '：id 参数 = 夹具行程 ID（实际 ' + u.searchParams.get('id') + '）');
    let crumb = '';
    try {
      crumb = (await waitFor(how + '：详情页渲染夹具标题', () =>
        page.locator('#crumbTitle').textContent().then((t) => t.trim() === TRIP_TITLE ? t.trim() : null).catch(() => null), 15000));
      check(true, how + '：详情页面包屑标题 = 夹具标题（' + crumb + '）');
    } catch (e) {
      crumb = ((await page.locator('#crumbTitle').textContent().catch(() => '')) || '').trim();
      check(false, how + '：详情页标题 = 夹具标题（实际「' + crumb.slice(0, 40) + '」，URL=' + page.url() + '）');
    }
    const passTitle = ((await page.locator('#pass .pass-title').textContent().catch(() => '')) || '').trim();
    check(passTitle.includes(TRIP_TITLE), how + '：票面主标题含夹具标题（' + (passTitle || '(空)').slice(0, 42) + '）');
  };

  await page.locator(rowSel).click();
  await assertOnDetail('点击行程行');
  await page.screenshot({ path: path.join(SHOTS, 'nav-click-detail.png') });
  console.log('  shot -> nav-click-detail.png');

  /* 键盘激活：回首页（等待数据就绪）→ 聚焦同一行 → Enter */
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await waitFor('回首页·行程速览出现', () => page.locator('.trip-row').count().then((n) => n >= 1).catch(() => false), 20000);
  await waitFor('回首页·花费骨架消失', () => page.evaluate(() => !document.querySelector('#expBody .skeleton')));
  await page.locator(rowSel).focus();
  await page.keyboard.press('Enter');
  await assertOnDetail('键盘 Enter 激活');
  ok('键盘 Enter 激活行程行 → 同样落到夹具行程详情');

  /* 返回首页并等待数据重新就绪，后续首页断言不受跳转影响 */
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await waitFor('行程速览出现（跳转后回到首页）', () => page.locator('.trip-row').count().then((n) => n >= 1).catch(() => false), 20000);
  await waitFor('花费骨架消失（跳转后回到首页）', () => page.evaluate(() => !document.querySelector('#expBody .skeleton')));

  /* ---- 最近花费 ---- */
  const expRows = await page.locator('#expBody .exp-row').count();
  check(expRows >= 1, '花费行数 = ' + expRows);
  check((await page.locator('#expBody .exp-row').first().locator('.ttl').textContent()).trim() === '首页验证·门票支出', '花费标题正确');
  const catTxt = (await page.locator('#expBody .exp-row').first().locator('.cat').textContent()).trim();
  check(catTxt.startsWith('门票 · '), '花费分类+日期：' + catTxt);
  const amtTxt = (await page.locator('#expBody .exp-row').first().locator('.amt').textContent()).trim();
  check(/480/.test(amtTxt), '花费金额：' + amtTxt);
  check((await page.locator('#expLink').getAttribute('href')).includes('/expenses/?trip=' + created.tripId), '「全部账目」带行程参数');

  /* ---- 最近照片 ---- */
  await waitFor('照片格 ≥3', () => page.locator('#photoBody .photo').count().then((n) => n >= 3).catch(() => false));
  const photoCount = await page.locator('#photoBody .photo').count();
  check(photoCount >= 3, '照片格数 = ' + photoCount);
  const bgs = await page.locator('#photoBody .photo').evaluateAll((els) => els.map((el) => getComputedStyle(el).backgroundImage));
  check(bgs.length > 0 && bgs.every((b) => /url\(/.test(b) && (/\/files\//.test(b) || /data:image\//.test(b))),
    '全部照片以真实缩略图资源为背景（' + bgs.length + '/' + bgs.length + '，现为 /files/<hash>.png 落盘缩略图）');
  /* 资源实际可加载（同源 Cookie 通道 + 对象级授权，而非仅样式存在） */
  const imgsLoad = await page.evaluate(async () => {
    const urls = Array.from(document.querySelectorAll('#photoBody .photo'))
      .map((el) => { const m = getComputedStyle(el).backgroundImage.match(/url\(["']?(.*?)["']?\)/); return m && m[1]; })
      .filter(Boolean);
    if (!urls.length) return false;
    const results = await Promise.all(urls.map((u) => new Promise((res) => {
      const img = new Image();
      img.onload = () => res(img.naturalWidth > 0);
      img.onerror = () => res(false);
      img.src = u;
    })));
    return results.every(Boolean);
  });
  check(imgsLoad, '照片缩略图全部实际加载成功（/files/ Cookie 鉴权通道）');
  const seededShown = await page.locator('#photoBody .photo[title^="首页验证照片"]').count();
  check(seededShown >= 3, '本次 seed 的照片已展示 ' + seededShown + ' 张');
  const own = (await page.locator('#photoBody .photo[title^="首页验证照片"]').first().locator('.own').textContent()).trim();
  check(own === '首页验证行程（临时）', '照片左上角行程归属：' + own);
  check((await page.locator('#photoLink').getAttribute('href')).includes('/photos/?trip=' + created.tripId), '「全部照片」带行程参数');

  /* ---- 最近游记 ---- */
  const jCards = await page.locator('#journalBody .journal-card').count();
  check(jCards === 1, '游记卡数 = ' + jCards);
  check((await page.locator('#journalBody .nm').first().textContent()).trim() === '首页验证·林间的第一天', '游记标题正确');
  const jMeta = (await page.locator('#journalBody .dt').first().textContent()).trim();
  check(jMeta.includes('·') && jMeta.includes('首页验证行程（临时）'), '游记元信息含行程名：' + jMeta);
  const jEx = (await page.locator('#journalBody .excerpt').first().textContent()).trim();
  check(jEx.length > 10, '游记摘要非空');
  check((await page.locator('#journalLink').getAttribute('href')).includes('/journals/?trip=' + created.tripId), '「全部游记」带行程参数');

  /* ---- 顶栏导航（原侧栏 #railToggle 折叠交互已随横板顶栏改版整体移除，
         无同位替代功能；改为断言顶栏导航现状，见 README「失效原因」） ---- */
  check((await page.locator('.nav a[aria-current="page"] .txt').textContent()).trim() === '首页', '顶栏首页高亮');
  const unreadHidden = await page.locator('#navUnread').isHidden();
  check(unreadHidden === (Number(dash.stats.unreadNotificationCount) === 0), '未读角标与接口一致（' + dash.stats.unreadNotificationCount + '）');

  /* ---- 页面 JS 错误（业务/环境分类） ---- */
  check(pageJsErrors.length === 0, '无页面业务 JS 错误' + (pageJsErrors.length ? ' -> ' + pageJsErrors.slice(0, 3).join(' | ') : ''));
  check(apiBizErrors.length === 0, '页面 /api/ 请求无 4xx/5xx（业务证据）'
    + (apiBizErrors.length ? ' -> ' + apiBizErrors.slice(0, 3).join(' | ') : ''));
  envCheck(mapEnvErrors.length === 0, '无地图 SDK/网络/Key 类错误' + (mapEnvErrors.length ? ' -> ' + mapEnvErrors.slice(0, 3).join(' | ') : ''));

  await page.screenshot({ path: path.join(SHOTS, 'desktop.png'), fullPage: true });
  console.log('  shot -> desktop.png');

  /* ---- 响应式：断点回归（1120/980/720，按当前样式表实测） ---- */
  await page.setViewportSize({ width: 1000, height: 900 });
  await waitFor('1000px 统计两列', () => page.locator('.stats').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length === 2).catch(() => false));
  check(true, '<=1120px 统计变 2 列（1000px 实测）');
  const duoAt1000 = await page.locator('.duo').first().evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(duoAt1000 === 2, '>980px 双栏保持两列（1000px 实测 ' + duoAt1000 + '）');
  await page.screenshot({ path: path.join(SHOTS, 'tablet.png'), fullPage: true });

  await page.setViewportSize({ width: 960, height: 900 });
  await waitFor('960px 双栏堆叠', () => page.locator('.duo').first().evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length === 1).catch(() => false));
  check(true, '<=980px 双栏堆叠（960px 实测）');

  /* ---- 移动端 390（原 #shell 单列 / .rail-toggle 隐藏 断言随侧栏移除失效，
         改为断言顶栏导航在移动端的现状：可见、独占一行、统计两列、照片网格现状） ---- */
  await page.setViewportSize({ width: 390, height: 844 });
  await waitFor('移动端布局生效', () => page.locator('.tb-nav').isVisible().catch(() => false));
  check(await page.locator('#sideNav').isVisible(), '<=720px 顶栏导航可见可用（替代已移除侧栏）');
  const navBox = await page.locator('.tb-nav').boundingBox();
  check(!!navBox && navBox.width >= 390 * 0.85, '顶栏导航独占一行（宽 ' + (navBox ? Math.round(navBox.width) : '?') + ' ≥85% 视口）');
  const statsColsM = await page.locator('.stats').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(statsColsM === 2, '<=720px 统计两列（实测 ' + statsColsM + '）');
  const photoCols = await page.locator('.photo-grid').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(photoCols === 3, '<=720px 照片网格保持 3 列（当前设计，实测 ' + photoCols + '）');
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('  shot -> mobile.png');
}

/* ================= 清理（按本次准确 ID，成败都执行） ================= */
/* 本地库表存在性检查（不同环境迁移进度可能不同；表不存在则该表不可能有本次数据，
   记录跳过而非报错——存在但删除失败仍然明确报错） */
const _tableCache = new Map();
function tableExists(name) {
  if (_tableCache.has(name)) return _tableCache.get(name);
  const r = psql(`SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${name}'`);
  const ex = r === '1';
  _tableCache.set(name, ex);
  return ex;
}

function cleanup() {
  const errors = [];
  const skipped = [];
  const runT = (tbl, sql) => {
    if (!tableExists(tbl)) { skipped.push(tbl); return; }
    try { psql(sql); } catch (e) { errors.push(tbl + ': ' + (e && e.message ? e.message.split('\n')[0] : e)); }
  };
  const q = (id) => "'" + id + "'";
  /* 本次行程落盘缩略图（photos.url → /files/<hash>）：按名单精确删除，模式守卫防路径逃逸 */
  const diskNames = [];
  const fileDirs = [path.resolve(__dirname, '../../data/files'),
    path.resolve(__dirname, '../../apps/server/data/files')];
  if (created.tripId && /^[0-9]+$/.test(created.tripId)) {
    const t = q(created.tripId);
    if (tableExists('photos')) {
      try {
        psql(`SELECT url FROM photos WHERE trip_id=${t}`).split('\n').map((s) => s.trim()).filter(Boolean)
          .forEach((u) => { const m = u.match(/^\/files\/([a-f0-9]{32}\.[a-z0-9]+)$/i); if (m) diskNames.push(m[1]); });
      } catch (e) { errors.push('photos.url 读取失败: ' + (e && e.message ? e.message.split('\n')[0] : e)); }
    }
    runT('edit_locks', `DELETE FROM edit_locks WHERE scope_id IN (SELECT id FROM trip_days WHERE trip_id=${t});`);
    runT('trip_expense_shares', `DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=${t});`);
    runT('trip_journal_photos', `DELETE FROM trip_journal_photos WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=${t});`);
    runT('trip_journal_audience', `DELETE FROM trip_journal_audience WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=${t});`);
    runT('journal_edit_locks', `DELETE FROM journal_edit_locks WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=${t});`);
    runT('packing_items', `DELETE FROM packing_items WHERE packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id=${t});`);
    /* 外键安全顺序：引用 trip_day_id 的子表（费用/游记/地点等）先删，trip_days 最后 */
    for (const tbl of ['ai_conversations', 'bookings', 'packing_lists', 'photos', 'travel_documents', 'trip_budgets',
      'trip_expenses', 'trip_journals', 'trip_members', 'trip_places', 'trip_routes',
      'trip_settlements', 'trip_tracks', 'trip_weather', 'trip_days']) {
      runT(tbl, `DELETE FROM ${tbl} WHERE trip_id=${t};`);
    }
    runT('trips', `DELETE FROM trips WHERE id=${t};`);
    for (const name of diskNames) {
      for (const dir of fileDirs) {
        const fp = path.join(dir, name);
        try {
          if (fs.existsSync(fp)) { fs.unlinkSync(fp); break; }
        } catch (e) { errors.push('磁盘缩略图删除失败 ' + name + ': ' + e.message); }
      }
    }
  } else if (created.tripId) {
    errors.push('tripId 非法（拒绝拼 SQL）: ' + created.tripId);
  }
  if (created.userId && /^[0-9]+$/.test(created.userId)) {
    const u = q(created.userId);
    runT('user_friends', `DELETE FROM user_friends WHERE user_id=${u} OR friend_id=${u};`);
    runT('audit_logs', `DELETE FROM audit_logs WHERE user_id=${u};`);
    runT('notifications', `DELETE FROM notifications WHERE user_id=${u};`);
    runT('user_preferences', `DELETE FROM user_preferences WHERE user_id=${u};`);
    runT('ai_messages', `DELETE FROM ai_messages WHERE conversation_id IN (SELECT id FROM ai_conversations WHERE user_id=${u});`);
    runT('ai_conversations', `DELETE FROM ai_conversations WHERE user_id=${u};`);
    /* 照片上传会登记 file_metadata（FK → users），必须先于 users 删除 */
    runT('file_metadata', `DELETE FROM file_metadata WHERE owner_user_id=${u};`);
    runT('trips', `DELETE FROM trips WHERE owner_id=${u};`);
    runT('trip_members', `DELETE FROM trip_members WHERE user_id=${u};`);
    runT('users', `DELETE FROM users WHERE id=${u};`);
  } else if (created.userId) {
    errors.push('userId 非法（拒绝拼 SQL）: ' + created.userId);
  }
  if (skipped.length) console.log('  (skip) 本地库不存在的表: ' + Array.from(new Set(skipped)).join(', '));

  /* 残留核验：本次 ID 范围必须归零 */
  try {
    const cnt = (sql) => Number(psql(sql));
    const left = {
      trips: created.tripId ? cnt(`SELECT count(*) FROM trips WHERE id=${q(created.tripId)} OR owner_id=${created.userId ? q(created.userId) : "''"}`) : 0,
      users: created.userId ? cnt(`SELECT count(*) FROM users WHERE id=${q(created.userId)}`) : 0,
      members: cnt(`SELECT count(*) FROM trip_members WHERE user_id=${created.userId ? q(created.userId) : "''"}`),
      expenses: created.tripId ? cnt(`SELECT count(*) FROM trip_expenses WHERE trip_id=${q(created.tripId)}`) : 0,
      locks: tableExists('edit_locks')
        ? cnt(`SELECT count(*) FROM edit_locks WHERE scope_id IN (SELECT id FROM trip_days WHERE trip_id=${created.tripId ? q(created.tripId) : "''"})`) : 0,
      filemeta: created.userId && tableExists('file_metadata')
        ? cnt(`SELECT count(*) FROM file_metadata WHERE owner_user_id=${q(created.userId)}`) : 0,
      disk: diskNames.filter((n) => fileDirs.some((d) => fs.existsSync(path.join(d, n)))).length,
      notifications: cnt(`SELECT count(*) FROM notifications WHERE user_id=${created.userId ? q(created.userId) : "''"}`)
    };
    const residue = Object.entries(left).filter(([, v]) => v > 0).map(([k, v]) => k + '=' + v);
    if (residue.length) errors.push('残留: ' + residue.join(', '));
  } catch (e) {
    errors.push('残留核验异常: ' + (e && e.message ? e.message.split('\n')[0] : e));
  }
  if (errors.length) throw new Error('清理失败（范围=本次 userId=' + created.userId + ' tripId=' + created.tripId + '）: ' + errors.join(' | '));
}

/* ================= 汇总出口 ================= */
(async () => {
  let fatalMsg = null;
  try {
    await main();
  } catch (e) {
    fatalMsg = (e && e.message) || String(e);
  }
  if (browser) { try { await browser.close(); } catch (e) { /* 关闭失败不覆盖主结果 */ } }

  let cleanupErr = null;
  console.log('--- cleanup（按本次准确 ID）---');
  try { cleanup(); console.log('  ok  夹具已清除并核验残留为 0'); }
  catch (e) { cleanupErr = (e && e.message) || String(e); console.error('  !!  ' + cleanupErr); }

  if (fatalMsg) console.error('\n' + fatalMsg);
  console.log('\n--- 结果 ---');
  console.log('业务断言失败: ' + biz + ' | 环境失败(SDK/网络/Key，不计通过): ' + env
      + ' | 原因未定的超时(不计通过): ' + undet + ' | 清理: ' + (cleanupErr ? '失败' : 'OK'));
  const pass = !fatalMsg && biz === 0 && env === 0 && undet === 0 && !cleanupErr;
  if (pass) console.log('=== ALL PASS ===');
  else if (biz > 0 || cleanupErr || (env === 0 && undet === 0)) console.log('=== FAILED ===');
  else if (env > 0) console.log('=== ENV FAILURE（环境失败，不算通过）===');
  else console.log('=== UNDETERMINED FAILURE（超时原因未确定，不计通过）===');
  process.exit(pass ? 0 : 1);
})();
