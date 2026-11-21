/* Travel OS · map 页（/map/）端到端验证
 *
 * 自包含夹具：脚本自行注册测试账号 → 创建 3 天行程/行程天/每天 2 个带坐标地点
 * （走正常权限 + 逐天 edit-lock 取还），断言结束后按【本次记录的准确 ID】硬删除，
 * 成败都执行清理，清理失败明确报错（不用用户名前缀、不动其他批次数据）。
 *
 * 覆盖：行程选择 / 地图与标点加载 / 按天聚焦 / 底图切换 / 回到全览 / 移动端抽屉 /
 *       无参数行程选择器；「地图可见」与「路线全部完成」分两个里程碑分别等待与断言。
 *
 * 运行（详见 tools/e2e/README.md）：
 *   NODE_PATH=<managed node_modules> node tools/e2e/verify-map.cjs
 * 环境变量：
 *   E2E_ORIGIN  测试地址，默认 http://127.0.0.1:3001（仅允许回环地址，防误伤生产）
 *   E2E_SHOTS   截图目录，默认 D:/tmp/map-shots
 *
 * 失败分类（证据驱动，超时不武断归因；唯一实现见 lib/error-classify.cjs，selftest 直接回归该模块）：
 *   biz   业务失败：页面脚本运行时异常（TypeError/ReferenceError 等，消息含 网络/域名/加载失败/
 *         exceeded/amap 等普通字样不改分类）、页面 /api/ 返回 4xx/5xx、DOM 状态不符、业务断言失败。
 *   env   环境失败：仅凭明确证据——net::ERR_* 网络错误码、SDK 加载失败（AMap is not defined 与
 *         amap.js 加载路径的确定字符串）、Key 无效/白名单、高德限流(CUQPS/10021)、页面自身降级提示
 *         （「未返回/虚线」）、高德域名请求失败（请求来源证据，URL 已脱敏）。
 *   undet 原因未定的超时：既无环境证据也无业务证据的超时，单独计数，保持非零退出，不武断归为环境。
 *   任一非 0 都不通过（exit 1）；业务与环境证据分别保留互不覆盖，汇总层业务失败优先定 FAILED 标签。
 *
 * 可控故障注入（仅测试上下文，不改生产逻辑）：E2E_FAULT=sdk-abort|route-fail|route-hang|biz-throw
 * 或逗号组合，供 tools/e2e/selftest-map-classify.cjs 验证分类正确性；不设置时完全关闭。
 *
 * 性能红线（交接文档第九节）：本脚本只观测不改造——路线串行、地图先就绪、完成后不 setFitView、
 * 路线缓存/QPS 退避/失败降级保持原样。
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/map-shots';
/* 可控故障注入（仅测试上下文；默认关）。用途见 selftest-map-classify.cjs：
 *   sdk-abort  : 高德/autonavi 全部请求 abort              → 期望 ENV（SDK 请求失败证据）
 *   route-fail : 驾车 JSONP 注入 10021 限流应答            → 期望 ENV（页面降级提示证据）
 *   route-hang : /route API 挂起、无任何错误事件           → 期望 UNDETERMINED（无证据超时）
 *   biz-throw  : 注入含「网络」+amap.js 路径的页面 TypeError → 期望 FAILED/biz（运行时异常不因关键词误分类）
 * 支持逗号组合（如 biz-throw,sdk-abort）→ 期望 FAILED 且业务/环境证据分别保留（互不覆盖）。 */
