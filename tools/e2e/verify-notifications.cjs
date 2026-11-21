// Travel OS · verify-notifications：通知中心端到端验证（自包含夹具）
// 覆盖：未读/重要徽标、统计卡与 API 一致、今天/昨天分组、类型与状态筛选、
//       行程上下文 chip 解析、链接改写、单条已读、全部已读（DB 核验）、清除已读按钮不点击。
// 夹具：自注册用户 + 自建行程；仅向该用户种入 2 条通知（动态 ID，按准确 ID 清理）。
// 运行：NODE_PATH=<managed node_modules> node tools/e2e/verify-notifications.cjs
const { chromium } = require('playwright-core');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, psql } = require('./lib/fixture.cjs');
const fs = require('fs');
const path = require('path');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/notifications-shots';
assertLocalOrigin(ORIGIN);

const cleaner = new Cleaner();
let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

let browser = null;
const seededIds = [];
let tripId = null;

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册用户 U + U 自建行程（chip 解析行程名用）；清空 U 的既有通知后种入 2 条 ----
  const user = await registerUser(ORIGIN, 'nt');
  cleaner.addUser(user.id);
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '通知验证行程（临时）', destination: '通知港', startDate: '2026-06-10', endDate: '2026-06-12'
  })).trip.id);
  psql("DELETE FROM notifications WHERE user_id = '" + user.id + "';");  // 清理自己账号的既有通知，保证计数确定
  const n1 = 'e2ent1-' + Date.now().toString(36);
  const n2 = 'e2ent2-' + Date.now().toString(36);
  seededIds.push(n1, n2);
  psql("INSERT INTO notifications (id, user_id, type, title, content, link, metadata, is_read, priority, created_at) VALUES "
    + "('" + n1 + "', '" + user.id + "', 'trip_upcoming', '验证通知·行程临近', '再过 3 天就要出发了，记得检查打包清单和证件。', "
    + "'/trips/" + tripId + "/packing', '{\"kind\":\"booking_reminder\",\"tripId\":\"" + tripId + "\"}'::jsonb, false, 'high', (now() AT TIME ZONE 'Asia/Shanghai'));");
  psql("INSERT INTO notifications (id, user_id, type, title, content, link, metadata, is_read, priority, created_at) VALUES "
    + "('" + n2 + "', '" + user.id + "', 'system', '验证通知·系统消息', '这是一条已读的普通系统通知。', "
    + "'/trips/" + tripId + "/expenses', '{\"kind\":\"settlement_confirmed\",\"tripId\":\"" + tripId + "\"}'::jsonb, true, 'normal', (now() AT TIME ZONE 'Asia/Shanghai') - interval '1 day');");
  const apiList = await apiU.get('/api/notifications?page=1&pageSize=20');
  const apiTotal = String(apiList.total);
  ok('种入 2 条通知 | API total = ' + apiTotal);

  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token: user.token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));

  console.log('--- 加载 /notifications/ ---');
  await page.goto(ORIGIN + '/notifications/', { waitUntil: 'domcontentloaded' });
  await waitFor('通知列表渲染', () => page.locator('.n-row').count().then((n) => n >= 2).catch(() => false), 15000);

  const unread = (await page.locator('#sUnread').textContent()).trim();
  const imp = (await page.locator('#sImp').textContent()).trim();
  const today = (await page.locator('#sToday').textContent()).trim();
  const totalN = (await page.locator('#sTotal').textContent()).trim();
  check(unread === '1', '未读数 = 1: ' + unread);
  check(Number(imp) >= 1, '重要数 ≥1: ' + imp);
  check(Number(today) >= 1, '今天数 ≥1: ' + today);
  check(Number(totalN) === Number(apiTotal), '总数与 API 一致: ' + totalN + ' vs ' + apiTotal);

  const row1 = page.locator('.n-row', { hasText: '验证通知·行程临近' });
  check((await row1.count()) === 1, '种入的通知 1 已渲染');
  const cls1 = (await row1.getAttribute('class')) || '';
  check(cls1.includes('unread'), '通知 1 带 .unread');
  check(cls1.includes('important'), '通知 1 带 .important');
  check((await row1.locator('.badge-imp').count()) === 1, '通知 1 有「重要」徽标');
  check((await row1.locator('.badge-unread').count()) === 1, '通知 1 有未读圆点');

  const chip = (await row1.locator('.n-ctx .chip').first().textContent()).trim();
  check(chip.includes('通知验证行程（临时）'), '行程上下文 chip 解析出行程名: ' + chip);

  const goHref = await row1.locator('.n-go').getAttribute('href');
  check(goHref === '/packing/?trip=' + tripId, '链接改写 → /packing/?trip=: ' + goHref);
  const row2 = page.locator('.n-row', { hasText: '验证通知·系统消息' });
  const goHref2 = await row2.locator('.n-go').getAttribute('href');
  check(goHref2 === '/expenses/?trip=' + tripId, '链接改写 2 → /expenses/?trip=: ' + goHref2);

  const groupTxt = await page.locator('.group .gt').allTextContents();
  check(groupTxt.some((t) => t.includes('今天')), '存在「今天」分组');
  check(groupTxt.some((t) => t.includes('昨天')), '存在「昨天」分组');

  const typeRow = page.locator('.fitem[data-t="trip_upcoming"]');
  check((await typeRow.textContent()).trim().includes('1'), 'trip_upcoming 筛选计数 = 1');

  await page.screenshot({ path: path.join(SHOTS, 'feed.png'), fullPage: true });

  // ---- 状态筛选：未读 ----
  await page.click('.fitem[data-s="unread"]');
  await waitFor('未读筛选只剩 1 行', () => page.locator('.n-row').count().then((n) => n === 1).catch(() => false), 8000);
  ok('状态筛选（unread → 1）');

  // ---- 类型筛选：trip_upcoming ----
  await page.click('.fitem[data-s="all"]');
  await page.click('.fitem[data-t="trip_upcoming"]');
  await waitFor('类型筛选只剩 1 行', () => page.locator('.n-row').count().then((n) => n === 1).catch(() => false), 8000);
  ok('类型筛选（trip_upcoming → 1）');
  await page.click('.fitem[data-t=""]');

  // ---- 单条已读 ----
  console.log('--- 单条已读 ---');
  const r1 = page.locator('.n-row', { hasText: '验证通知·行程临近' });
  await r1.hover();
  await r1.locator('.n-ops .rd').click();
  await waitFor('未读数归 0', () => page.locator('#sUnread').textContent().then((t) => t.trim() === '0').catch(() => false), 8000);
  const cls1b = (await page.locator('.n-row', { hasText: '验证通知·行程临近' }).getAttribute('class')) || '';
  check(!cls1b.includes('unread'), '已读后行不再带 .unread');
  ok('单条已读（未读 1 → 0）');

  // ---- 全部已读（DB 核验） ----
  await page.click('#btnReadAll');
  await waitFor('无未读行', () => page.locator('.n-row.unread').count().then((n) => n === 0).catch(() => false), 8000);
  const dbUnread = psql("SELECT count(*) FROM notifications WHERE user_id = '" + user.id + "' AND is_read = false;");
  check(dbUnread === '0', 'DB 未读 = 0: ' + dbUnread);
  ok('全部已读（DB 核验）');

  check((await page.locator('#btnClearRead').count()) === 1, '「清除已读」按钮存在（约定不点击）');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

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
  // 清理：种入的通知行（准确 ID）+ 夹具（含 U 的其余通知）
  try {
    if (seededIds.length) {
      psql("DELETE FROM notifications WHERE id IN ('" + seededIds.join("','") + "');");
    }
    cleaner.run();
    console.log('cleanup OK（残留核验 0）');
  } catch (e) {
    if (!failed) failed = e;
    console.error('清理失败: ' + (e && e.message));
  }
  process.exit(failed ? 1 : 0);   // 显式退出：事件循环可能有残留句柄（playwright/fetch keep-alive），不显式退出会挂住批量 runner
})();
