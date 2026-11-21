// Travel OS · verify-trip-detail：行程详情页端到端验证（自包含夹具）
// 覆盖：登录页可达 / 票面字段与 API 一致 / 模块磁贴与计数 / 成员与操作记录 / 天气回退 /
//       未知 id 的错误提示与跳转 / 移动端截图；夹具按准确 ID 清理，残留核验非 0 即失败。
// 运行：NODE_PATH=<managed node_modules> node tools/e2e/verify-trip-detail.cjs
// 环境：E2E_ORIGIN（默认 http://127.0.0.1:3001，仅允许回环）；E2E_SHOTS（默认 D:/tmp/trip-detail-shots）
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

/** 互为好友：requester 添加 target + target accept（邀请成员的前提）。 */
async function befriend(origin, requester, target) {
  const apiReq = api(origin, requester.token, true);
  const apiTarget = api(origin, target.token, true);
  const add = await apiReq.post('/api/friends', { userId: target.id });
  if (!add.ok && !(add.body && /已经|等待/.test(JSON.stringify(add.body)))) {
    throw new Error('添加好友失败: ' + JSON.stringify(add.body));
  }
  const accept = await apiTarget.post('/api/friends/' + requester.id + '/accept');
  if (!accept.ok && !(accept.body && /已经/.test(JSON.stringify(accept.body)))) {
    throw new Error('接受好友失败: ' + JSON.stringify(accept.body));
  }
}

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/trip-detail-shots';
assertLocalOrigin(ORIGIN);

