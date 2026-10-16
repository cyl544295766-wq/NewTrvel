/* Travel OS · 生产环境全功能验收
 * 用法：node tools/e2e/verify-prod.cjs  （目标 http://140.143.166.155，可用 PROD_ORIGIN 覆盖）
 * 只读断言跑在现有行程上；写操作跑在临时行程，结束后软删除 + 清理 AI 会话。
 */
const { chromium } = require('playwright-core');
const fs = require('fs');

const ORIGIN = process.env.PROD_ORIGIN || 'http://140.143.166.155';
const USER = process.env.PROD_USER || 'admin';
const PASS = process.env.PROD_PASS || 'admin123';
const TINY_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

let failures = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { failures++; console.error('  !!  ' + msg); } }
function ymd(offset) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
let TOKEN, H;

async function api(method, p, body) {
  const r = await fetch(ORIGIN + p, {
    method, headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', ...H },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch (_) {}
  return { status: r.status, json };
}

async function main() {
  console.log('目标: ' + ORIGIN);

  /* ---- 1. 基础 ---- */
  console.log('\n=== 1. 基础与鉴权 ===');
  const health = await api('GET', '/api/health');
  check(health.status === 200, '健康检查 200');
  const login = await api('POST', '/api/auth/login', { username: USER, password: PASS });
  check(login.status === 200 && login.json.token, '登录成功并返回 token');
  TOKEN = login.json.token;
  H = { 'Authorization': 'Bearer ' + TOKEN };
  const me = await api('GET', '/api/auth/me');
  check(me.status === 200, '/auth/me 正常（当前用户 ' + (me.json.user || {}).username + '）');

  /* ---- 2. 全局数据接口 ---- */
  console.log('\n=== 2. 全局接口（此前 500 的两个重点验证） ===');
  const dash = await api('GET', '/api/dashboard');
  check(dash.status === 200, '/api/dashboard 200');
  const stats = await api('GET', '/api/stats/overview');
  check(stats.status === 200, '/api/stats/overview 200');
  const sug = await api('GET', '/api/places/suggestions?keyword=' + encodeURIComponent('布达拉宫'));
  const sugHasCoord = (sug.json.suggestions || []).some(s => s.latitude && s.longitude);
  check(sug.status === 200 && sugHasCoord, '地点联想 200 且含坐标项（高德 Web 服务 Key 生效）');
  const unread = await api('GET', '/api/notifications/unread-count');
  check(unread.status === 200, '通知未读数 200（count=' + unread.json.count + '）');

  /* ---- 3. 临时行程全链路（写操作） ---- */
  console.log('\n=== 3. 行程全链路（临时行程） ===');
  const t = (await api('POST', '/api/trips', {
    title: '线上验收行程（临时）', destination: '拉萨', startDate: ymd(2), endDate: ymd(4),
    description: 'verify-prod 临时数据，将自动清理'
  })).json.trip;
  const TEMP = t.id;
  check(!!TEMP, '创建临时行程 ' + TEMP);
  await api('POST', `/api/trips/${TEMP}/days/generate`, {});
  const days = (await api('GET', `/api/trips/${TEMP}/days`)).json.days || [];
  check(days.length === 3, '行程天生成 3 天');
  const p1 = await api('POST', `/api/trips/${TEMP}/places`, {
    tripDayId: days[0].id, name: '布达拉宫', type: 'attraction',
    longitude: 91.1169468, latitude: 29.6571781
  });
  await api('POST', `/api/trips/${TEMP}/places`, {
    tripDayId: days[0].id, name: '大昭寺', type: 'attraction',
    longitude: 91.1313060, latitude: 29.6529325
  });
  check(p1.status === 200, '添加带坐标地点 ×2');
  const daysAfter = (await api('GET', `/api/trips/${TEMP}/days`)).json.days;
  const withCoord = daysAfter[0].places.filter(p => p.latitude != null && p.longitude != null).length;
  check(withCoord === 2, '地点坐标已落库（' + withCoord + '/2）');
  const weather = await api('GET', `/api/trips/${TEMP}/weather`);
  check(weather.status === 200, '整程天气聚合 200');
  const exp = await api('POST', `/api/trips/${TEMP}/expenses`, {
    amount: '128.5', category: 'food', title: '验收餐费', spentAt: ymd(2) + ' 12:00:00'
  });
  check(exp.status === 200, '记账 200（含分摊生成）');
  const budget = await api('PUT', `/api/trips/${TEMP}/budget`, { budgets: [{ category: 'food', amount: '500' }] });
  check(budget.status === 200, '类别预算设置 200');
  const bk = await api('POST', `/api/trips/${TEMP}/bookings`, {
    type: 'flight', title: '验收航班', startTime: ymd(2) + ' 09:00:00', orderNo: 'VP-1',
    extra: { flightNo: 'CA777' }, reminderMinutes: 120, reminderEnabled: true
  });
  check(bk.status === 200, '预订创建 200');
  const reminders = await api('GET', `/api/trips/${TEMP}/bookings/reminders`);
  check(reminders.status === 200 && (reminders.json.reminders || []).length >= 1, '提醒时间线 200 且有条目');
  const photo = await api('POST', `/api/trips/${TEMP}/photos`, {
    url: TINY_PNG, caption: '验收照片', shotAt: ymd(2) + ' 10:00:00'
  });
  check(photo.status === 200, '照片上传 200');
  const journal = await api('POST', `/api/trips/${TEMP}/journals`, {
    title: '线上验收游记', content: 'verify-prod', mood: 'happy', isDraft: false,
    photoIds: [(photo.json.photo || photo.json).id]
  });
  check(journal.status === 200, '游记创建 200（带照片）');
  const docs = await api('GET', `/api/trips/${TEMP}/documents`);
  check(docs.status === 200, '证件列表 200');
  const packing = await api('GET', `/api/trips/${TEMP}/packing-lists`);
  check(packing.status === 200, '打包清单 200');

  /* ---- 4. AI 助手（DeepSeek + 页面导航） ---- */
  console.log('\n=== 4. AI 能力 ===');
  const conv = (await api('POST', '/api/ai/conversations', { tripId: TEMP, title: '线上验收' })).json.conversation;
  const aiStart = Date.now();
  const ai = await api('POST', `/api/ai/conversations/${conv.id}/messages`, { content: '帮我打开行程规划页面' });
  check(ai.status === 200 && (ai.json.assistantMessage.content || '').length > 5,
    'AI 真实回复（' + (Date.now() - aiStart) + 'ms）：' + (ai.json.assistantMessage.content || '').slice(0, 30) + '…');
  check(!!ai.json.assistantMessage.navigation, '页面导航字段正常（' + (ai.json.assistantMessage.navigation || {}).url + '）');
  await api('DELETE', '/api/ai/conversations/' + conv.id);

  /* ---- 5. 浏览器实测 ---- */
  console.log('\n=== 5. 浏览器实测 ===');
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message.slice(0, 100)));
  await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.evaluate(([tk, u]) => {
    localStorage.setItem('travelos.token', tk);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, [TOKEN, login.json.user || { username: USER }]);
  const amapErrors = [];
  page.on('console', m => { if (/域名|白名单|INVALID_USER/i.test(m.text())) amapErrors.push(m.text().slice(0, 100)); });

  // 首页
  await page.goto(ORIGIN + '/home/', { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(800);
  const home = await page.evaluate(() => ({
    statsInHero: !!document.querySelector('.hero .stats'),
    statLinks: Array.from(document.querySelectorAll('a.stat')).map(a => a.getAttribute('href')),
    sideLogout: !!document.getElementById('sideLogout'),
    sticky: getComputedStyle(document.querySelector('.side')).position
  }));
  check(home.statsInHero, '首页统计条已融入 Hero 背景图');
  check(home.statLinks.join() === '/trips/,/expenses/,/stats/,/packing/', '统计卡跳转链接正确');
  check(home.sideLogout && home.sticky === 'sticky', '侧栏退出按钮可见且吸附视口');

  // 地图页（临时行程，2 个带坐标地点）
  await page.goto(ORIGIN + '/map/?trip=' + TEMP, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('#dayList .day-block', { timeout: 30000 }).catch(() => {});
  await page.waitForFunction(() => document.querySelectorAll('.amap-marker').length > 0, { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(1000);
  const map = await page.evaluate(() => ({
    container: !!document.querySelector('.amap-container'),
    markers: document.querySelectorAll('.amap-marker').length
  }));
  check(map.container, '地图容器渲染（高德 JS Key 在线上可用' + (amapErrors.length ? '）' : '，无白名单报错）'));
  check(map.markers >= 2, '地图标记 ' + map.markers + ' 个（≥2）');

  // 行程详情
  await page.goto(ORIGIN + '/trip-detail/?id=' + TEMP, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForSelector('#modules .module', { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(1200);
  const badges = (await page.locator('#modules .module .badge').allTextContents()).map(s => s.trim());
  check(badges.length === 8 && !badges.includes('–'), '行程详情 8 模块计数真实：' + badges.join(' | '));
  check(errors.length === 0, '访问页面无 JS 报错' + (errors.length ? '（' + errors[0] + '）' : ''));
  await browser.close();

  /* ---- 6. 清理 ---- */
  console.log('\n=== 6. 清理 ===');
  const del = await api('DELETE', '/api/trips/' + TEMP);
  check(del.status === 200, '临时行程已删除（软删除）');
  const convs = ((await api('GET', '/api/ai/conversations?tripId=' + TEMP)).json.conversations) || [];
  for (const c of convs) await api('DELETE', '/api/ai/conversations/' + c.id);
  check(((await api('GET', '/api/ai/conversations?tripId=' + TEMP)).json.conversations || []).length === 0, 'AI 会话已清空');

  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
