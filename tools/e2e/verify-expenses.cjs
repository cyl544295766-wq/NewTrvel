// Verify expenses page: wallet + list + form + report + settlement (seeded data cleaned up at end)
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
assertLocalOrigin(ORIGIN);
const cleaner = new Cleaner();
let browser = null;
let TRIP = '';
let memberA = null, memberB = null;
const SHOTS = 'D:/tmp/expenses-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const user = await registerUser(ORIGIN, 'ex');
  cleaner.addUser(user.id);
  const token = user.token;
  fs.mkdirSync(SHOTS, { recursive: true });
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };

  // ---- 夹具：建行程 + 注册 2 成员 + 好友 + 邀请 ----
  console.log('--- 夹具：建行程 + 成员 ---');
  const tripRes = await fetch(ORIGIN + '/api/trips', {
    method: 'POST', headers: H,
    body: JSON.stringify({ title: '费用验证行程（临时）', destination: '费用镇', startDate: '2026-07-01', endDate: '2026-07-05', budget: '6000' })
  });
  const tripData = (await tripRes.json()).trip;
  TRIP = tripData.id;
  cleaner.addTrip(TRIP);

  // 注册 2 个成员
  memberA = await registerUser(ORIGIN, 'exA');
  cleaner.addUser(memberA.id);
  memberB = await registerUser(ORIGIN, 'exB');
  cleaner.addUser(memberB.id);

  // 互为好友（邀请前提）
  const apiOwner = api(ORIGIN, token, true);
  const apiA = api(ORIGIN, memberA.token, true);
  const apiB = api(ORIGIN, memberB.token, true);
  await apiOwner.post('/api/friends', { userId: memberA.id });
  await apiA.post('/api/friends/' + user.id + '/accept');
  await apiOwner.post('/api/friends', { userId: memberB.id });
  await apiB.post('/api/friends/' + user.id + '/accept');

  // 邀请成员
  await apiOwner.post('/api/trips/' + TRIP + '/members', { username: memberA.username, role: 'member' });
  await apiOwner.post('/api/trips/' + TRIP + '/members', { username: memberB.username, role: 'member' });

  const admin = user.id;
  const m02 = memberA.id;
  const v02 = memberB.id;
  const seedOne = async (body) => {
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/expenses', {
      method: 'POST', headers: H, body: JSON.stringify(body)
    });
    const j = await r.json();
    if (!r.ok) throw new Error('seed expense failed: ' + r.status + ' ' + JSON.stringify(j));
    return (j.expense || j).id;
  };
  const seededIds = [];
  seededIds.push(await seedOne({ amount: '320', category: 'food', title: '牧民家晚餐', payerUserId: admin, shareUserIds: [admin, m02, v02] }));
  seededIds.push(await seedOne({ amount: '200', category: 'hotel', title: '经棚镇住宿', payerUserId: m02, shares: [{ userId: m02, shareAmount: '200' }] }));
  seededIds.push(await seedOne({ amount: '60', category: 'ticket', title: '沙湖门票', payerUserId: admin, shares: [{ userId: admin, ratio: '2' }, { userId: m02, ratio: '1' }] }));
  console.log('seeded:', seededIds.join(', '));

  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load expenses ---');
  await page.goto(ORIGIN + '/expenses/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.exp-card', { timeout: 8000 });
  await page.waitForTimeout(500);

  // wallet
  const spent = (await page.locator('#wSpent').textContent()).trim();
  if (!spent.includes('580')) bail('wallet total expected 580, got: ' + spent);
  const pct = (await page.locator('#wPct').textContent()).trim();
  if (pct !== '10%') bail('pct expected 10% (580/6000), got ' + pct);
  const count = (await page.locator('#wCount').textContent()).trim();
  if (!count.startsWith('3')) bail('count expected 3, got ' + count);
  console.log('wallet OK:', spent.replace(/\s+/g, ' '), '| pct', pct, '| count', count.trim());

  // meter bar width
  const w = await page.locator('#meterSpent').evaluate(el => el.style.width);
  if (w !== '10%') bail('meter width expected 10%, got ' + w);

  // list cards
  const cards = await page.locator('.exp-card').count();
  if (cards !== 3) bail('expected 3 exp cards, got ' + cards);
  const foodCard = page.locator('.exp-card', { hasText: '牧民家晚餐' });
  const foodAmt = (await foodCard.locator('.exp-amt').textContent()).trim();
  if (!foodAmt.includes('320')) bail('food amount wrong: ' + foodAmt);
  const shares = await foodCard.locator('.exp-shares span').allTextContents();
  if (shares.length !== 3) bail('food shares expected 3 entries, got ' + shares.join('|'));
  console.log('list OK: 3 cards, food shares =', shares.join(' / '));

  await page.screenshot({ path: path.join(SHOTS, 'list.png'), fullPage: true });

  // ---- report tab ----
  console.log('--- report tab ---');
  await page.click('#tab-report');
  await page.waitForTimeout(400);
  const catRows = await page.locator('#rpCat .cat-row').count();
  if (catRows < 3) bail('report cat rows expected >=3, got ' + catRows);
  const svg = await page.locator('#rpCat svg circle').count();
  if (svg < 3) bail('donut segs expected >=3, got ' + svg);
  const dayBars = await page.locator('#rpDay > div > div').count();
  console.log('report OK: cat rows', catRows, '| donut segs', svg, '| day bars', dayBars);
  await page.screenshot({ path: path.join(SHOTS, 'report.png'), fullPage: true });

  // ---- settlement tab ----
  console.log('--- settlement tab ---');
  await page.click('#tab-stl');
  await page.waitForTimeout(400);
  const netRows = await page.locator('#stlNets .net-row').count();
  if (netRows < 3) bail('net rows expected >=3, got ' + netRows);
  const transfers = await page.locator('#stlTransfers .transfer-row').count();
  if (transfers < 1) bail('transfers expected >=1, got ' + transfers);
  const hint = (await page.locator('#stlHint').textContent()).trim();
  console.log('settlement OK: nets', netRows, '| transfers', transfers, '|', hint);
  await page.screenshot({ path: path.join(SHOTS, 'settlement.png'), fullPage: true });

  // ---- form: add expense (equal, 2 members) ----
  console.log('--- form add ---');
  await page.click('#tab-list');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.fill('#fAmount', '90');
  await page.fill('#fTitle', '验证打车费');
  await page.click('#catChips button[data-cat="transport"]');
  // equal mode default; keep only Admin + member02 checked
  const mp = page.locator('#memberPick .mp');
  const mpCount = await mp.count();
  for (let i = 0; i < mpCount; i++) {
    const el = mp.nth(i);
    const txt = await el.textContent();
    const keep = txt.includes(user.username) || txt.includes(memberA.username);
    const isOn = (await el.getAttribute('class')).includes('on');
    if (keep && !isOn) await el.click();
    if (!keep && isOn) await el.click();
  }
  await page.click('#btnSaveExp');
  await page.waitForTimeout(1200);
  const cards2 = await page.locator('.exp-card').count();
  if (cards2 !== 4) bail('expected 4 cards after add, got ' + cards2);
  const newCard = page.locator('.exp-card', { hasText: '验证打车费' });
  const newShares = await newCard.locator('.exp-shares span').allTextContents();
  if (newShares.length !== 2) bail('equal split expected 2 shares, got ' + newShares.join('|'));
  if (!newShares[0].includes('45')) bail('equal 90/2 expected 45, got ' + newShares.join('|'));
  console.log('add OK: 4 cards, equal 90 -> ', newShares.join(' / '));
  await page.screenshot({ path: path.join(SHOTS, 'form-added.png'), fullPage: false });

  // ---- edit that expense ----
  console.log('--- edit ---');
  await newCard.hover();
  await newCard.locator('.ed').click();
  await page.waitForSelector('#formPanel:not([hidden])');
  const titleVal = await page.locator('#fTitle').inputValue();
  if (titleVal !== '验证打车费') bail('edit prefill title wrong: ' + titleVal);
  await page.fill('#fTitle', '验证打车费（改）');
  await page.click('#btnSaveExp');
  await page.waitForTimeout(1200);
  if (!(await page.locator('.exp-card', { hasText: '验证打车费（改）' }).count())) bail('edited title not rendered');
  console.log('edit OK');

  // ---- delete it ----
  console.log('--- delete ---');
  page.once('dialog', d => d.accept());
  const delCard = page.locator('.exp-card', { hasText: '验证打车费（改）' });
  await delCard.hover();
  await delCard.locator('.del').click();
  await page.waitForTimeout(1200);
  const cards3 = await page.locator('.exp-card').count();
  if (cards3 !== 3) bail('expected back to 3 cards, got ' + cards3);
  console.log('delete OK: back to 3');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  // ---- cleanup seeded ----
  console.log('--- cleanup seeded expenses ---');
  for (const id of seededIds) {
    const res = await fetch(ORIGIN + '/api/trips/' + TRIP + '/expenses/' + id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }, credentials: 'omit'
    });
    console.log('delete', id, res.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
