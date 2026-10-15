// Verify stats: year pill / 4 cards / yearly bars / monthly bars / category donut / highlights / table drilldown
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/stats-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };

  // ---- seed a 2025 trip + one expense ----
  console.log('--- seed 2025 trip + expense ---');
  const tr = await fetch(ORIGIN + '/api/trips', {
    method: 'POST', headers: H,
    body: JSON.stringify({ title: '验证统计·2025 行程', destination: '验证目的地', startDate: '2025-05-10', endDate: '2025-05-14' })
  });
  const trip = (await tr.json()).trip;
  if (!trip) bail('seed trip failed');
  const meId = user.id;
  const er = await fetch(ORIGIN + '/api/trips/' + trip.id + '/expenses', {
    method: 'POST', headers: H,
    body: JSON.stringify({ amount: '1500', category: 'food', title: '验证统计餐饮', payerUserId: meId, shareUserIds: [meId] })
  });
  if (!er.ok) bail('seed expense failed: ' + er.status);
  console.log('seeded trip', trip.id, '+ expense 1500');

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load stats ---');
  await page.goto(ORIGIN + '/stats/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#yearPill button', { timeout: 8000 });
  await page.waitForTimeout(900);

  // year pill: should have 2026 + 2025, latest selected
  const pills = await page.locator('#yearPill button').allTextContents();
  if (pills.length < 2) bail('expected >=2 year pills, got ' + pills.join(','));
  const active = (await page.locator('#yearPill button[aria-pressed="true"]').textContent()).trim();
  if (active !== '2026') bail('default selected year expected 2026, got ' + active);
  console.log('year pill OK:', pills.map(s => s.trim()).join(' / '), '| active =', active);

  // 4 stat cards vs API
  const yearly = await (await fetch(ORIGIN + '/api/stats/yearly?year=2026', { headers: H })).json();
  const tripsTxt = (await page.locator('#sTrips').textContent()).trim();
  if (!tripsTxt.startsWith(String(yearly.tripCount))) bail('tripCount card mismatch: ' + tripsTxt + ' vs ' + yearly.tripCount);
  const daysTxt = (await page.locator('#sDays').textContent()).trim();
  if (!daysTxt.startsWith(String(yearly.totalDays))) bail('totalDays card mismatch: ' + daysTxt);
  const destTxt = (await page.locator('#sDest').textContent()).trim();
  if (!destTxt.startsWith(String(yearly.destinationCount))) bail('dest card mismatch: ' + destTxt);
  const costTxt = (await page.locator('#sCost').textContent()).trim();
  if (!costTxt.includes('¥')) bail('cost card missing ¥: ' + costTxt);
  console.log('cards OK:', { tripsTxt, daysTxt, destTxt, costTxt });

  // yearly bars: 2 columns x 2 bars
  const cols = await page.locator('#chartYear .bar-col').count();
  if (cols !== 2) bail('yearly chart expected 2 columns, got ' + cols);
  const bars = await page.locator('#chartYear .bar').count();
  if (bars !== 4) bail('yearly chart expected 4 bars, got ' + bars);
  const legend = await page.locator('#chartYear .legend span').count();
  if (legend !== 2) bail('yearly legend expected 2, got ' + legend);
  console.log('yearly chart OK: 2 years x 2 bars + legend');

  // monthly: 12 columns
  const mos = await page.locator('#chartMonth .mo').count();
  if (mos !== 12) bail('monthly chart expected 12 columns, got ' + mos);
  const hasMonths = await page.locator('#chartMonth .mo.has').count();
  if (hasMonths < 1) bail('monthly chart should have at least 1 active month');
  console.log('monthly chart OK: 12 columns,', hasMonths, 'active');

  // category donut (expense exists in 2025 trip, endpoint is global)
  const catRows = await page.locator('#chartCat .cat-row').count();
  if (catRows < 1) bail('category donut should have rows after seeding expense, got ' + catRows);
  const segs = await page.locator('#chartCat svg circle').count();
  if (segs < 1) bail('donut segments missing');
  const catTxt = (await page.locator('#chartCat').textContent()).replace(/\s+/g, ' ');
  if (!catTxt.includes('1,500')) bail('category amount should show 1,500: ' + catTxt.slice(0, 120));
  console.log('category donut OK:', catRows, 'rows /', segs, 'segs');

  // highlights
  const hls = await page.locator('#highlights .hl').count();
  if (hls !== 3) bail('highlights expected 3 cards, got ' + hls);
  const hlTxt = (await page.locator('#highlights').textContent()).replace(/\s+/g, ' ');
  if (!hlTxt.includes('阿拉善盟')) bail('most visited should be 阿拉善盟, got: ' + hlTxt.slice(0, 120));
  console.log('highlights OK (3 cards, 最常去 阿拉善盟)');

  // yearly table: 2 rows
  const rowsT = await page.locator('#yearTable tbody tr').count();
  if (rowsT !== 2) bail('year table expected 2 rows, got ' + rowsT);
  const minis = await page.locator('#yearTable tbody tr').first().locator('.mini').count();
  if (minis !== 3) bail('each row expected 3 mini bars, got ' + minis);
  console.log('year table OK: 2 rows x 3 mini bars');

  await page.screenshot({ path: path.join(SHOTS, 'overview.png'), fullPage: true });

  // ---- drilldown: click 2025 row -> pill switches, cards change ----
  console.log('--- drilldown to 2025 ---');
  await page.click('#yearTable tbody tr[data-year="2025"]');
  await page.waitForTimeout(1200);
  const active2 = (await page.locator('#yearPill button[aria-pressed="true"]').textContent()).trim();
  if (active2 !== '2025') bail('after drilldown active year expected 2025, got ' + active2);
  const trips2025 = (await page.locator('#sTrips').textContent()).trim();
  if (!trips2025.startsWith('1')) bail('2025 trip count expected 1, got ' + trips2025);
  const days2025 = (await page.locator('#sDays').textContent()).trim();
  if (!days2025.startsWith('5')) bail('2025 total days expected 5, got ' + days2025);
  const monthSub = (await page.locator('#monthSub').textContent()).trim();
  if (!monthSub.includes('2025')) bail('month subtitle should show 2025, got ' + monthSub);
  const mayHas = await page.locator('#chartMonth .mo.has').count();
  if (mayHas !== 1) bail('2025 should have exactly 1 active month, got ' + mayHas);
  console.log('drilldown OK: 2025 / 1 次 / 5 天 / 1 active month');
  await page.screenshot({ path: path.join(SHOTS, 'year-2025.png'), fullPage: true });

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  // ---- cleanup ----
  console.log('--- cleanup ---');
  const delRes = await fetch(ORIGIN + '/api/trips/' + trip.id, { method: 'DELETE', headers: H });
  console.log('delete seeded trip', trip.id, delRes.status);
  const check = await (await fetch(ORIGIN + '/api/stats/overview', { headers: H })).json();
  console.log('after cleanup: tripCount =', check.tripCount, '| years =', (check.availableYears || []).join(','));
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