const KNOWN_FAULTS = ['sdk-abort', 'route-fail', 'route-hang', 'biz-throw'];
const FAULTS = String(process.env.E2E_FAULT || '').split(',').map((s) => s.trim()).filter(Boolean);
if (FAULTS.some((f) => !KNOWN_FAULTS.includes(f))) {
  console.error('[REFUSE] 未知 E2E_FAULT（可选 ' + KNOWN_FAULTS.join('/') + '，支持逗号组合）: ' + process.env.E2E_FAULT);
  process.exit(1);
}
/* 故障注入自检时缩短两个里程碑的等待预算（分类逻辑不受影响：证据仍先于超时出现） */
const T_MAP_READY = FAULTS.length ? 18000 : 45000;
const T_ROUTES = FAULTS.length ? 40000 : 90000;
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
let biz = 0;      /* 业务失败：页面脚本异常 / API 4xx5xx / DOM 与断言不符 */
let env = 0;      /* 环境失败：有明确证据（SDK/网络/Key/限流）才计入 */
let undet = 0;    /* 原因未定的超时：无环境证据也无业务证据，不武断归因 */
function ok(msg) { console.log('  OK  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function envFail(msg) { env++; console.error('  [ENV] ' + msg); }
function undetFail(msg) { undet++; console.error('  [?] ' + msg); }
function fatal(msg) { throw new Error('[FAIL] ' + msg); }
function psql(sql) { return execSync(`${PG} "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' }).trim(); }
function ymd(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
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
/* 标记框稳定采样（两次一致即视为动画结束） */
async function stableBox(locator, desc, timeout = 10000) {
  const t0 = Date.now();
  let prev = null;
  for (;;) {
    const b = await locator.boundingBox().catch(() => null);
    if (b && prev && Math.abs(b.x - prev.x) < 1 && Math.abs(b.y - prev.y) < 1
        && Math.abs(b.width - prev.width) < 1 && Math.abs(b.height - prev.height) < 1) return b;
    prev = b;
    if (Date.now() - t0 > timeout) throw new Error('等待超时: ' + desc);
    await new Promise((r) => setTimeout(r, 300));
  }
}
const boxDelta = (a, b) => Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y),
  Math.abs(a.width - b.width), Math.abs(a.height - b.height));

/* ---- 本次夹具 ID（注册/创建成功即记录） ---- */
const created = { userId: null, tripId: null, dayIds: [] };
let browser = null;

/* ================= 夹具准备 ================= */
async function seed(jpost, jget) {
  console.log('--- seed ---');
  const tripRes = (await jpost('/api/trips', {
    title: '地图验证行程（临时）', destination: '地图夹具',
    startDate: ymd(3), endDate: ymd(5),          /* 3 天跨度 → 3 个行程天 */
    description: 'map e2e 临时数据'
  })).trip;
  if (!tripRes || !tripRes.id) fatal('行程创建失败');
  created.tripId = tripRes.id;
  console.log('  tripId =', created.tripId);

  await jpost(`/api/trips/${created.tripId}/days/generate`, {});
  const days = (await jget(`/api/trips/${created.tripId}/days`)).days || [];
  if (days.length !== 3) fatal('行程天应为 3，得到 ' + days.length);
  created.dayIds = days.map((d) => d.id);

  /* 每天 2 点：天内聚集（<10km），跨天相距 >700km → 总里程必然包含跨天转场 */
  const PLACES = [
    { day: 0, name: '天安门', lng: 116.397, lat: 39.909 },
    { day: 0, name: '故宫', lng: 116.407, lat: 39.919 },
    { day: 1, name: '钟楼', lng: 108.948, lat: 34.263 },
    { day: 1, name: '大雁塔', lng: 108.954, lat: 34.341 },
    { day: 2, name: '天府广场', lng: 104.066, lat: 30.657 },
    { day: 2, name: '宽窄巷子', lng: 104.081, lat: 30.669 }
  ];
  for (const p of PLACES) {
    const dayId = days[p.day].id;
    await jpost(`/api/trips/${created.tripId}/days/${dayId}/edit-lock`, {});   /* 正常流程取锁 */
    try {
      await jpost(`/api/trips/${created.tripId}/places`, {
        tripDayId: dayId, name: p.name, type: 'attraction',
        longitude: p.lng, latitude: p.lat
      });
    } finally {
      await releaseLock(dayId);                                                /* 还锁 */
    }
  }
  console.log('  places =', PLACES.length, '（逐天 edit-lock 取/还）');
}
let releaseLock = async () => {};

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  /* ---- 注册独立测试账号（不依赖登录缓存/个人账号；密码仅驻内存，不打印） ---- */
  const username = 'e2em' + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
  const password = 'Mp' + Date.now().toString(36) + 'a7';
  const regRes = await fetch(ORIGIN + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const regBody = await regRes.json().catch(() => null);
  if (regRes.status !== 200 || !regBody || !regBody.token || !regBody.user || !regBody.user.id) {
    fatal('注册失败: ' + regRes.status + ' ' + JSON.stringify(regBody || {}).slice(0, 160));
  }
  created.userId = regBody.user.id;
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
  releaseLock = async (dayId) => {
    const r = await fetch(ORIGIN + `/api/trips/${created.tripId}/days/${dayId}/edit-lock`,
      { method: 'DELETE', headers: H });
    if (!r.ok) console.error('  (warn) 释放编辑锁失败 -> ' + r.status + '（清理阶段兜底删锁）');
  };
  const jget = async (p) => (await (await fetch(ORIGIN + p, { headers: H })).json());

  await seed(jpost, jget);

  const TITLE = '地图验证行程（临时）';
  const START = ymd(3);

  /* ================= 浏览器 ================= */
  browser = await chromium.launch({
    channel: 'chrome', headless: true,
    args: ['--no-sandbox', '--no-proxy-server', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({ viewport: { width: 1512, height: 900 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });

  const page = await ctx.newPage();
  /* ---- 可控故障注入（仅测试上下文，不改生产逻辑） ---- */
  if (FAULTS.includes('sdk-abort')) {
    await page.route((u) => /amap\.com|autonavi\.com/i.test(u.toString()), (route) => route.abort());
    console.log('  [fault] sdk-abort：已拦截全部高德/autonavi 请求');
  }
  if (FAULTS.includes('route-fail')) {
    await page.route((u) => /restapi\.amap\.com\/v3\/direction\/driving/i.test(u.toString()), (route) => {
      /* 按 JSONP 回调名包装限流应答，避免 JSON 解析错误混入业务证据 */
      const m = route.request().url().match(/[?&]callback=([^&]+)/);
      const cb = m ? m[1] : 'cb';
      route.fulfill({ status: 200, contentType: 'text/javascript',
        body: cb + '({"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"})' });
    });
    console.log('  [fault] route-fail：驾车规划注入 10021 限流应答');
  }
  if (FAULTS.includes('route-hang')) {
    await page.route((u) => /\/api\/trips\/[^/]+\/route(\?|$)/.test(u.toString()), () => { /* 永不应答 */ });
    console.log('  [fault] route-hang：/route API 挂起（不产生任何错误事件）');
  }
  if (FAULTS.includes('biz-throw')) {
    await page.addInitScript(() => {
      setTimeout(() => {
        /* 真实 TypeError，消息含「网络」与本地 amap.js 路径：验证页面运行时异常
           不因普通关键词（网络/amap）被误分类为环境（分类优先级见 lib/error-classify.cjs） */
        throw new TypeError('网络状态组件读取 undefined（injected-biz-error，refs assets/js/amap.js:1:1）');
      }, 600);
    });
    console.log('  [fault] biz-throw：已注入页面业务 TypeError（消息含 网络 + amap.js 路径）');
  }

  /* ---- 证据收集与分类（业务/环境分别保留；超时不武断归因） ----
     分类唯一实现在 lib/error-classify.cjs（selftest 直接调用同模块做回归）：
     页面运行时异常（TypeError/ReferenceError 等）一律业务，消息含 网络/域名/加载失败/exceeded/amap
     等普通字样不改变分类；环境只认 net::ERR_* 网络错误码、SDK 加载失败（AMap is not defined、
     amap.js 加载路径的确定字符串）、Key 错误、高德限流错误码。 */
  const { createErrorCollector } = require('./lib/error-classify.cjs');
  const collector = createErrorCollector();
  const pageJsErrors = collector.pageErrors;   /* 业务：页面脚本运行时异常等 */
  const mapEnvErrors = collector.envErrors;    /* 环境：网络错误码 / SDK 加载失败 / Key / 限流 */
  const amapFails = [];      /* 环境：高德域名请求失败（请求来源证据，已脱敏防 Key 泄漏） */
  const apiBizErrors = [];   /* 业务：页面 /api/ 请求返回 4xx/5xx */
  const sanitizeUrl = (u) => String(u).split('?')[0].slice(0, 120);   /* 只留协议+路径，去掉 query */
  const classify = collector.classify;
  /* pageerror 传 stack：首行含「TypeError: xxx」等异常名，供「异常名优先」分类 */
  page.on('pageerror', (e) => classify('pageerror', (e && e.stack) || (e && e.message) || String(e)));
  page.on('console', (m) => { if (m.type() === 'error') classify('console', m.text()); });
  page.on('requestfailed', (r) => {
    const u = r.url();
    const why = (r.failure() && r.failure().errorText) || '';
    /* 视野变化时高德主动取消旧瓦片请求（ERR_ABORTED）不算失败 */
    if (/amap|autonavi/.test(u) && !/\/tile\//.test(u) && why !== 'net::ERR_ABORTED') {
      amapFails.push(sanitizeUrl(u) + ' ' + why);
      console.log('[reqfail amap]', sanitizeUrl(u), why);
    }
  });
  page.on('response', (r) => {
    try {
      const u = r.url();
      if (/\/api\//.test(u) && r.status() >= 400) apiBizErrors.push(r.status() + ' ' + sanitizeUrl(u));
    } catch (e) { /* 忽略取 URL 失败 */ }
  });

  /* ---------- 1. 地图可见（里程碑 A） ---------- */
  console.log('--- 1. open /map/?trip=' + created.tripId + ' ---');
  await page.goto(ORIGIN + '/map/?trip=' + created.tripId, { waitUntil: 'domcontentloaded' });
  try {
    await waitFor('高德容器挂载 (#mapStage 有子节点)',
      () => page.evaluate(() => {
        const s = document.getElementById('mapStage');
        return !!s && s.children.length > 0;
      }), T_MAP_READY);
    ok('高德容器已挂载 (#mapStage 有子节点)');

    await waitFor('加载遮罩撤除（地图可见）',
      () => page.evaluate(() => document.getElementById('veilLoad').hidden), T_MAP_READY);
    ok('里程碑A：加载遮罩已撤除（地图可见）');
  } catch (e) {
    /* 证据在先、分类在后：有明确环境证据（SDK/网络/Key/限流）才归 env；
       有业务证据（页面脚本异常 / API 4xx5xx）归 biz；两者皆无 → 原因未定的超时 */
    const veilVisible = await page.locator('#veilErr').isVisible().catch(() => false);
    const veilText = veilVisible
      ? ((await page.locator('#errTitle').textContent().catch(() => '')) + ' '
        + (await page.locator('#errSub').textContent().catch(() => ''))).trim() : '';
    const envEvidence = mapEnvErrors.length > 0 || amapFails.length > 0
      || (veilVisible && /网络|加载失败|超时|Key|白名单|INVALID/i.test(veilText));
    const bizEvidence = pageJsErrors.length > 0 || apiBizErrors.length > 0;
    if (bizEvidence) {
      biz++;
      console.error('  !!  [biz] 地图可见里程碑超时，且存在业务失败证据（页面脚本异常/API 4xx5xx）: ' + e.message);
      if (pageJsErrors.length) console.error('       业务证据: ' + pageJsErrors.slice(0, 3).join(' | '));
      if (apiBizErrors.length) console.error('       API 证据: ' + apiBizErrors.slice(0, 3).join(' | '));
      if (envEvidence) envFail('（同时存在环境证据，分别保留: ' + (mapEnvErrors.length + amapFails.length) + ' 条）');
    } else if (envEvidence) {
      envFail('地图可见里程碑超时，有明确环境证据（SDK/网络/Key/限流）: ' + e.message
        + (veilText ? ' | 错误遮罩「' + veilText.slice(0, 60) + '」' : '')
        + (mapEnvErrors.length ? ' | console/pageerror ' + mapEnvErrors.length + ' 条: ' + mapEnvErrors.slice(0, 2).join(' | ') : '')
        + (amapFails.length ? ' | 高德请求失败 ' + amapFails.length + ' 条: ' + amapFails.slice(0, 2).join(' | ') : ''));
    } else {
      undetFail('原因未定的超时：地图未就绪，但无网络/SDK/Key 失败证据，也无业务异常证据（不武断归因）: ' + e.message);
    }
    throw new Error('地图可见里程碑未达成（分类见上），后续断言无法进行');
  }
  if (await page.locator('#veilErr').isVisible().catch(() => false)) {
    const t = (await page.locator('#errTitle').textContent()).trim();
    const s = (await page.locator('#errSub').textContent()).trim();
    if (/网络|加载失败|超时|Key|白名单|INVALID/i.test(t + s)) { envFail('地图错误遮罩: ' + t + ' | ' + s); throw new Error('环境失败（错误遮罩）: ' + t); }
    fatal('出现错误遮罩 → ' + t + ' | ' + s);
  }
  ok('加载完成，无错误遮罩');

  /* ---------- 2. 地图画布 ---------- */
  const canvases = await page.locator('#mapStage canvas').count();
  const tileImgs = await page.locator('#mapStage img').count();
  console.log('  canvas =', canvases, '| tile <img> =', tileImgs);
  check(canvases > 0 || tileImgs > 0, '地图渲染了画布/瓦片');

  /* ---------- 3. HUD 概览（标题/日期/点数即刻断言；里程归里程碑 B） ---------- */
  console.log('--- 3. HUD ---');
  const title = (await page.locator('#tcTitle').textContent()).trim();
  check(title === TITLE, '行程标题 = 夹具标题（旧「赤峰之旅」种子依赖已移除）: ' + title);
  const dates = (await page.locator('#tcDates').textContent()).trim();
  /* #tcDates 用中文日期（fmtYMD = YYYY年M月D日，无补零） */
  const startCn = START.slice(0, 4) + '年' + Number(START.slice(5, 7)) + '月' + Number(START.slice(8, 10)) + '日';
  check(dates.includes(startCn), '行程日期含起始日 ' + startCn + ': ' + dates);
  const points = (await page.locator('#stPoints').textContent()).trim();
  check(points === '6', '地点数 = 6（夹具），实为 ' + points);
  console.log('  title =', title, '| dates =', dates, '| points =', points);

  /* ---------- 4. 每日面板与标点 ---------- */
  console.log('--- 4. 每日面板与标点 ---');
  await waitFor('3 个天块渲染', () => page.locator('.day-block').count().then((n) => n === 3).catch(() => false));
  await waitFor('6 个列表地点', () => page.locator('.pl').count().then((n) => n === 6).catch(() => false));
  await waitFor('6 个地图标点', () => page.locator('.mk-dot').count().then((n) => n === 6).catch(() => false));
  const blocks = await page.locator('.day-block').count();
  const places = await page.locator('.pl').count();
  const markers = await page.locator('.mk-dot').count();
  check(blocks === 3, '3 个行程天（实为 ' + blocks + '）');
  check(places === 6, '列表地点 = 6（实为 ' + places + '）');
  check(markers === 6, '地图标点 = 6（实为 ' + markers + '）');
  await page.screenshot({ path: path.join(SHOTS, 'desktop.png') });
  console.log('  shot -> desktop.png');

  /* ---------- 5. 里程碑 B：路线全部完成（与「地图可见」解耦，独立等待） ---------- */
  console.log('--- 5. 里程碑B：路线全部完成 ---');
  let routesSettled = false;
  try {
    await waitFor('全部天路线完成（无「路线计算中」且里程/时长出数）', async () => {
      const st = await page.evaluate(() => {
        const metas = Array.from(document.querySelectorAll('.db-meta')).map((el) => el.textContent);
        const km = (document.getElementById('stKm') || {}).textContent || '';
        const dur = (document.getElementById('stTime') || {}).textContent || '';
        return { metas, km: km.trim(), dur: dur.trim() };
      });
      const pending = st.metas.some((m) => /路线计算中/.test(m));
      return !pending && /\d/.test(st.km) && /\d/.test(st.dur) ? st : null;
    }, T_ROUTES);
    routesSettled = true;
    ok('里程碑B：全部路线规划完成（里程/时长已出数）');
  } catch (e) {
    /* 证据在先、分类在后：页面自身降级提示（「未返回/虚线」= 页面明确标记高德未返回）或
       高德请求失败 → 环境证据；页面脚本异常 / API 4xx5xx → 业务证据；皆无 → 原因未定的超时。
       注意「正在规划驾车路线…」只是仍在进行，不构成失败证据。 */
    const notice = (await page.locator('#routeNotice').textContent().catch(() => '')).trim();
    const degraded = /未返回|虚线/.test(notice);
    const envEvidence = degraded || amapFails.length > 0 || mapEnvErrors.length > 0;
    const bizEvidence = pageJsErrors.length > 0 || apiBizErrors.length > 0;
    if (bizEvidence) {
      biz++;
      console.error('  !!  [biz] 路线里程碑超时，且存在业务失败证据（页面脚本异常/API 4xx5xx）: ' + e.message);
      if (pageJsErrors.length) console.error('       业务证据: ' + pageJsErrors.slice(0, 3).join(' | '));
      if (apiBizErrors.length) console.error('       API 证据: ' + apiBizErrors.slice(0, 3).join(' | '));
      if (envEvidence) envFail('（同时存在环境证据，分别保留: ' + notice.slice(0, 80) + '）');
    } else if (envEvidence) {
      envFail('路线规划有明确环境证据未完成（页面降级提示/高德请求失败）: ' + notice.slice(0, 80)
        + (amapFails.length ? ' | 高德请求失败 ' + amapFails.length + ' 条: ' + amapFails.slice(0, 2).join(' | ') : '')
        + (mapEnvErrors.length ? ' | ' + mapEnvErrors.slice(0, 2).join(' | ') : ''));
    } else {
      undetFail('原因未定的超时：路线未在时限内完成，无环境失败证据也无业务异常证据: ' + e.message
        + (notice ? ' | routeNotice=' + notice.slice(0, 80) : ''));
    }
  }

  if (routesSettled) {
    const st = await page.evaluate(() => ({
      metas: Array.from(document.querySelectorAll('.db-meta')).map((el) => el.textContent.replace(/\s+/g, ' ').trim()),
      km: document.getElementById('stKm').textContent.trim(),
      dur: document.getElementById('stTime').textContent.trim(),
      notice: document.getElementById('routeNotice').textContent.trim(),
      noticeVisible: !document.getElementById('routeNotice').hidden
    }));
    st.metas.forEach((m, i) => console.log('  D' + (i + 1) + ' meta:', m));
    console.log('  km =', st.km, '| dur =', st.dur, '| 降级提示 =', st.noticeVisible ? st.notice : '隐藏');

    const degraded = st.metas.some((m) => /直线示意/.test(m)) || /未返回/.test(st.notice) || amapFails.length > 0;
    if (degraded) {
      envFail('存在降级/失败路线（AMap 未返回，属环境）: ' + st.notice.slice(0, 100));
    } else {
      check(!st.metas.some((m) => /路线计算中/.test(m)), '没有天仍显示「路线计算中」');
      check(st.metas.slice(1).every((m) => /转场/.test(m)), '第 2、3 天显示跨天转场里程');
      check(/\d/.test(st.km) && /\d/.test(st.dur), '里程/时长格式正常: ' + st.km + ' / ' + st.dur);
      const kmNum = parseFloat(st.km.replace(/[^\d.]/g, ''));
      /* 夹具天内合计 <20km，跨天转场合计 ≈1780km → >800 证明总里程含跨天转场 */
      check(kmNum > 800, '总里程包含跨天转场（应 >800，实为 ' + st.km + '）');
    }
    await page.screenshot({ path: path.join(SHOTS, 'route-done.png') });
    console.log('  shot -> route-done.png');
  } else {
    console.log('  (skip) 路线相关断言因环境失败跳过（整体不计通过）');
  }

  /* 基线：全览视口的标记框（路线阶段不改视图——性能红线：路线完成后不 setFitView）。
     必须在「地点联动」缩放之前采集，供后续 聚焦 / 重置 / 回到全览 的视口变化断言对照。 */
  const tracked = page.locator('.mk-wrap').nth(2);   /* 第 2 天首点：聚焦期间保持在视野内 */
  const baseline = await stableBox(tracked, '基线标记框（全览）');
  console.log('  baseline box = x:' + Math.round(baseline.x) + ' y:' + Math.round(baseline.y)
      + ' w:' + Math.round(baseline.width));

  /* ---------- 6. 地点联动 ---------- */
  console.log('--- 6. 地点联动定位 ---');
  await page.locator('.pl').first().click();
  await waitFor('点击列表项后 1 个高亮标点',
    () => page.locator('.mk-wrap.hot').count().then((n) => n === 1).catch(() => false), 6000);
  const hotLi = await page.locator('.pl.is-hot').count();
  check(hotLi === 1, '列表项高亮自身（实为 ' + hotLi + '）');
  ok('点列表项 → 标点放大高亮 + 列表高亮');

  /* ---------- 7. 按天聚焦（含视口真实变化断言） ---------- */
  console.log('--- 7. 按天聚焦 ---');
  await page.locator('.day-block').nth(1).locator('.db-head').click();
  await waitFor('聚焦态生效', () => page.locator('.day-block.is-focus').count().then((n) => n === 1).catch(() => false));
  check(await page.locator('#dpReset').isVisible(), '聚焦后「重置聚焦」按钮出现');
  const focusedBox = await stableBox(tracked, '聚焦后标记框').catch(() => null);
  const focusedDelta = focusedBox ? boxDelta(focusedBox, baseline) : 0;
  check(focusedDelta > 20, '聚焦 D2 → 天块高亮 + 地图真实缩放到该天（标记框位移 ' + Math.round(focusedDelta) + 'px）');
  const dimCount = await page.locator('.mk-wrap.dim').count();
  check(dimCount === 4, '聚焦后非当天标点置灰（dim × ' + dimCount + '，应 4 = 6−2）');
  ok('聚焦 D2 → 天块高亮 + 重置按钮出现');
  await page.screenshot({ path: path.join(SHOTS, 'focus-day.png') });

  /* ---------- 8. 重置聚焦（恢复全览） ---------- */
  console.log('--- 8. 重置聚焦 ---');
  await page.locator('#dpReset').click();
  await waitFor('重置后无聚焦天块', () => page.locator('.day-block.is-focus').count().then((n) => n === 0).catch(() => false));
  check(await page.locator('#dpReset').isHidden(), '重置聚焦 → 聚焦清除、按钮隐藏');
  const afterReset = await stableBox(tracked, '重置后标记框').catch(() => null);
  const resetDelta = afterReset ? boxDelta(afterReset, baseline) : 9999;
  check(resetDelta <= 24, '重置聚焦 → 视口回到全览基线（偏差 ' + Math.round(resetDelta) + 'px ≤24）');
  ok('重置聚焦 → 恢复全览');

  /* ---------- 9. 回到全览（替代已移除的 #railToggle 侧栏折叠 resize 断言） ---------- */
  console.log('--- 9. 回到全览 ---');
  /* 先再次聚焦，制造「需要回到全览」的前置状态；btnFit 现有行为=清除聚焦+全览贴合 */
  await page.locator('.day-block').nth(1).locator('.db-head').click();
  await waitFor('再次聚焦', () => page.locator('.day-block.is-focus').count().then((n) => n === 1).catch(() => false));
  await page.locator('#btnFit').click();
  await waitFor('btnFit 清除聚焦', () => page.locator('.day-block.is-focus').count().then((n) => n === 0).catch(() => false));
  check(await page.locator('#dpReset').isHidden(), '回到全览 → 聚焦状态同步清除');
  const afterFit = await stableBox(tracked, '回到全览后标记框').catch(() => null);
  const fitDelta = afterFit ? boxDelta(afterFit, baseline) : 9999;
  check(fitDelta <= 24, '点「回到全览」→ 视口恢复全览（偏差 ' + Math.round(fitDelta)
      + 'px ≤24；聚焦态偏差 ' + Math.round(focusedDelta) + 'px）');
  await page.screenshot({ path: path.join(SHOTS, 'fit-overview.png') });

  /* ---------- 10. 底图切换 ---------- */
  console.log('--- 10. 底图切换 ---');
  await page.locator('#layerSeg button[data-layer="satellite"]').click();
  await waitFor('卫星态 aria-pressed', () => page.locator('#layerSeg button[data-layer="satellite"]')
    .getAttribute('aria-pressed').then((v) => v === 'true').catch(() => false));
  const normalPressed = await page.locator('#layerSeg button[data-layer="normal"]').getAttribute('aria-pressed');
  check(normalPressed === 'false', '标准态同步取消（sat=true normal=' + normalPressed + '）');
  ok('切换卫星底图 → aria-pressed 正确');
  await page.screenshot({ path: path.join(SHOTS, 'satellite.png') });

  await page.locator('#layerSeg button[data-layer="normal"]').click();
  await waitFor('切回标准底图', () => page.locator('#layerSeg button[data-layer="normal"]')
    .getAttribute('aria-pressed').then((v) => v === 'true').catch(() => false));
  ok('切回标准底图');

  /* ---------- 11. 移动端抽屉 ---------- */
  console.log('--- 11. 移动端 390×844 ---');
  await page.setViewportSize({ width: 390, height: 844 });
  await waitFor('移动端「每日行程」开关可见', () => page.locator('#btnPanel').isVisible().catch(() => false));
  const panelOpenBefore = await page.locator('#dayPanel.open').count();
  check(panelOpenBefore === 0, '移动端抽屉初始应收起');
  ok('移动端抽屉初始收起 + 开关可见');
  await page.screenshot({ path: path.join(SHOTS, 'mobile-closed.png') });

  /* 期望行为（map/index.html @media ≤720px）：展开 = bottom:12px + transform:none + opacity:1，
     过渡 transform .28s / opacity .22s；收起 = translateY(calc(100%+28px)) + opacity:0 + pointer-events:none。
     修复假通过：仅凭 .open 类断言会在动画进行中采样（实测曾采到收起位 y=860 的框仍在视口外
     却因 y>=100 之外的宽松检查通过）——必须等计算样式到达目标态且框连续两次采样稳定后再断言/截图。 */
  const panel = page.locator('#dayPanel');
  const VH = 844, BOTTOM_GAP = 12;   /* 视口高与期望的底部贴边距（CSS bottom:12px） */
  await page.locator('#btnPanel').click();
  await waitFor('抽屉 .open 类生效', () => panel.evaluate((el) => el.classList.contains('open')).catch(() => false), 6000);
  await waitFor('抽屉过渡到达展开目标态（transform:none & opacity:1）', () => page.evaluate(() => {
    const cs = getComputedStyle(document.getElementById('dayPanel'));
    const settled = (cs.transform === 'none' || cs.transform === 'matrix(1, 0, 0, 1, 0, 0)') && cs.opacity === '1';
    return settled ? cs.transform : null;
  }), 6000);
  const sheetBox = await stableBox(panel, '展开态抽屉框稳定');
  check(!!sheetBox && sheetBox.width >= 340 && sheetBox.height >= 80 && sheetBox.height <= (58 / 100) * VH + 2,
    '抽屉有效尺寸（' + (sheetBox ? Math.round(sheetBox.width) + '×' + Math.round(sheetBox.height) : '?')
    + '，预期宽≈366、高 ≤58vh=' + Math.round((58 / 100) * VH) + '）');
  check(!!sheetBox && sheetBox.y >= 100 && sheetBox.y + sheetBox.height <= VH,
    '抽屉整体在视口内（y=' + (sheetBox ? Math.round(sheetBox.y) : '?') + '，底边='
    + (sheetBox ? Math.round(sheetBox.y + sheetBox.height) : '?') + ' ≤ ' + VH + '）');
  check(!!sheetBox && Math.abs((sheetBox.y + sheetBox.height) - (VH - BOTTOM_GAP)) <= 8,
    '抽屉锚定底部 12px（底边 ' + (sheetBox ? Math.round(sheetBox.y + sheetBox.height) : '?')
    + ' ≈ ' + (VH - BOTTOM_GAP) + '±8）');
  /* 核心内容与关闭按钮真实可见、可点（几何都在视口内，而非仅元素存在） */
  check(await page.locator('#dayPanel .dp-head').isVisible(), '抽屉头部（每日行程标题栏）可见');
  const dayBlocksInPanel = await page.locator('#dayPanel .day-block').count();
  check(dayBlocksInPanel === 3, '抽屉内 3 个天块（实为 ' + dayBlocksInPanel + '）');
  const firstBlockBox = await page.locator('#dayPanel .day-block').first().boundingBox();
  check(!!firstBlockBox && firstBlockBox.y >= 0 && firstBlockBox.y + firstBlockBox.height <= VH,
    '首个天块完整落在视口内（y=' + (firstBlockBox ? Math.round(firstBlockBox.y) : '?') + '）');
  const closeBox = await page.locator('#dpClose').boundingBox();
  check(!!closeBox && closeBox.width > 0 && closeBox.height > 0 && closeBox.y >= 0 && closeBox.y + closeBox.height <= VH,
    '关闭按钮 #dpClose 在视口内可见可点（y=' + (closeBox ? Math.round(closeBox.y) : '?') + '）');
  const ariaOpen = await page.locator('#btnPanel').getAttribute('aria-expanded');
  check(ariaOpen === 'true', '开关 aria-expanded=true（实为 ' + ariaOpen + '）');
  await page.screenshot({ path: path.join(SHOTS, 'mobile-open.png') });
  console.log('  shot -> mobile-open.png（展开稳定后 box=' + (sheetBox
    ? 'x:' + Math.round(sheetBox.x) + ' y:' + Math.round(sheetBox.y) + ' w:' + Math.round(sheetBox.width) + ' h:' + Math.round(sheetBox.height)
    : '?') + '）');

  await page.locator('#dpClose').click();
  await waitFor('抽屉 .open 类移除', () => panel.evaluate((el) => !el.classList.contains('open')).catch(() => false), 6000);
  /* 实际收起：计算样式到隐藏目标态，且框已移出视口（不只看类名） */
  await waitFor('抽屉过渡回到隐藏态（opacity:0 & pointer-events:none）', () => page.evaluate(() => {
    const cs = getComputedStyle(document.getElementById('dayPanel'));
    return cs.opacity === '0' && cs.pointerEvents === 'none' ? cs.opacity : null;
  }), 6000);
  const closedBox = await stableBox(panel, '收起态抽屉框稳定');
  check(!!closedBox && closedBox.y >= VH,
    '抽屉实际移出视口（收起位 y=' + (closedBox ? Math.round(closedBox.y) : '?') + ' ≥ ' + VH + '）');
  check(await page.locator('#btnPanel').isVisible(), '开关恢复可见（is-hidden 移除）');
  check((await page.locator('#btnPanel').getAttribute('aria-expanded')) === 'false', '开关 aria-expanded=false');
  await page.screenshot({ path: path.join(SHOTS, 'mobile-closed-after.png') });
  console.log('  shot -> mobile-closed-after.png');
  ok('抽屉展开/收起全链路（真实几何、可达性与状态同步）');

  /* ---------- 12. 无参数进入 → 行程选择器 ---------- */
  console.log('--- 12. 参数缺失降级（行程选择器） ---');
  await page.setViewportSize({ width: 1512, height: 900 });
  await page.goto(ORIGIN + '/map/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#veilPick:not([hidden])', { timeout: 10000 });
  await waitFor('选择器有选项', () => page.locator('#tripSelect option').count().then((n) => n > 0).catch(() => false));
  const optCount = await page.locator('#tripSelect option').count();
  check(optCount >= 1, '行程选择器有选项（' + optCount + ' 个）');
  /* 选中本次夹具行程（按准确 ID，不依赖排序/默认值） */
  await page.selectOption('#tripSelect', created.tripId);
  await page.locator('#btnOpenTrip').click();
  await waitFor('选择行程后天块渲染', () => page.locator('#dayList .day-block').count().then((n) => n === 3).catch(() => false), 60000);
  check(page.url().includes('trip=' + created.tripId), '选择行程后 URL 带行程参数（' + page.url().split('?')[1] + '）');
  ok('选择行程后地图就地加载');
  await page.screenshot({ path: path.join(SHOTS, 'no-param.png') });

  /* ---------- 13. 错误分类汇总 ---------- */
  check(pageJsErrors.length === 0, '无页面业务 JS 错误' + (pageJsErrors.length ? ' -> ' + pageJsErrors.slice(0, 3).join(' | ') : ''));
  check(apiBizErrors.length === 0, '页面 /api/ 请求无 4xx/5xx（业务证据）'
    + (apiBizErrors.length ? ' -> ' + apiBizErrors.slice(0, 3).join(' | ') : ''));
  if (mapEnvErrors.length) envFail('地图 SDK/网络/Key 类错误 ' + mapEnvErrors.length + ' 条: ' + mapEnvErrors.slice(0, 2).join(' | '));
  else ok('无地图 SDK/网络/Key 类错误');
  if (amapFails.length) envFail('高德请求失败 ' + amapFails.length + ' 条（URL 已脱敏）: ' + amapFails.slice(0, 2).join(' | '));
  else ok('高德请求零失败');
  console.log('\namap 请求失败数 =', amapFails.length);
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
  if (created.tripId && /^[0-9]+$/.test(created.tripId)) {
    const t = q(created.tripId);
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
    runT('file_metadata', `DELETE FROM file_metadata WHERE owner_user_id=${u};`);
    runT('trips', `DELETE FROM trips WHERE owner_id=${u};`);
    runT('trip_members', `DELETE FROM trip_members WHERE user_id=${u};`);
    runT('users', `DELETE FROM users WHERE id=${u};`);
  } else if (created.userId) {
    errors.push('userId 非法（拒绝拼 SQL）: ' + created.userId);
  }
  if (skipped.length) console.log('  (skip) 本地库不存在的表: ' + Array.from(new Set(skipped)).join(', '));

  try {
    const cnt = (sql) => Number(psql(sql));
    const left = {
      trips: created.tripId ? cnt(`SELECT count(*) FROM trips WHERE id=${q(created.tripId)} OR owner_id=${created.userId ? q(created.userId) : "''"}`) : 0,
      users: created.userId ? cnt(`SELECT count(*) FROM users WHERE id=${q(created.userId)}`) : 0,
      members: cnt(`SELECT count(*) FROM trip_members WHERE user_id=${created.userId ? q(created.userId) : "''"}`),
      places: created.tripId ? cnt(`SELECT count(*) FROM trip_places WHERE trip_id=${q(created.tripId)}`) : 0,
      locks: tableExists('edit_locks')
        ? cnt(`SELECT count(*) FROM edit_locks WHERE scope_id IN (SELECT id FROM trip_days WHERE trip_id=${created.tripId ? q(created.tripId) : "''"})`) : 0,
      filemeta: created.userId && tableExists('file_metadata')
        ? cnt(`SELECT count(*) FROM file_metadata WHERE owner_user_id=${q(created.userId)}`) : 0
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
  if (browser) { try { await browser.close(); } catch (e) { /* 忽略关闭失败 */ } }

  let cleanupErr = null;
  console.log('--- cleanup（按本次准确 ID）---');
  try { cleanup(); console.log('  OK  夹具已清除并核验残留为 0'); }
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
