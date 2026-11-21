// Verify journals page: write (publish/draft) / mood filter / state filter / hero card / edit + cleanup
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, writePngFile } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/journals-shots';
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

  // ---- 夹具：自注册 + 行程 + 天 + 2 张配图照片（data URL，按 ID 登记） ----
  user = await registerUser(ORIGIN, 'jn');
  cleaner.addUser(user.id);
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '游记验证行程（临时）', destination: '沙丘镇', startDate: '2026-06-10', endDate: '2026-06-12'
  })).trip.id);
  await apiU.post('/api/trips/' + tripId + '/days/generate', {});
  writePngFile('D:/tmp/jn-a.png', 320, 200, [13, 110, 110]);
  writePngFile('D:/tmp/jn-b.png', 200, 320, [90, 60, 120]);
  const seededPhotos = [];
  for (const f of ['D:/tmp/jn-a.png', 'D:/tmp/jn-b.png']) {
    const dataUrl = 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
    const j = await apiU.post('/api/trips/' + tripId + '/photos', { url: dataUrl, caption: '游记配图' });
    const ph = j.photo || j;
    seededPhotos.push(ph.id);
    cleaner.addPhoto(ph.id, ph.url);
  }
  ok('夹具就绪 trip=' + tripId + '（配图 ' + seededPhotos.length + ' 张）');

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token: user.token, u: { id: user.id, username: user.username, displayName: "" } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load journals (empty) ---');
  await page.goto(ORIGIN + '/journals/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  console.log('empty state OK');

  // ---- write #1: publish with 2 photos + happy mood ----
  console.log('--- write #1 (publish, happy, 2 photos) ---');
  await page.click('#btnWrite');
  await page.waitForSelector('#drawer:not([hidden])', { timeout: 3000 });
  await page.fill('#drName', '验证游记一');
  await page.fill('#drContent', '今天的风很软，沙脊上的脚印很快被吹平。坐下来看了很久的日落，什么都不想做。' + '记录记录记录。'.repeat(4));
  await page.click('#drMoods button[data-mood="happy"]');
  const ppCount = await page.locator('#drPhotos .pp').count();
  check(ppCount === 2, '照片选择器 2 张: ' + ppCount);
  await page.locator('#drPhotos .pp').nth(0).click();
  await page.locator('#drPhotos .pp').nth(1).click();
  const pickedOn = await page.locator('#drPhotos .pp.on').count();
  check(pickedOn === 2, '选中 2 张: ' + pickedOn);
  // new journal defaults to publish mode
  const lab0 = (await page.locator('#drSave').textContent()).trim();
  check(lab0 === '保存并发布', '新游记默认保存并发布: ' + lab0);
  // toggle to draft and back, verifying label sync
  await page.click('#drDraftSw');
  const lab1 = (await page.locator('#drSave').textContent()).trim();
  check(lab1 === '存为草稿', '切换后存为草稿: ' + lab1);
  const labTxt = (await page.locator('#drDraftLab').textContent()).trim();
  check(labTxt === '先存为草稿', '草稿标签: ' + labTxt);
  await page.click('#drDraftSw');
  const lab2 = (await page.locator('#drSave').textContent()).trim();
  check(lab2 === '保存并发布', '切回保存并发布: ' + lab2);
  await page.click('#drSave');
  await page.waitForSelector('#jGrid .hero-card', { timeout: 10000 });
  await page.waitForTimeout(600);

  const heroTitle = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  check(heroTitle === '验证游记一', 'hero 标题: ' + heroTitle);
  const moodChip = (await page.locator('#jGrid .hero-card .mood-chip').textContent()).trim();
  check(moodChip.includes('开心'), '心情 chip 开心: ' + moodChip);
  const heroPhotos = await page.locator('#jGrid .hero-card .hero-photos img').count();
  check(heroPhotos === 2, 'hero 配图 2 张: ' + heroPhotos);
  const ovCount = (await page.locator('#ovCount').textContent()).trim();
  const ovWords = (await page.locator('#ovWords').textContent()).replace(/,/g, '');
  const ovPhotos = (await page.locator('#ovPhotos').textContent()).trim();
  check(ovCount === '1', '游记计数 = 1: ' + ovCount);
  check(Number(ovWords) >= 20, '字数统计 >=20: ' + ovWords);
  check(ovPhotos === '2', '配图统计 = 2: ' + ovPhotos);
  console.log('write #1 OK: hero card + 开心 chip + 2 photos, words =', ovWords);
  await page.screenshot({ path: path.join(SHOTS, 'hero.png'), fullPage: true });

  // ---- write #2: draft with tired mood ----
  console.log('--- write #2 (draft, tired) ---');
  await page.click('#btnWrite');
  await page.waitForSelector('#drawer:not([hidden])');
  await page.fill('#drName', '验证游记二');
  await page.fill('#drContent', '腿有点酸，但心里是满的。明天想去看日出。');
  await page.click('#drMoods button[data-mood="tired"]');
  // switch to draft mode explicitly
  await page.click('#drDraftSw');
  const draftOn = await page.locator('#drDraftToggle').evaluate(el => el.classList.contains('on'));
  check(draftOn, '草稿开关开启');
  await page.click('#drSave');
  await page.waitForTimeout(1500);

  const cards = await page.locator('#jGrid .j-card').count();
  check(cards === 1, '普通卡 1 张: ' + cards);
  const draftBadges = await page.locator('.draft-badge').count();
  check(draftBadges === 1, 'DRAFT 徽标 1 个: ' + draftBadges);
  const ovDrafts = (await page.locator('#ovDrafts').textContent()).trim();
  check(ovDrafts === '1', '草稿统计 = 1: ' + ovDrafts);
  console.log('write #2 OK: draft badge + ovDrafts=1');

  // ---- state filter: draft ----
  console.log('--- filters ---');
  await page.click('#stateSeg button[data-s="draft"]');
  await page.waitForTimeout(400);
  const draftView = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  check(draftView === 1, '草稿筛选 1 张: ' + draftView);
  const isDraftHero = await page.locator('#jGrid .hero-card.draft, #jGrid .j-card.draft').count();
  check(isDraftHero > 0, '筛选结果为草稿卡');
  await page.click('#stateSeg button[data-s="all"]');
  await page.waitForTimeout(400);

  // ---- mood filter: happy ----
  await page.click('#moodFilter button[data-m="happy"]');
  await page.waitForTimeout(400);
  const happyView = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  check(happyView === 1, '开心筛选 1 张: ' + happyView);
  const happyHero = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  check(happyHero === '验证游记一', '开心筛选显示游记一: ' + happyHero);
  await page.click('#moodFilter button[data-m=""]');
  await page.waitForTimeout(400);
  console.log('filters OK (state=draft 1, mood=happy 1)');

  // ---- edit hero journal via UI (hover -> pencil) ----
  console.log('--- edit (UI path) ---');
  const hero = page.locator('#jGrid .hero-card').first();
  await hero.hover();
  await hero.locator('.hero-ops .ed').click();
  await page.waitForSelector('#drawer:not([hidden])', { timeout: 3000 });
  const preTitle = await page.locator('#drName').inputValue();
  const preContent = await page.locator('#drContent').inputValue();
  check(preTitle === '验证游记一', '编辑预填标题: ' + preTitle);
  check(preContent.includes('今天的风很软'), '编辑预填正文');
  const prePicked = await page.locator('#drPhotos .pp.on').count();
  check(prePicked === 2, '编辑预填 2 张配图: ' + prePicked);
  const preMood = await page.locator('#drMoods button[aria-pressed="true"]').getAttribute('data-mood');
  check(preMood === 'happy', '编辑预填心情: ' + preMood);
  const drTitleLab = (await page.locator('#drTitle').textContent()).trim();
  check(drTitleLab === '编辑游记', '抽屉标题 = 编辑游记: ' + drTitleLab);
  await page.fill('#drName', '验证游记一（改）');
  await page.click('#drSave');
  await page.waitForTimeout(1500);
  const heroTitle2 = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  check(heroTitle2 === '验证游记一（改）', '改名已反映: ' + heroTitle2);
  console.log('edit OK (prefill title/content/mood/2 photos + rename)');

  // mobile shot before deletion
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(300);

  // ---- delete normal card via UI + delete hero via UI ----
  console.log('--- delete via UI ---');
  const norm = page.locator('#jGrid .j-card').first();
  await norm.hover();
  page.once('dialog', d => d.accept());
  await norm.locator('.card-ops .del').click();
  await page.waitForTimeout(1200);
  const afterDel = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  check(afterDel === 1, '删普通卡后剩 1: ' + afterDel);
  page.once('dialog', d => d.accept());
  await page.locator('#jGrid .hero-card').first().hover();
  await page.locator('#jGrid .hero-card').first().locator('.hero-ops .del').click();
  await page.waitForTimeout(1200);
  const afterDel2 = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  check(afterDel2 === 0, '删 hero 后归零: ' + afterDel2);
  const backToEmpty = await page.locator('.empty-state').count();
  check(backToEmpty > 0, '回到空态');
  console.log('delete OK (card + hero, back to empty state)');

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
