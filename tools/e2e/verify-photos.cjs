// Verify photos page: multi-upload / day grouping / view switch / lightbox edit / cover / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/photos-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
    localStorage.setItem('travelos-photos-view', 'masonry');
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load photos (empty) ---');
  await page.goto(ORIGIN + '/photos/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  const ov0 = (await page.locator('#ovTotal').textContent()).trim();
  if (ov0 !== '0') bail('expected 0 photos, got ' + ov0);
  console.log('empty state OK');

  // ---- multi upload 3 photos into D1 ----
  console.log('--- multi upload (3 photos -> D1) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.setInputFiles('#fileInput', ['D:/tmp/p1-landscape.png', 'D:/tmp/p2-portrait.png', 'D:/tmp/p3-square.png']);
  await page.waitForTimeout(1200);
  const chips = await page.locator('#fileChips .fchip').count();
  if (chips !== 3) bail('expected 3 file chips, got ' + chips);
  const cntLab = (await page.locator('#fpCount').textContent()).trim();
  if (!cntLab.includes('3')) bail('fpCount expected 3, got ' + cntLab);
  // pick D1
  const dayOpts = await page.locator('#fDay option').count();
  if (dayOpts < 2) bail('day select should have options, got ' + dayOpts);
  await page.selectOption('#fDay', { index: 1 });
  await page.fill('#fCaption', '验证照片');
  await page.click('#btnUpload');
  await page.waitForSelector('.ph-card', { timeout: 15000 });
  await page.waitForTimeout(800);

  const cards = await page.locator('.ph-card').count();
  if (cards !== 3) bail('expected 3 photo cards, got ' + cards);
  console.log('upload OK: 3 cards');

  // grouping: one day section with D1
  const secs = await page.locator('.day-sec').count();
  if (secs !== 1) bail('expected 1 day section, got ' + secs);
  const dn = (await page.locator('.day-sec .dn').first().textContent()).trim();
  if (dn !== 'D1') bail('group header expected D1, got ' + dn);
  const groupN = (await page.locator('.day-sec .n').first().textContent()).trim();
  if (!groupN.includes('3')) bail('group count expected 3 张, got ' + groupN);
  console.log('grouping OK: D1 section with 3 photos');

  // stats
  const ovTotal = (await page.locator('#ovTotal').textContent()).trim();
  const ovDays = (await page.locator('#ovDays').textContent()).trim();
  if (ovTotal !== '3') bail('ovTotal expected 3, got ' + ovTotal);
  if (ovDays !== '1') bail('ovDays expected 1, got ' + ovDays);
  // captions applied
  const caps = await page.locator('.ph-cap .tx').allTextContents();
  if (!caps.every(t => t.includes('验证照片'))) bail('captions not applied: ' + caps.join('|'));
  console.log('stats + captions OK:', { ovTotal, ovDays });

  // masonry columns & original ratio preserved (landscape wider than tall)
  const lw = await page.locator('.ph-card').first().locator('img').evaluate(el => el.naturalWidth + 'x' + el.naturalHeight);
  const pw = await page.locator('.ph-card').nth(1).locator('img').evaluate(el => el.naturalWidth + 'x' + el.naturalHeight);
  console.log('natural sizes =', lw, pw);

  await page.screenshot({ path: path.join(SHOTS, 'masonry.png'), fullPage: true });

  // ---- view switch to grid ----
  console.log('--- view switch ---');
  await page.click('#viewSeg button[data-v="grid"]');
  await page.waitForTimeout(400);
  const hasGrid = await page.locator('.grid-view').count();
  if (!hasGrid) bail('grid view class missing');
  await page.screenshot({ path: path.join(SHOTS, 'grid.png'), fullPage: true });
  await page.click('#viewSeg button[data-v="masonry"]');
  await page.waitForTimeout(300);
  if (!(await page.locator('.masonry').count())) bail('masonry view not restored');
  console.log('view switch OK (grid <-> masonry, persisted)');

  // ---- lightbox: open, edit caption, save ----
  console.log('--- lightbox ---');
  await page.locator('.ph-card').first().locator('[data-zoom]').click();
  await page.waitForSelector('#lbMask:not([hidden])', { timeout: 3000 });
  const metaTxt = (await page.locator('#lbMeta').textContent()).replace(/\s+/g, ' ');
  if (!metaTxt.includes('D1')) bail('lightbox meta should show D1, got: ' + metaTxt);
  const capVal = await page.locator('#lbCaption').inputValue();
  if (capVal !== '验证照片') bail('lightbox caption prefill wrong: ' + capVal);
  await page.fill('#lbCaption', '改后说明');
  await page.click('#lbSave');
  await page.waitForTimeout(1500);
  const caps2 = await page.locator('.ph-cap .tx').allTextContents();
  if (!caps2.some(t => t.includes('改后说明'))) bail('caption edit not reflected: ' + caps2.join('|'));
  console.log('lightbox edit OK');

  // ---- set cover via lightbox on second photo ----
  console.log('--- set cover ---');
  await page.locator('.ph-card').nth(1).locator('[data-zoom]').click();
  await page.waitForSelector('#lbMask:not([hidden])');
  await page.click('#lbCover');
  await page.waitForTimeout(1500);
  const badges = await page.locator('.ph-cover-badge').count();
  if (badges !== 1) bail('expected exactly 1 cover badge, got ' + badges);
  const ovCover = (await page.locator('#ovCover').textContent()).trim();
  if (ovCover === '–') bail('ovCover should not be dash after setting');
  console.log('cover OK: 1 badge, ovCover =', ovCover);
  await page.screenshot({ path: path.join(SHOTS, 'cover.png'), fullPage: true });

  // ---- card-level cover + delete ----
  console.log('--- card ops ---');
  await page.locator('.ph-card').first().hover();
  await page.locator('.ph-card').first().locator('.ph-ops .cover').click();
  await page.waitForTimeout(1200);
  const badgeOwner = await page.locator('.ph-card').first().locator('.ph-cover-badge').count();
  if (!badgeOwner) bail('card-level cover click did not move badge to first card');
  console.log('card cover OK (badge moved)');

  page.once('dialog', d => d.accept());
  await page.locator('.ph-card').first().hover();
  await page.locator('.ph-card').first().locator('.ph-ops .del').click();
  await page.waitForTimeout(1200);
  const left = await page.locator('.ph-card').count();
  if (left !== 2) bail('expected 2 cards after delete, got ' + left);
  console.log('delete OK: 2 left');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  // ---- cleanup remaining photos ----
  console.log('--- cleanup ---');
  const listRes = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos', { headers: { 'Authorization': 'Bearer ' + token } });
  const list = await listRes.json();
  for (const p of (list.photos || [])) {
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos/' + p.id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }, credentials: 'omit'
    });
    console.log('delete', p.id, r.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
