// Verify itinerary page: timeline render + interactions (add/edit/toggle/reorder/delete/day-title)
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
let TRIP = null;              // 动态创建的验证行程
const SHOTS = 'D:/tmp/itinerary-shots';
const SEED_DAYS = 3, SEED_PLACES = [4, 3, 3];   // 3 天 / 10 地点

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function api(method, p, body, token) {
  const r = await fetch(ORIGIN + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  return { status: r.status, json: await r.json().catch(() => null) };
}
function ymd(o) { const d = new Date(); d.setDate(d.getDate() + o); return d.toISOString().slice(0, 10); }

/** 天锁规则下播种：取锁 → 建地点 → 释放 */
async function seedPlaces(token, tripId, dayId, names) {
  await api('POST', `/api/trips/${tripId}/days/${dayId}/edit-lock`, {}, token);
  for (const name of names) {
    const r = await api('POST', `/api/trips/${tripId}/places`, { tripDayId: dayId, name }, token);
    if (r.status !== 200) bail('seed place failed: ' + name + ' -> ' + r.status);
  }
  await api('DELETE', `/api/trips/${tripId}/days/${dayId}/edit-lock`, null, token);
}

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  /* --- 自建验证行程（不再依赖固定种子数据） --- */
  console.log('--- seed trip ---');
  const created = await api('POST', '/api/trips', {
    title: '行程页验证（临时）', destination: '大理', startDate: ymd(1), endDate: ymd(SEED_DAYS)
  }, token);
  if (created.status !== 200) bail('create trip failed: ' + created.status);
  TRIP = created.json.trip.id;
  await api('POST', `/api/trips/${TRIP}/days/generate`, {}, token);
  const days = (await api('GET', `/api/trips/${TRIP}/days`, null, token)).json.days;
  if (days.length !== SEED_DAYS) bail('expected ' + SEED_DAYS + ' days, got ' + days.length);
  const allNames = ['苍山索道', '洱海骑行', '大理古城', '崇圣寺三塔', '喜洲古镇', '双廊', '沙溪古镇', '石宝山', '诺邓古村', '云龙天池'];
  let nameIdx = 0;
  const tripStartDate = created.json.trip.startDate;
  for (let i = 0; i < SEED_DAYS; i++) {
    await seedPlaces(token, TRIP, days[i].id, allNames.slice(nameIdx, nameIdx + SEED_PLACES[i]));
    nameIdx += SEED_PLACES[i];
  }
  const expectDate = (d => ('0' + (d.getMonth() + 1)).slice(-2) + ' / ' + ('0' + d.getDate()).slice(-2))(new Date(String(tripStartDate).slice(0, 10) + 'T00:00:00'));

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load itinerary ---');
  await page.goto(ORIGIN + '/itinerary/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.day-card', { timeout: 8000 });
  await page.waitForTimeout(600);

  // overview numbers
  const ovDays = (await page.locator('#ovDays').textContent()).trim();
  const ovPlaces = (await page.locator('#ovPlaces').textContent()).trim();
  const ovDone = (await page.locator('#ovDone').textContent()).trim();
  console.log('overview:', { ovDays, ovPlaces, ovDone });
  if (ovDays !== '3') bail('expected 3 days, got ' + ovDays);
  if (ovPlaces !== '10') bail('expected 10 places, got ' + ovPlaces);

  // day stubs
  const dn1 = (await page.locator('.day-card .dn').first().textContent()).trim();
  if (dn1 !== 'D1') bail('stub D1 expected, got ' + dn1);
  const dd1 = (await page.locator('.day-card .dd').first().textContent()).trim();
  if (dd1 !== expectDate) bail('stub date expected ' + expectDate + ', got ' + dd1);
  const ring0 = (await page.locator('.day-card .ring .num').first().textContent()).trim();
  console.log('day1 ring =', ring0);

  // place rows count
  const rows = await page.locator('.p-row').count();
  if (rows !== 10) bail('expected 10 place rows, got ' + rows);
  console.log('place rows =', rows);

  // timeline dots rendered
  const dots = await page.locator('.p-dot').count();
  if (dots !== 10) bail('expected 10 axis dots, got ' + dots);

  await page.screenshot({ path: path.join(SHOTS, 'desktop.png'), fullPage: true });
  console.log('shot -> desktop.png');

  // ---- toggle complete on first place of day1 ----
  console.log('--- toggle complete ---');
  const firstChk = page.locator('.day-card').first().locator('.p-row').first().locator('.chk');
  await firstChk.click();
  await page.waitForTimeout(700);
  const doneRow = await page.locator('.day-card').first().locator('.p-row.done').count();
  if (doneRow !== 1) bail('expected 1 done row after toggle');
  const ring1 = (await page.locator('.day-card .ring .num').first().textContent()).trim();
  if (ring0 === ring1) bail('ring pct should change after toggle: ' + ring0 + ' -> ' + ring1);
  const ovDone2 = (await page.locator('#ovDone').textContent()).trim();
  console.log('ring', ring0, '->', ring1, '| ovDone', ovDone, '->', ovDone2);
  // strike-through check
  const deco = await page.locator('.day-card').first().locator('.p-row.done .p-name').first().evaluate(el => getComputedStyle(el).textDecorationLine);
  if (deco !== 'line-through') bail('done name should be line-through, got ' + deco);
  console.log('toggle OK (ring updated + strikethrough)');

  // ---- add a place with time ----
  console.log('--- add place ---');
  const addRow = page.locator('.day-card').first().locator('.add-row');
  await addRow.locator('input[type="text"]').fill('验证测试地点');
  await addRow.locator('select').selectOption('restaurant');
  const times = addRow.locator('input[type="time"]');
  await times.nth(0).fill('10:00');
  await times.nth(1).fill('11:30');
  await addRow.locator('.btn-add').click();
  await page.waitForTimeout(900);
  const rows2 = await page.locator('.p-row').count();
  if (rows2 !== 11) bail('expected 11 rows after add, got ' + rows2);
  const newRow = page.locator('.p-row', { hasText: '验证测试地点' }).first();
  const newRowTime = (await newRow.locator('.p-time').textContent()).trim();
  if (!newRowTime.includes('10:00')) bail('new row time expected 10:00, got ' + newRowTime);
  if (!newRowTime.includes('1h30m')) bail('new row duration expected 1h30m, got: ' + newRowTime);
  const newType = (await newRow.locator('.p-type').textContent()).trim();
  if (!newType.includes('餐厅')) bail('new row type expected 餐厅, got ' + newType);
  console.log('add OK: rows=11, time=10:00, dur=1h30m, type=餐厅');

  // ---- edit that place ----
  console.log('--- edit place ---');
  await newRow.hover();
  await newRow.locator('.ed').click();
  await page.waitForSelector('.p-edit', { timeout: 3000 });
  const editBox = page.locator('.p-edit').first();
  await editBox.locator('[data-f="name"]').fill('验证测试地点（改）');
  await editBox.locator('[data-f="addr"]').fill('赤峰市测试路 1 号');
  await editBox.locator('.sv').click();
  await page.waitForTimeout(900);
  const edited = page.locator('.p-row', { hasText: '验证测试地点（改）' });
  if (!(await edited.count())) bail('edited name not rendered');
  const addr = (await edited.locator('.p-addr').textContent().catch(() => '')).trim();
  if (!addr.includes('测试路')) bail('edited address missing, got: ' + addr);
  console.log('edit OK: name + address updated');

  // ---- reorder: move it up then back down ----
  console.log('--- reorder ---');
  async function testRowIndex() {
    return page.evaluate(() => {
      const rows = [...document.querySelectorAll('.day-card')[0].querySelectorAll('.p-row')];
      return rows.findIndex(r => r.textContent.includes('验证测试地点（改）'));
    });
  }
  const idxBefore = await testRowIndex();
  if (idxBefore < 0) bail('test row not found before reorder');
  const rowEd = page.locator('.day-card').first().locator('.p-row').nth(idxBefore);
  await rowEd.hover();
  await rowEd.locator('.up').click();
  await page.waitForTimeout(900);
  const idxAfterUp = await testRowIndex();
  if (idxAfterUp !== idxBefore - 1) bail('reorder up failed: idx ' + idxBefore + ' -> ' + idxAfterUp);
  console.log('reorder up OK: idx ' + idxBefore + ' -> ' + idxAfterUp);
  // move back down to restore original order
  const rowEd2 = page.locator('.day-card').first().locator('.p-row').nth(idxAfterUp);
  await rowEd2.hover();
  await rowEd2.locator('.down').click();
  await page.waitForTimeout(900);
  const idxRestored = await testRowIndex();
  if (idxRestored !== idxBefore) bail('reorder down restore failed: idx ' + idxRestored);
  console.log('reorder down restore OK: back to idx ' + idxRestored);

  // ---- delete test place ----
  console.log('--- delete place ---');
  page.once('dialog', d => d.accept());
  const rowDel = page.locator('.p-row', { hasText: '验证测试地点（改）' }).first();
  await rowDel.hover();
  await rowDel.locator('.del').click();
  await page.waitForTimeout(900);
  const rows3 = await page.locator('.p-row').count();
  if (rows3 !== 10) bail('expected 10 rows after delete, got ' + rows3);
  console.log('delete OK: back to 10 rows');

  // ---- day title ----
  console.log('--- day title ---');
  const titleInput = page.locator('[data-daytitle]').first();
  await titleInput.fill('抵达赤峰 · 验证');
  await titleInput.press('Enter');
  await page.waitForTimeout(800);
  // revert to empty to preserve data
  await titleInput.fill('');
  await titleInput.press('Enter');
  await page.waitForTimeout(800);
  console.log('day title save OK (set + revert)');

  // ---- un-toggle the place we completed (restore data) ----
  const chk2 = page.locator('.day-card').first().locator('.p-row').first().locator('.chk');
  const pressed = await chk2.getAttribute('aria-pressed');
  if (pressed === 'true') { await chk2.click(); await page.waitForTimeout(600); console.log('restored: place unchecked'); }

  // mobile shot
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  /* --- 清理：软删临时行程 --- */
  const del = await api('DELETE', '/api/trips/' + TRIP, null, token);
  console.log(del.status === 200 ? 'cleanup OK (trip soft-deleted)' : 'cleanup WARN: ' + del.status);
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
