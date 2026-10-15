// Verify notifications: unread badges / priority / grouping / type & status filters / link rewrite / mark read
const { chromium } = require('playwright-core');
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const ADMIN = 'cmrdbm84p0001fmbcs21zkqb2';
const SHOTS = 'D:/tmp/notifications-shots';
const SEED_IDS = ['verify-notif-1', 'verify-notif-2'];

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }
function psql(sql) {
  return execSync('docker exec travel-os-postgres psql -U travel_os -d travel_os -t -A -c ' + JSON.stringify(sql), { encoding: 'utf8' }).trim();
}

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- seed 2 notifications (1 unread+high, 1 read+normal) ----
  console.log('--- seed notifications ---');
  psql("DELETE FROM notifications WHERE id IN ('verify-notif-1','verify-notif-2');");
  psql("INSERT INTO notifications (id, user_id, type, title, content, link, metadata, is_read, priority, created_at) VALUES "
    + "('verify-notif-1', '" + ADMIN + "', 'trip_upcoming', '验证通知·行程临近', '再过 3 天就要出发了，记得检查打包清单和证件。', "
    + "'/trips/" + TRIP + "/packing', '{\"kind\":\"booking_reminder\",\"tripId\":\"" + TRIP + "\"}'::jsonb, false, 'high', now());");
  psql("INSERT INTO notifications (id, user_id, type, title, content, link, metadata, is_read, priority, created_at) VALUES "
    + "('verify-notif-2', '" + ADMIN + "', 'system', '验证通知·系统消息', '这是一条已读的普通系统通知。', "
    + "'/trips/" + TRIP + "/expenses', '{\"kind\":\"settlement_confirmed\",\"tripId\":\"" + TRIP + "\"}'::jsonb, true, 'normal', now() - interval '1 day');");
  const total = psql("SELECT count(*) FROM notifications WHERE user_id = '" + ADMIN + "';");
  console.log('seeded 2 | DB total =', total);
  /* API 会过滤已过期通知，用 API 的 total 作为期望值 */
  const apiList = await (await fetch(ORIGIN + '/api/notifications?page=1&pageSize=20', { headers: { 'Authorization': 'Bearer ' + token } })).json();
  const apiTotal = String(apiList.total);
  console.log('API total =', apiTotal);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load notifications ---');
  await page.goto(ORIGIN + '/notifications/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.n-row', { timeout: 8000 });
  await page.waitForTimeout(700);

  // stats
  const unread = (await page.locator('#sUnread').textContent()).trim();
  const imp = (await page.locator('#sImp').textContent()).trim();
  const today = (await page.locator('#sToday').textContent()).trim();
  const totalN = (await page.locator('#sTotal').textContent()).trim();
  if (unread !== '1') bail('unread expected 1, got ' + unread);
  if (Number(imp) < 1) bail('important expected >=1, got ' + imp);
  if (Number(today) < 1) bail('today expected >=1, got ' + today);
  if (Number(totalN) !== Number(apiTotal)) bail('total expected ' + apiTotal + ', got ' + totalN);
  console.log('stats OK:', { unread, imp, today, totalN });

  // seeded row specifics
  const row1 = page.locator('.n-row', { hasText: '验证通知·行程临近' });
  if (!(await row1.count())) bail('seeded notification 1 not rendered');
  const cls1 = await row1.getAttribute('class');
  if (!cls1.includes('unread')) bail('notif 1 should be .unread');
  if (!cls1.includes('important')) bail('notif 1 should be .important');
  if (!(await row1.locator('.badge-imp').count())) bail('notif 1 missing 重要 badge');
  if (!(await row1.locator('.badge-unread').count())) bail('notif 1 missing unread dot');
  console.log('notif1 OK: unread + important + badges');

  // trip context chip resolves trip name
  const chip = (await row1.locator('.n-ctx .chip').first().textContent()).trim();
  if (!chip.includes('赤峰之旅')) bail('trip chip should resolve 赤峰之旅, got ' + chip);

  // link rewrite: /trips/{id}/packing -> /packing/?trip={id}
  const goHref = await row1.locator('.n-go').getAttribute('href');
  if (goHref !== '/packing/?trip=' + TRIP) bail('link rewrite wrong: ' + goHref);
  const row2 = page.locator('.n-row', { hasText: '验证通知·系统消息' });
  const goHref2 = await row2.locator('.n-go').getAttribute('href');
  if (goHref2 !== '/expenses/?trip=' + TRIP) bail('link rewrite 2 wrong: ' + goHref2);
  console.log('link rewrite OK:', goHref, '|', goHref2);

  // grouping: notif1 today, notif2 yesterday
  const groupTxt = await page.locator('.group .gt').allTextContents();
  if (!groupTxt.some(t => t.includes('今天'))) bail('missing 今天 group: ' + groupTxt.join('|'));
  if (!groupTxt.some(t => t.includes('昨天'))) bail('missing 昨天 group: ' + groupTxt.join('|'));
  console.log('grouping OK:', groupTxt.map(s => s.replace(/\s+/g, '')).join(' / '));

  // type filter chips show counts
  const typeRow = page.locator('.fitem[data-t="trip_upcoming"]');
  const typeTxt = (await typeRow.textContent()).trim();
  if (!typeTxt.includes('1')) bail('trip_upcoming filter count expected 1, got ' + typeTxt);
  console.log('type filter counts OK');

  await page.screenshot({ path: path.join(SHOTS, 'feed.png'), fullPage: true });

  // ---- status filter: unread ----
  await page.click('.fitem[data-s="unread"]');
  await page.waitForTimeout(400);
  const unreadRows = await page.locator('.n-row').count();
  if (unreadRows !== 1) bail('unread filter expected 1 row, got ' + unreadRows);
  console.log('status filter OK (unread -> 1)');

  // ---- type filter: trip_upcoming ----
  await page.click('.fitem[data-s="all"]');
  await page.waitForTimeout(300);
  await page.click('.fitem[data-t="trip_upcoming"]');
  await page.waitForTimeout(400);
  const typeRows = await page.locator('.n-row').count();
  if (typeRows !== 1) bail('type filter expected 1 row, got ' + typeRows);
  await page.click('.fitem[data-t=""]');
  await page.waitForTimeout(300);
  console.log('type filter OK (trip_upcoming -> 1)');

  // ---- mark single read ----
  console.log('--- mark read ---');
  const r1 = page.locator('.n-row', { hasText: '验证通知·行程临近' });
  await r1.hover();
  await r1.locator('.n-ops .rd').click();
  await page.waitForTimeout(900);
  const unread2 = (await page.locator('#sUnread').textContent()).trim();
  if (unread2 !== '0') bail('unread should be 0 after marking read, got ' + unread2);
  const cls1b = await page.locator('.n-row', { hasText: '验证通知·行程临近' }).getAttribute('class');
  if (cls1b.includes('unread')) bail('row should no longer be .unread');
  console.log('mark read OK (unread 1 -> 0)');

  // ---- mark all read ----
  await page.click('#btnReadAll');
  await page.waitForTimeout(900);
  const anyUnread = await page.locator('.n-row.unread').count();
  if (anyUnread !== 0) bail('after read-all there should be no unread rows, got ' + anyUnread);
  const dbUnread = psql("SELECT count(*) FROM notifications WHERE user_id = '" + ADMIN + "' AND is_read = false;");
  if (dbUnread !== '0') bail('DB unread should be 0, got ' + dbUnread);
  console.log('read-all OK (DB unread = 0)');

  // clear-read button exists but NOT clicked (would wipe real data)
  if (!(await page.locator('#btnClearRead').count())) bail('clear-read button missing');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  // ---- cleanup: remove only the 2 seeded rows ----
  console.log('--- cleanup ---');
  console.log('deleted rows =', psql("DELETE FROM notifications WHERE id IN ('verify-notif-1','verify-notif-2'); SELECT count(*) FROM notifications WHERE user_id = '" + ADMIN + "';").split('\n').pop());
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
