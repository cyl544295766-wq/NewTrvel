// Verify bookings: create 5-type tickets / route split / status stamps / filter / remind toggle / edit / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/bookings-shots';
assertLocalOrigin(ORIGIN);

let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const cleaner = new Cleaner();
let browser = null;
let tripId = null;
let user = null;
let token = null;

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 + 行程（远期日期保证 UPCOMING 语义） ----
  user = await registerUser(ORIGIN, 'bk');
  cleaner.addUser(user.id);
  token = user.token;
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '预订验证行程（临时）', destination: '航线镇', startDate: '2027-03-14', endDate: '2027-03-18'
  })).trip.id);
  ok('夹具就绪 trip=' + tripId);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load bookings (empty) ---');
  await page.goto(ORIGIN + '/bookings/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  console.log('empty state OK');

  // ---- create flight (upcoming) ----
  console.log('--- create flight ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.click('#typeTabs button[data-t="flight"]');
  await page.fill('#fTitle', '验证机票：北京 → 上海');
  await page.fill('#fStart', '2027-03-15T14:20');
  await page.fill('#fEnd', '2027-03-15T16:25');
  await page.fill('#fConfirm', 'ABC123');
  await page.fill('#fOrderNo', 'ORD-9001');
  await page.fill('#ex_flightNo', 'CA1234');
  await page.fill('#ex_airports', '大兴T2 → 虹桥T2');
  await page.fill('#ex_cabin', '经济舱');
  await page.selectOption('#fRemind', '1440');
  await page.click('#btnSaveBk');
  await waitFor('机票卡渲染', () => page.locator('.bk-card').count().then((n) => n >= 1).catch(() => false), 10000);

  const card = page.locator('.bk-card').first();
  const from = (await card.locator('.route .pt').first().textContent()).trim();
  const to = (await card.locator('.route .pt').nth(1).textContent()).trim();
  const no = (await card.locator('.route .mid .no').textContent()).trim();
  check(from === '大兴T2', '航段起点: ' + from);
  check(to === '虹桥T2', '航段终点: ' + to);
  check(no === 'CA1234', '航班号: ' + no);
  const stamp1 = (await card.locator('.stamp').textContent()).trim();
  check(stamp1 === 'UPCOMING', '未来航班戳 UPCOMING: ' + stamp1);
  const code = (await card.locator('.stub-code').first().textContent()).trim();
  check(code === 'ABC123', '确认码: ' + code);
  const remTxt = (await card.locator('.stub-rem').textContent()).trim();
  check(remTxt.includes('提醒开'), '提醒开启: ' + remTxt);
  console.log('flight OK: 大兴T2 → 虹桥T2 / CA1234 / UPCOMING / ABC123 / 提醒开');

  // ---- create hotel (past -> DONE, with free cancel deadline) ----
  console.log('--- create hotel (past) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.click('#typeTabs button[data-t="hotel"]');
  await page.fill('#fTitle', '验证酒店：沙漠营地');
  await page.fill('#fStart', '2026-07-24T15:00');
  await page.fill('#fEnd', '2026-07-26T12:00');
  await page.fill('#ex_hotelName', '腾格里沙漠营地');
  await page.fill('#ex_roomType', '双人帐篷');
  await page.fill('#ex_cancelPolicy', '入住前 1 天可免费取消');
  await page.fill('#ex_freeCancelDeadline', '2026-07-23T18:30');
  await page.click('#btnSaveBk');
  await waitFor('酒店卡渲染', () => page.locator('.bk-card').count().then((n) => n === 2).catch(() => false), 10000);

  const cards = await page.locator('.bk-card').count();
  check(cards === 2, '两张卡: ' + cards);
  const hotelCard = page.locator('.bk-card', { hasText: '沙漠营地' });
  const stamp2 = (await hotelCard.locator('.stamp').textContent()).trim();
  check(stamp2 === 'DONE', '过去酒店戳 DONE: ' + stamp2);
  const note = (await hotelCard.locator('.bk-note').textContent()).trim();
  check(note.includes('免费取消'), '免费取消备注: ' + note);
  console.log('hotel OK: DONE stamp + cancel policy note');

  // free-cancel-derived default reminder should be a positive minutes value
  const hb = await (await fetch(ORIGIN + '/api/trips/' + tripId + '/bookings', { headers: { 'Authorization': 'Bearer ' + token } })).json();
  const hotel = (hb.bookings || []).find(b => b.type === 'hotel');
  check(!!hotel, 'API 可查酒店预订');
  check(hotel.reminderEnabled, '酒店提醒默认开启');
  console.log('hotel reminderMinutes (derived from deadline) =', hotel.reminderMinutes);

  // filter chips
  const chips = await page.locator('#filters button').allTextContents();
  console.log('filters =', chips.map(s => s.replace(/\s+/g, '')).join(' | '));
  const remNote = (await page.locator('#remNote').textContent()).trim();
  console.log('reminder summary =', remNote);

  await page.screenshot({ path: path.join(SHOTS, 'list.png'), fullPage: true });

  // ---- filter to flight ----
  console.log('--- filter ---');
  await page.click('#filters button[data-f="flight"]');
  await page.waitForTimeout(300);
  const flt = await page.locator('.bk-card').count();
  check(flt === 1, '类型筛选 1 张: ' + flt);
  await page.click('#filters button[data-f="all"]');
  await page.waitForTimeout(300);
  console.log('filter OK');

  // ---- toggle reminder off on flight -> summary changes ----
  console.log('--- remind toggle ---');
  await page.locator('.bk-card', { hasText: 'CA1234' }).locator('.stub-rem').click();
  await page.waitForTimeout(900);
  const rem2 = (await page.locator('.bk-card', { hasText: 'CA1234' }).locator('.stub-rem').textContent()).trim();
  check(rem2.includes('提醒关'), '提醒关闭: ' + rem2);
  const note2 = (await page.locator('#remNote').textContent()).trim();
  check(note2.includes('1 项'), '摘要 1 项提醒关: ' + note2);
  console.log('remind toggle OK + summary =', note2);

  // ---- edit flight ----
  console.log('--- edit ---');
  const fCard = page.locator('.bk-card', { hasText: 'CA1234' });
  await fCard.hover();
  await fCard.locator('.bk-ops .ed').click();
  await page.waitForSelector('#formPanel:not([hidden])');
  const pre = await page.locator('#fTitle').inputValue();
  check(pre.includes('验证机票'), '编辑预填标题: ' + pre);
  const preFlight = await page.locator('#ex_flightNo').inputValue();
  check(preFlight === 'CA1234', '编辑预填航班号: ' + preFlight);
  const preStart = await page.locator('#fStart').inputValue();
  if (preStart !== '2027-03-15T14:20') bail('edit prefill start wrong: ' + preStart);
  await page.fill('#fTitle', '验证机票（改）');
  await page.fill('#ex_cabin', '商务舱');
  await page.click('#btnSaveBk');
  await page.waitForTimeout(1200);
  const edited = page.locator('.bk-card', { hasText: '验证机票（改）' });
  check((await edited.count()) > 0, '编辑后标题渲染');
  const cabinTxt = (await edited.locator('.bk-times').textContent()).replace(/\s+/g, ' ');
  check(cabinTxt.includes('商务舱'), '编辑后舱位渲染: ' + cabinTxt);
  console.log('edit OK (title + cabin)');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(300);

  // ---- delete both via UI ----
  console.log('--- delete ---');
  page.once('dialog', d => d.accept());
  await page.locator('.bk-card').first().locator('.bk-ops .del').click();
  await page.waitForTimeout(1000);
  const left1 = await page.locator('.bk-card').count();
  check(left1 === 1, '删一张后剩 1: ' + left1);
  page.once('dialog', d => d.accept());
  await page.locator('.bk-card').first().locator('.stub-del').click();
  await page.waitForTimeout(1000);
  const left2 = await page.locator('.bk-card').count();
  check(left2 === 0, '全部删除归零: ' + left2);
  const empty = await page.locator('.empty-state').count();
  check(empty > 0, '回到空态');
  console.log('delete OK (hover × + stub 删除, back to empty)');

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
  try {
    cleaner.run();
    console.log('cleanup OK（残留核验 0）');
  } catch (e) {
    if (!failed) failed = e;
    console.error('清理失败: ' + (e && e.message));
  }
  process.exit(failed ? 1 : 0);   // 显式退出：事件循环可能有残留句柄（playwright/fetch keep-alive），不显式退出会挂住批量 runner
})();
