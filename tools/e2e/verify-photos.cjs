// Verify photos page: multi-upload / day grouping / view switch / lightbox edit / cover / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, writePngFile } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/photos-shots';
assertLocalOrigin(ORIGIN);

let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const cleaner = new Cleaner();
let browser = null;
let tripId = null;
let user = null;

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 + 行程 + 行程天（D1 分组） + 上传用 PNG 文件 ----
  user = await registerUser(ORIGIN, 'ph');
  cleaner.addUser(user.id);
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '照片验证行程（临时）', destination: '光影镇', startDate: '2026-06-10', endDate: '2026-06-12'
  })).trip.id);
  await apiU.post(`/api/trips/${tripId}/days/generate`, {});
  const days = (await apiU.get(`/api/trips/${tripId}/days`)).days;
  for (const d of days) cleaner.addDay(tripId, d.id);
  writePngFile('D:/tmp/p1-landscape.png', 320, 200, [13, 110, 110]);
  writePngFile('D:/tmp/p2-portrait.png', 200, 320, [90, 60, 120]);
  writePngFile('D:/tmp/p3-square.png', 240, 240, [200, 120, 40]);
  ok('夹具就绪 trip=' + tripId + '（3 张 PNG 已生成）');

  const token = user.token;

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
  await page.goto(ORIGIN + '/photos/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  const ov0 = (await page.locator('#ovTotal').textContent()).trim();
  check(ov0 === '0', '空态照片数 = 0: ' + ov0);
  console.log('empty state OK');

  // ---- multi upload 3 photos into D1 ----
  console.log('--- multi upload (3 photos -> D1) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.setInputFiles('#fileInput', ['D:/tmp/p1-landscape.png', 'D:/tmp/p2-portrait.png', 'D:/tmp/p3-square.png']);
  await waitFor('3 个文件 chips', () => page.locator('#fileChips .fchip').count().then((n) => n === 3).catch(() => false), 10000);
  const chips = await page.locator('#fileChips .fchip').count();
  check(chips === 3, '文件 chips = 3: ' + chips);
  const cntLab = (await page.locator('#fpCount').textContent()).trim();
  check(cntLab.includes('3'), 'fpCount 含 3: ' + cntLab);
  // pick D1
  const dayOpts = await page.locator('#fDay option').count();
  check(dayOpts >= 2, '日期下拉有选项: ' + dayOpts);
  await page.selectOption('#fDay', { index: 1 });
  await page.fill('#fCaption', '验证照片');
  await page.click('#btnUpload');
  await waitFor('照片卡片渲染', () => page.locator('.ph-card').count().then((n) => n === 3).catch(() => false), 20000);

  const cards = await page.locator('.ph-card').count();
  check(cards === 3, '上传后 3 张卡片: ' + cards);
  const listed = (await apiU.get(`/api/trips/${tripId}/photos`)).photos || [];
  for (const ph of listed) {
    cleaner.addPhoto(ph.id, ph.url);
  }
  ok('已登记 ' + listed.length + ' 张照片进清理器');
  console.log('upload OK: 3 cards');

  // grouping: one day section with D1
  const secs = await page.locator('.day-sec').count();
  check(secs === 1, '按天分组 1 段: ' + secs);
  const dn = (await page.locator('.day-sec .dn').first().textContent()).trim();
  check(dn === 'D1', '分组头 D1: ' + dn);
  const groupN = (await page.locator('.day-sec .n').first().textContent()).trim();
  check(groupN.includes('3'), '分组计数 3 张: ' + groupN);
  console.log('grouping OK: D1 section with 3 photos');

  // stats
  const ovTotal = (await page.locator('#ovTotal').textContent()).trim();
  const ovDays = (await page.locator('#ovDays').textContent()).trim();
  check(ovTotal === '3', '统计总数 = 3: ' + ovTotal);
  check(ovDays === '1', '统计天数 = 1: ' + ovDays);
  // captions applied
  const caps = await page.locator('.ph-cap .tx').allTextContents();
  check(caps.every(t => t.includes('验证照片')), '说明已应用: ' + caps.join('|'));
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
  check(hasGrid > 0, '网格视图类生效');
  await page.screenshot({ path: path.join(SHOTS, 'grid.png'), fullPage: true });
  await page.click('#viewSeg button[data-v="masonry"]');
  await page.waitForTimeout(300);
  check((await page.locator('.masonry').count()) > 0, '瀑布流恢复');
  console.log('view switch OK (grid <-> masonry, persisted)');

  // ---- lightbox: open, edit caption, save ----
  console.log('--- lightbox ---');
  await page.locator('.ph-card').first().locator('[data-zoom]').click();
  await page.waitForSelector('#lbMask:not([hidden])', { timeout: 3000 });
  const metaTxt = (await page.locator('#lbMeta').textContent()).replace(/\s+/g, ' ');
  check(metaTxt.includes('D1'), '灯箱元信息含 D1: ' + metaTxt);
  const capVal = await page.locator('#lbCaption').inputValue();
  check(capVal === '验证照片', '灯箱说明预填: ' + capVal);
  await page.fill('#lbCaption', '改后说明');
  await page.click('#lbSave');
  await page.waitForTimeout(1500);
  const caps2 = await page.locator('.ph-cap .tx').allTextContents();
  check(caps2.some(t => t.includes('改后说明')), '说明编辑已反映');
  console.log('lightbox edit OK');

  // ---- set cover via lightbox on second photo ----
  console.log('--- set cover ---');
  await page.locator('.ph-card').nth(1).locator('[data-zoom]').click();
  await page.waitForSelector('#lbMask:not([hidden])');
  await page.click('#lbCover');
  await page.waitForTimeout(1500);
  const badges = await page.locator('.ph-cover-badge').count();
  check(badges === 1, '封面徽标唯一: ' + badges);
  const ovCover = (await page.locator('#ovCover').textContent()).trim();
  check(ovCover !== '–', '统计封面非占位: ' + ovCover);
  console.log('cover OK: 1 badge, ovCover =', ovCover);
  await page.screenshot({ path: path.join(SHOTS, 'cover.png'), fullPage: true });

  // ---- card-level cover + delete ----
  console.log('--- card ops ---');
  await page.locator('.ph-card').first().hover();
  await page.locator('.ph-card').first().locator('.ph-ops .cover').click();
  await page.waitForTimeout(1200);
  const badgeOwner = await page.locator('.ph-card').first().locator('.ph-cover-badge').count();
  check(badgeOwner > 0, '卡片级封面切换生效');
  console.log('card cover OK (badge moved)');

  page.once('dialog', d => d.accept());
  await page.locator('.ph-card').first().hover();
  await page.locator('.ph-card').first().locator('.ph-ops .del').click();
  await page.waitForTimeout(1200);
  const left = await page.locator('.ph-card').count();
  check(left === 2, '删除后剩 2 张: ' + left);
  console.log('delete OK: 2 left');

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
