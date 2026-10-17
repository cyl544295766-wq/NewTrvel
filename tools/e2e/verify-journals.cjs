// Verify journals page: write (publish/draft) / mood filter / state filter / hero card / edit + cleanup
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/journals-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };

  // ---- seed 2 photos so journal can link them ----
  console.log('--- seed photos ---');
  const seededPhotos = [];
  for (const f of ['D:/tmp/p1-landscape.png', 'D:/tmp/p2-portrait.png']) {
    const dataUrl = 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos', {
      method: 'POST', headers: H, body: JSON.stringify({ url: dataUrl, caption: '游记配图' })
    });
    const j = await r.json();
    seededPhotos.push(j.photo.id);
  }
  console.log('seeded photos:', seededPhotos.length);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load journals (empty) ---');
  await page.goto(ORIGIN + '/journals/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
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
  if (ppCount !== 2) bail('photo picker expected 2, got ' + ppCount);
  await page.locator('#drPhotos .pp').nth(0).click();
  await page.locator('#drPhotos .pp').nth(1).click();
  const pickedOn = await page.locator('#drPhotos .pp.on').count();
  if (pickedOn !== 2) bail('expected 2 picked photos, got ' + pickedOn);
  // new journal defaults to publish mode
  const lab0 = (await page.locator('#drSave').textContent()).trim();
  if (lab0 !== '保存并发布') bail('new journal should default to 保存并发布, got ' + lab0);
  // toggle to draft and back, verifying label sync
  await page.click('#drDraftSw');
  const lab1 = (await page.locator('#drSave').textContent()).trim();
  if (lab1 !== '存为草稿') bail('after toggle expect 存为草稿, got ' + lab1);
  const labTxt = (await page.locator('#drDraftLab').textContent()).trim();
  if (labTxt !== '先存为草稿') bail('draft label expected 先存为草稿, got ' + labTxt);
  await page.click('#drDraftSw');
  const lab2 = (await page.locator('#drSave').textContent()).trim();
  if (lab2 !== '保存并发布') bail('after untoggle expect 保存并发布, got ' + lab2);
  await page.click('#drSave');
  await page.waitForSelector('#jGrid .hero-card', { timeout: 10000 });
  await page.waitForTimeout(600);

  const heroTitle = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  if (heroTitle !== '验证游记一') bail('hero title wrong: ' + heroTitle);
  const moodChip = (await page.locator('#jGrid .hero-card .mood-chip').textContent()).trim();
  if (!moodChip.includes('开心')) bail('mood chip expected 开心, got ' + moodChip);
  const heroPhotos = await page.locator('#jGrid .hero-card .hero-photos img').count();
  if (heroPhotos !== 2) bail('hero photos expected 2, got ' + heroPhotos);
  const ovCount = (await page.locator('#ovCount').textContent()).trim();
  const ovWords = (await page.locator('#ovWords').textContent()).replace(/,/g, '');
  const ovPhotos = (await page.locator('#ovPhotos').textContent()).trim();
  if (ovCount !== '1') bail('ovCount expected 1, got ' + ovCount);
  if (Number(ovWords) < 20) bail('ovWords too small: ' + ovWords);
  if (ovPhotos !== '2') bail('ovPhotos expected 2, got ' + ovPhotos);
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
  if (!draftOn) bail('draft toggle should be on after click');
  await page.click('#drSave');
  await page.waitForTimeout(1500);

  const cards = await page.locator('#jGrid .j-card').count();
  if (cards !== 1) bail('expected 1 normal card, got ' + cards);
  const draftBadges = await page.locator('.draft-badge').count();
  if (draftBadges !== 1) bail('expected 1 DRAFT badge, got ' + draftBadges);
  const ovDrafts = (await page.locator('#ovDrafts').textContent()).trim();
  if (ovDrafts !== '1') bail('ovDrafts expected 1, got ' + ovDrafts);
  console.log('write #2 OK: draft badge + ovDrafts=1');

  // ---- state filter: draft ----
  console.log('--- filters ---');
  await page.click('#stateSeg button[data-s="draft"]');
  await page.waitForTimeout(400);
  const draftView = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  if (draftView !== 1) bail('draft filter expected 1, got ' + draftView);
  const isDraftHero = await page.locator('#jGrid .hero-card.draft, #jGrid .j-card.draft').count();
  if (!isDraftHero) bail('filtered result should be the draft');
  await page.click('#stateSeg button[data-s="all"]');
  await page.waitForTimeout(400);

  // ---- mood filter: happy ----
  await page.click('#moodFilter button[data-m="happy"]');
  await page.waitForTimeout(400);
  const happyView = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  if (happyView !== 1) bail('happy filter expected 1, got ' + happyView);
  const happyHero = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  if (happyHero !== '验证游记一') bail('happy filter should show 游记一, got ' + happyHero);
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
  if (preTitle !== '验证游记一') bail('edit prefill title wrong: ' + preTitle);
  if (!preContent.includes('今天的风很软')) bail('edit prefill content wrong');
  const prePicked = await page.locator('#drPhotos .pp.on').count();
  if (prePicked !== 2) bail('edit should prefill 2 picked photos, got ' + prePicked);
  const preMood = await page.locator('#drMoods button[aria-pressed="true"]').getAttribute('data-mood');
  if (preMood !== 'happy') bail('edit should prefill mood happy, got ' + preMood);
  const drTitleLab = (await page.locator('#drTitle').textContent()).trim();
  if (drTitleLab !== '编辑游记') bail('drawer title expected 编辑游记, got ' + drTitleLab);
  await page.fill('#drName', '验证游记一（改）');
  await page.click('#drSave');
  await page.waitForTimeout(1500);
  const heroTitle2 = (await page.locator('#jGrid .hero-card h2').textContent()).trim();
  if (heroTitle2 !== '验证游记一（改）') bail('edited title not reflected: ' + heroTitle2);
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
  if (afterDel !== 1) bail('expected 1 left after deleting normal card, got ' + afterDel);
  page.once('dialog', d => d.accept());
  await page.locator('#jGrid .hero-card').first().hover();
  await page.locator('#jGrid .hero-card').first().locator('.hero-ops .del').click();
  await page.waitForTimeout(1200);
  const afterDel2 = await page.locator('#jGrid .hero-card, #jGrid .j-card').count();
  if (afterDel2 !== 0) bail('expected 0 after deleting hero, got ' + afterDel2);
  const backToEmpty = await page.locator('.empty-state').count();
  if (!backToEmpty) bail('empty state should return after deleting all');
  console.log('delete OK (card + hero, back to empty state)');

  await browser.close();

  // ---- cleanup ----
  console.log('--- cleanup ---');
  const jRes2 = await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals', { headers: { 'Authorization': 'Bearer ' + token } });
  const jList2 = (await jRes2.json()).journals || [];
  for (const j of jList2) {
    const rr = await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals/' + j.id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }
    });
    console.log('delete journal', j.id, rr.status);
  }
  for (const pid of seededPhotos) {
    const rr = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos/' + pid, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }
    });
    console.log('delete photo', pid, rr.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
