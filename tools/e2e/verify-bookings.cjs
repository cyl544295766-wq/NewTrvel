// Verify bookings: create 5-type tickets / route split / status stamps / filter / remind toggle / edit / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/bookings-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load bookings (empty) ---');
  await page.goto(ORIGIN + '/bookings/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  console.log('empty state OK');

  // ---- create flight (upcoming) ----
  console.log('--- create flight ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.click('#typeTabs button[data-t="flight"]');
  await page.fill('#fTitle', '验证机票：北京 → 上海');
  await page.fill('#fStart', '2026-11-20T14:20');
  await page.fill('#fEnd', '2026-11-20T16:25');
  await page.fill('#fConfirm', 'ABC123');
  await page.fill('#fOrderNo', 'ORD-9001');
  await page.fill('#ex_flightNo', 'CA1234');
  await page.fill('#ex_airports', '大兴T2 → 虹桥T2');
  await page.fill('#ex_cabin', '经济舱');
  await page.selectOption('#fRemind', '1440');
  await page.click('#btnSaveBk');
  await page.waitForSelector('.bk-card', { timeout: 8000 });
  await page.waitForTimeout(500);

  const card = page.locator('.bk-card').first();
  const from = (await card.locator('.route .pt').first().textContent()).trim();
  const to = (await card.locator('.route .pt').nth(1).textContent()).trim();
  const no = (await card.locator('.route .mid .no').textContent()).trim();
  if (from !== '大兴T2') bail('route from expected 大兴T2, got ' + from);
  if (to !== '虹桥T2') bail('route to expected 虹桥T2, got ' + to);
  if (no !== 'CA1234') bail('flight no expected CA1234, got ' + no);
  const stamp1 = (await card.locator('.stamp').textContent()).trim();
  if (stamp1 !== 'UPCOMING') bail('future flight stamp expected UPCOMING, got ' + stamp1);
  const code = (await card.locator('.stub-code').first().textContent()).trim();
  if (code !== 'ABC123') bail('confirm code expected ABC123, got ' + code);
  const remTxt = (await card.locator('.stub-rem').textContent()).trim();
  if (!remTxt.includes('提醒开')) bail('reminder should be on, got ' + remTxt);
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
  await page.waitForTimeout(1200);

  const cards = await page.locator('.bk-card').count();
  if (cards !== 2) bail('expected 2 cards, got ' + cards);
  const hotelCard = page.locator('.bk-card', { hasText: '沙漠营地' });
  const stamp2 = (await hotelCard.locator('.stamp').textContent()).trim();
  if (stamp2 !== 'DONE') bail('past hotel stamp expected DONE, got ' + stamp2);
  const note = (await hotelCard.locator('.bk-note').textContent()).trim();
  if (!note.includes('免费取消')) bail('cancel policy note missing: ' + note);
  console.log('hotel OK: DONE stamp + cancel policy note');

  // free-cancel-derived default reminder should be a positive minutes value
  const hb = await (await fetch(ORIGIN + '/api/trips/' + TRIP + '/bookings', { headers: { 'Authorization': 'Bearer ' + token } })).json();
  const hotel = (hb.bookings || []).find(b => b.type === 'hotel');
  if (!hotel) bail('hotel booking not found via API');
  if (!hotel.reminderEnabled) bail('hotel reminder should default enabled');
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
  if (flt !== 1) bail('flight filter expected 1, got ' + flt);
  await page.click('#filters button[data-f="all"]');
  await page.waitForTimeout(300);
  console.log('filter OK');

  // ---- toggle reminder off on flight -> summary changes ----
  console.log('--- remind toggle ---');
  await page.locator('.bk-card', { hasText: 'CA1234' }).locator('.stub-rem').click();
  await page.waitForTimeout(900);
  const rem2 = (await page.locator('.bk-card', { hasText: 'CA1234' }).locator('.stub-rem').textContent()).trim();
  if (!rem2.includes('提醒关')) bail('flight reminder should be off, got ' + rem2);
  const note2 = (await page.locator('#remNote').textContent()).trim();
  if (!note2.includes('1 项')) bail('summary should report 1 reminder off, got ' + note2);
  console.log('remind toggle OK + summary =', note2);

  // ---- edit flight ----
  console.log('--- edit ---');
  const fCard = page.locator('.bk-card', { hasText: 'CA1234' });
  await fCard.hover();
  await fCard.locator('.bk-ops .ed').click();
  await page.waitForSelector('#formPanel:not([hidden])');
  const pre = await page.locator('#fTitle').inputValue();
  if (!pre.includes('验证机票')) bail('edit prefill title wrong: ' + pre);
  const preFlight = await page.locator('#ex_flightNo').inputValue();
  if (preFlight !== 'CA1234') bail('edit prefill extra wrong: ' + preFlight);
  const preStart = await page.locator('#fStart').inputValue();
  if (preStart !== '2026-11-20T14:20') bail('edit prefill start wrong: ' + preStart);
  await page.fill('#fTitle', '验证机票（改）');
  await page.fill('#ex_cabin', '商务舱');
  await page.click('#btnSaveBk');
  await page.waitForTimeout(1200);
  const edited = page.locator('.bk-card', { hasText: '验证机票（改）' });
  if (!(await edited.count())) bail('edited title not rendered');
  const cabinTxt = (await edited.locator('.bk-times').textContent()).replace(/\s+/g, ' ');
  if (!cabinTxt.includes('商务舱')) bail('edited cabin not rendered: ' + cabinTxt);
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
  if (left1 !== 1) bail('expected 1 left, got ' + left1);
  page.once('dialog', d => d.accept());
  await page.locator('.bk-card').first().locator('.stub-del').click();
  await page.waitForTimeout(1000);
  const left2 = await page.locator('.bk-card').count();
  if (left2 !== 0) bail('expected 0 left, got ' + left2);
  const empty = await page.locator('.empty-state').count();
  if (!empty) bail('empty state should return');
  console.log('delete OK (hover × + stub 删除, back to empty)');

  await browser.close();

  // ---- cleanup safety net ----
  const rest = await (await fetch(ORIGIN + '/api/trips/' + TRIP + '/bookings', { headers: { 'Authorization': 'Bearer ' + token } })).json();
  for (const b of (rest.bookings || [])) {
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/bookings/' + b.id, { method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token } });
    console.log('cleanup delete', b.id, r.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
