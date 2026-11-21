// Verify vouchers: timeline / QR / onsite info per type / banner countdown / copy code / empty state
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, writePngFile } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/vouchers-shots';
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
function daysFromNow(n) {
  const d = new Date(); d.setDate(d.getDate() + n);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 + 行程 + 4 张不同类型凭证（booking 承载） ----
  user = await registerUser(ORIGIN, 'vc');
  cleaner.addUser(user.id);
  token = user.token;
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '凭证验证行程（临时）', destination: '驿站', startDate: daysFromNow(4), endDate: daysFromNow(9)
  })).trip.id);
  writePngFile('D:/tmp/vc-qr.png', 240, 240, [200, 120, 40]);
  const tinyPng = 'data:image/png;base64,' + fs.readFileSync('D:/tmp/vc-qr.png').toString('base64');
  const seeded = [];

  console.log('--- seed vouchers ---');
  const specs = [
    { type: 'flight', title: '验证凭证·机票', startTime: daysFromNow(5) + ' 14:20:00', endTime: daysFromNow(5) + ' 16:25:00',
      confirmCode: 'FLY-888', orderNo: 'ORD-1001', attachmentUrl: tinyPng,
      extra: { flightNo: 'CA1234', airports: '大兴T2 → 虹桥T2', cabin: '经济舱' } },
    { type: 'train', title: '验证凭证·火车', startTime: daysFromNow(6) + ' 08:00:00', endTime: daysFromNow(6) + ' 09:30:00',
      confirmCode: 'TRN-777',
      extra: { trainNo: 'G101', stations: '北京南 → 上海虹桥', seat: '05车12F' } },
    { type: 'hotel', title: '验证凭证·酒店', startTime: daysFromNow(6) + ' 15:00:00', endTime: daysFromNow(8) + ' 12:00:00',
      orderNo: 'ORD-1002',
      extra: { hotelName: '腾格里沙漠营地', roomType: '双人帐篷', cancelPolicy: '入住前 1 天可免费取消',
               freeCancelDeadline: daysFromNow(5) + ' 18:30:00' } },
    { type: 'ticket', title: '验证凭证·门票', startTime: daysFromNow(7) + ' 10:30:00', endTime: daysFromNow(7) + ' 12:00:00',
      confirmCode: 'TKT-666',
      extra: { itemName: '沙坡头门票', session: '上午场' } }
  ];
  for (const s of specs) {
    const j = await apiU.post('/api/trips/' + tripId + '/bookings', s);
    if (!j.booking) bail('seed failed for ' + s.type + ': ' + JSON.stringify(j));
    cleaner.addBooking(j.booking.id);
    seeded.push(j.booking.id);
  }
  console.log('seeded', seeded.length, 'vouchers');

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 }, locale: 'zh-CN',
    permissions: ['clipboard-read', 'clipboard-write']
  });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load vouchers ---');
  await page.goto(ORIGIN + '/vouchers/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.v-ticket', { timeout: 8000 });
  await page.waitForTimeout(600);

  const tickets = await page.locator('.v-ticket').count();
  check(tickets === 4, '4 张凭证: ' + tickets);
  const rows = await page.locator('.v-row').count();
  check(rows === 4, '时间轴 4 行: ' + rows);

  // QR：带 attachmentUrl 的凭证显示官方原图（.qr-orig img），其余为生成 svg（内容 = 祝福语「旅途愉快」）
  const qrs = await page.locator('.v-stub .qr svg').count();
  const qrImgs = await page.locator('.v-stub .qr.qr-orig img').count();
  check(qrs === 3 && qrImgs === 1, '3 个生成二维码 + 1 个官方原图（实为 ' + qrs + '/' + qrImgs + '）');
  const qrRects = await page.locator('.v-stub .qr svg').first().locator('rect').count();
  check(qrRects >= 100, '二维码矩形足够多: ' + qrRects);
  // 现行为：二维码 payload = 凭证标题（aria-label 可见）→ 不同凭证图案应不同
  const svg0 = await page.locator('.v-stub .qr svg').first().evaluate(el => el.outerHTML);
  const svg1 = await page.locator('.v-stub .qr svg').nth(1).evaluate(el => el.outerHTML);
  check(svg0 !== svg1, '不同标题的二维码图案不同');
  const aria0 = await page.locator('.v-stub .qr svg').first().getAttribute('aria-label');
  const aria1 = await page.locator('.v-stub .qr svg').nth(1).getAttribute('aria-label');
  check(/火车/.test(aria0), '火车凭证 QR aria: ' + aria0);
  check(/酒店/.test(aria1), '酒店凭证 QR aria: ' + aria1);
  check(/门票/.test(await page.locator('.v-stub .qr svg').nth(2).getAttribute('aria-label')), '门票凭证 QR aria 正确');
  console.log('timeline OK: 4 rows + 4 QR (first has', qrRects, 'rects) — 真编码，aria-label =', aria0);
  /* 截下第一张凭证的二维码，供 python 解码器验证可扫性 */
  await page.locator('.v-stub .qr').first().screenshot({ path: 'D:/tmp/vouchers-shots/qr-first.png', timeout: 60000 });

  // flight onsite info
  const flightCard = page.locator('.v-row', { hasText: '验证凭证·机票' });
  const fTxt = (await flightCard.locator('.onsite').textContent()).replace(/\s+/g, ' ');
  if (!fTxt.includes('CA1234')) bail('flight onsite missing flight no: ' + fTxt);
  if (!fTxt.includes('大兴T2 → 虹桥T2')) bail('flight onsite missing route: ' + fTxt);
  if (!fTxt.includes('经济舱')) bail('flight onsite missing cabin');
  if (!fTxt.includes('FLY-888')) bail('flight onsite missing confirm code');
  if (!fTxt.includes('ORD-1001')) bail('flight onsite missing order no');
  const codeCls = await flightCard.locator('.os .v.code').first().textContent();
  if (codeCls.trim() !== 'FLY-888') bail('confirm code should render in .code style, got ' + codeCls);
  console.log('flight onsite OK (航班/航线/舱位/确认码/订单号)');

  // train onsite
  const trainTxt = (await page.locator('.v-row', { hasText: '验证凭证·火车' }).locator('.onsite').textContent()).replace(/\s+/g, ' ');
  if (!trainTxt.includes('G101') || !trainTxt.includes('05车12F')) bail('train onsite wrong: ' + trainTxt);
  console.log('train onsite OK');

  // hotel: free cancel deadline warn + policy note
  const hotelCard = page.locator('.v-row', { hasText: '验证凭证·酒店' });
  const hotelTxt = (await hotelCard.locator('.onsite').textContent()).replace(/\s+/g, ' ');
  if (!hotelTxt.includes('CANCEL BY')) bail('hotel onsite missing cancel deadline: ' + hotelTxt);
  const warnCount = await hotelCard.locator('.os .v.warn').count();
  if (warnCount !== 1) bail('hotel cancel deadline should use .warn style');
  const note = (await hotelCard.locator('.v-note').textContent()).trim();
  if (!note.includes('免费取消')) bail('hotel policy note missing');
  console.log('hotel onsite OK (免费取消截止 warn + 政策条)');

  // ticket onsite
  const ticketTxt = (await page.locator('.v-row', { hasText: '验证凭证·门票' }).locator('.onsite').textContent()).replace(/\s+/g, ' ');
  if (!ticketTxt.includes('沙坡头门票') || !ticketTxt.includes('上午场')) bail('ticket onsite wrong: ' + ticketTxt);
  console.log('ticket onsite OK');

  // overview + banner
  const ovCount = (await page.locator('#ovCount').textContent()).trim();
  const ovDays = (await page.locator('#ovDays').textContent()).trim();
  const ovAttach = (await page.locator('#ovAttach').textContent()).trim();
  if (ovCount !== '4') bail('ovCount expected 4, got ' + ovCount);
  if (!/5 天/.test(ovDays)) bail('ovDays expected 5 天, got ' + ovDays);
  if (ovAttach !== '1') bail('ovAttach expected 1, got ' + ovAttach);
  const bannerTxt = (await page.locator('.banner .tx').textContent()).replace(/\s+/g, ' ');
  if (!bannerTxt.includes('5 天')) bail('banner should mention 5 天, got: ' + bannerTxt);
  console.log('overview+banner OK:', { ovCount, ovDays, ovAttach }, '|', bannerTxt.slice(0, 40));

  // attachment button enabled only for flight
  const viewBtns = page.locator('.stub-actions .view');
  const flightViewDisabled = await page.locator('.v-row', { hasText: '验证凭证·机票' }).locator('.view').isDisabled();
  if (flightViewDisabled) bail('flight view button should be enabled (has attachment)');
  const trainViewDisabled = await page.locator('.v-row', { hasText: '验证凭证·火车' }).locator('.view').isDisabled();
  if (!trainViewDisabled) bail('train view button should be disabled (no attachment)');
  console.log('attachment button state OK');

  await page.screenshot({ path: path.join(SHOTS, 'list.png'), fullPage: true });

  // ---- copy confirm code ----
  console.log('--- copy code ---');
  await page.locator('.v-row', { hasText: '验证凭证·机票' }).locator('.copy').click();
  await page.waitForTimeout(600);
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  if (clip !== 'FLY-888') bail('clipboard expected FLY-888, got ' + clip);
  const toastTxt = (await page.locator('#toast').textContent()).trim();
  if (!toastTxt.includes('FLY-888')) bail('toast should confirm copy, got ' + toastTxt);
  console.log('copy OK: clipboard =', clip);

  // ---- share ----
  await page.click('#btnShare');
  await page.waitForTimeout(500);
  const shared = await page.evaluate(() => navigator.clipboard.readText());
  if (!shared.includes('/vouchers/')) bail('share should copy page url, got ' + shared);
  console.log('share OK');

  // ---- print button do not actually print (stub window.print) ----
  const printed = await page.evaluate(() => {
    window.__printed = false;
    window.print = () => { window.__printed = true; };
    document.getElementById('btnPrint').click();
    return new Promise(r => setTimeout(() => r(window.__printed), 100));
  });
  if (!printed) bail('print button should call window.print');
  console.log('print trigger OK');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
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
  try {
    cleaner.run();
    console.log('cleanup OK（残留核验 0）');
  } catch (e) {
    if (!failed) failed = e;
    console.error('清理失败: ' + (e && e.message));
  }
  process.exit(failed ? 1 : 0);   // 显式退出：事件循环可能有残留句柄（playwright/fetch keep-alive），不显式退出会挂住批量 runner
})();