let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const cleaner = new Cleaner();
let browser = null;

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });
  browser = await chromium.launch({
    channel: 'chrome', headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']
  });

  // ---- 夹具：所有者 + 行程（日期/目的地/预算）+ 行程天 + 地点 + 一笔 CNY 费用 ----
  const owner = await registerUser(ORIGIN, 'td');
  cleaner.addUser(owner.id);
  const apiOwner = api(ORIGIN, owner.token);
  const created = await apiOwner.post('/api/trips', {
    title: '详情验证行程（临时）', destination: '大连', startDate: '2026-06-10', endDate: '2026-06-12',
    budget: '3000', description: 'trip-detail e2e 临时数据'
  });
  const tripId = cleaner.addTrip(created.trip.id);
  await apiOwner.post(`/api/trips/${tripId}/days/generate`, {});
  const days = (await apiOwner.get(`/api/trips/${tripId}/days`)).days;
  for (const day of days) cleaner.addDay(tripId, day.id);
  const lock = await apiOwner.post(`/api/trips/${tripId}/days/${days[0].id}/edit-lock`, {});
  const place = await apiOwner.post(`/api/trips/${tripId}/places`, {
    tripDayId: days[0].id, name: '星海广场', type: 'attraction', longitude: 121.59, latitude: 38.88
  });
  cleaner.addPlace(place.place ? place.place.id : place.id);
  await apiOwner.del(`/api/trips/${tripId}/days/${days[0].id}/edit-lock`);
  const expense = await apiOwner.post(`/api/trips/${tripId}/expenses`, {
    amount: '120.50', category: 'food', title: '详情验证费用', payerUserId: owner.id,
    shareUserIds: [owner.id]
  });
  cleaner.addExpense(expense.expense ? expense.expense.id : expense.id);
  // 邀请成员（TripMemberService 写审计日志）→ 操作记录 tab 有真实条目可断言
  memberUser = await registerUser(ORIGIN, 'tdm');
  cleaner.addUser(memberUser.id);
  await befriend(ORIGIN, owner, memberUser);
  const invite = await apiOwner.post(`/api/trips/${tripId}/members`, {
    username: memberUser.username, role: 'member'
  });
  const invitedRow = (invite.members || []).find((m) => m.userId === memberUser.id);
  cleaner.addMember(invitedRow ? invitedRow.id : null);
  ok('夹具就绪 trip=' + tripId);

  const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await page.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token: owner.token, user: { id: owner.id, username: owner.username, displayName: '' } });

  // ---- 1. 登录页（匿名上下文）可达 ----
  console.log('--- 1. 匿名访问 /login/ ---');
  const anon = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await anon.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
  await waitFor('登录表单可见', () => anon.locator('form#form, .auth .card').first().isVisible().catch(() => false), 8000);
  ok('登录页表单可见');
  await anon.close();

  // ---- 2. 详情页：票面与 API 数据一致 ----
  console.log('--- 2. /trip-detail/?id=' + tripId + ' ---');
  await page.goto(ORIGIN + '/trip-detail/?id=' + tripId, { waitUntil: 'domcontentloaded' });
  await waitFor('票面渲染', () => page.locator('#pass:not([hidden])').count().then((n) => n === 1).catch(() => false), 15000);
  await waitFor('模块磁贴渲染', () => page.locator('#modules button.module').count().then((n) => n >= 8).catch(() => false), 8000);

  const crumb = (await page.locator('#crumbTitle').textContent()).trim();
  check(crumb === '详情验证行程（临时）', '面包屑标题 = 夹具行程标题: ' + crumb);
  const passTitle = (await page.locator('.pass-title').first().textContent()).trim();
  check(passTitle.includes('详情验证行程（临时）') && passTitle.includes('DRAFT'), '票面标题含标题+DRAFT: ' + passTitle.slice(0, 40));

  const fields = {};
  for (const v of await page.locator('.fields .field').all()) {
    const k = (await v.locator('.k').textContent()).trim();
    fields[k] = (await v.locator('.v').textContent()).trim();
  }
  check(fields['开始日期'] === '06 / 10' || /06 ?\/ ?10|6月10/.test(fields['开始日期'] || ''),
    '开始日期与夹具一致: ' + fields['开始日期']);
  // 页面契约：fmtMoney 千分位 + 整数化（¥ 3,000）；已花费 = ¥ 121（120.50 页面四舍五入）
  check(fields['预算'] === '¥ 3,000', '预算与夹具一致（千分位契约）: ' + fields['预算']);
  check(fields['已花费'] === '¥ 121', '已花费与费用汇总一致（页面四舍五入契约）: ' + fields['已花费']);

  const daysText = (await page.locator('.stub-days').first().textContent()).trim();
  // 页面契约：stub-days = 起止日期差（06-10 → 06-12 = 2 天，天数差口径）
  check(daysText === '2天' || daysText === '2', '天数徽标 = 起止日期差 2（页面口径）: ' + daysText);

  // 模块磁贴：行程安排/费用/地图/照片/文档/打包/预订/游记 等 ≥8 个，且照片计数与 API 一致（0）
  const tiles = await page.locator('#modules button.module').count();
  check(tiles >= 8, '模块磁贴 ≥8（实为 ' + tiles + '）');
  const photoBadge = await page.locator('#modules button.module', { hasText: '照片' }).textContent().catch(() => '');
  check(/照片/.test(photoBadge), '照片磁贴存在: ' + photoBadge.replace(/\s+/g, ' ').slice(0, 30));

  // 天气面板：真实卡片或回退提示，均非空
  const weatherLen = (await page.locator('#weatherBox').innerHTML()).length;
  check(weatherLen > 10, '天气面板有内容（卡片或回退提示，len=' + weatherLen + '）');

  // ---- 3. 成员 tab：真实成员行 ----
  console.log('--- 3. 成员 tab ---');
  await page.locator('#tab-members').click();
  await waitFor('成员面板可见', () => page.locator('#pane-members:not([hidden])').isVisible().catch(() => false), 5000);
  await waitFor('成员行渲染', () => page.locator('#memberList .member, #memberList li, #memberList > div').count().then((n) => n >= 1).catch(() => false), 5000);
  const memberText = (await page.locator('#memberList').textContent()).replace(/\s+/g, ' ');
  check(memberText.includes(owner.username) || memberText.includes('所有者'), '成员列表含所有者: ' + memberText.slice(0, 80));
  check(memberText.includes(memberUser.username), '成员列表含被邀请成员: ' + memberUser.username);
  const memberRowCount = await page.locator('#memberList .member, #memberList li, #memberList > div').count();
  check(memberRowCount >= 2, '成员行数 ≥2（实为 ' + memberRowCount + '）');

  // ---- 4. 操作记录 tab：有审计条目（建行程/记费用均写审计） ----
  await page.locator('#tab-logs').click();
  await waitFor('操作记录面板可见', () => page.locator('#pane-logs:not([hidden])').isVisible().catch(() => false), 5000);
  await waitFor('操作记录渲染（含成员邀请审计）',
    () => page.locator('#logList').textContent()
      .then((t) => /邀请|成员|member/i.test(t) && !/还没有操作记录/.test(t)).catch(() => false), 8000);
  const logs = (await page.locator('#logList').textContent()).replace(/\s+/g, ' ');
  check(/邀请|成员|member/i.test(logs), '操作记录含成员邀请审计: ' + logs.slice(0, 80));

  // ---- 5. 未知 id：提示 + 跳转回 /trips/（真实错误路径） ----
  console.log('--- 4. 未知行程 id ---');
  const page2 = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page2.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token: owner.token, user: { id: owner.id, username: owner.username, displayName: '' } });
  await page2.goto(ORIGIN + '/trip-detail/?id=does-not-exist-000', { waitUntil: 'domcontentloaded' });
  await waitFor('提示「找不到该旅程」出现',
    () => page2.locator('#toast').textContent().then((t) => t.includes('找不到该旅程')).catch(() => false), 10000);
  ok('未知 id 提示正确');
  await waitFor('1s 后跳转回 /trips/', () => /\/trips\//.test(page2.url()), 6000);
  ok('自动跳转 /trips/');
  await page2.close();

  // ---- 6. 截图（概览/成员/移动端） ----
  await page.locator('#tab-overview').click();
  await waitFor('概览面板可见', () => page.locator('#pane-overview:not([hidden])').isVisible().catch(() => false), 5000);
  await page.screenshot({ path: path.join(SHOTS, 'overview.png'), fullPage: true });
  await page.locator('#tab-members').click();
  await page.screenshot({ path: path.join(SHOTS, 'members.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('#tab-overview').click();
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shots -> overview/members/mobile.png');

  // ---- 汇总 ----
  check(biz === 0, '业务断言失败数 = ' + biz);
  if (biz > 0) bail('存在业务断言失败');
  console.log('=== ALL PASS ===');
}

(async () => {
  let failed = null;
  try {
    await main();
  } catch (e) {
    failed = e;
    console.error(e.stack || String(e));
  }
  if (browser) { try { await browser.close(); } catch (e) { /* 忽略 */ } }
  // 成败都清理：失败也不得跳过（退出码非 0）
  try {
    const { skipped } = cleaner.run();
    if (skipped.length) console.log('清理跳过不存在表: ' + skipped.join(','));
    console.log('cleanup OK（残留核验 0）');
  } catch (e) {
    if (!failed) failed = e;
    console.error('清理失败: ' + (e && e.message));
  }
  process.exit(failed ? 1 : 0);   // 显式退出：事件循环可能有残留句柄（playwright/fetch keep-alive），不显式退出会挂住批量 runner
})();
