// Verify journal-detail: hero / prose typography / TOC / photo band / pager / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, writePngFile } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/journal-detail-shots';
assertLocalOrigin(ORIGIN);

let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const cleaner = new Cleaner();
let browser = null;
let tripId = null;
let user = null;

const MULTI = [
  '清晨五点的沙丘还是凉的，把外套裹紧才敢下车。',
  '走到脊线上等日出，风把脚印一层层抹平，像是从来没来过人。',
  '日出',
  '天边先是灰，然后是一线橙，最后整个沙海亮起来。我坐在原地看了很久，什么都没想。',
  '收帐篷时发现鞋里全是沙，抖了半天才干净。',
  '回程',
  '车子陷进软沙，四个人挖了四十分钟。所有人都在笑，没人抱怨一句。'
].join('\n\n');

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 + 行程 + 天 + 地点 + 2 照片 + 2 游记（pager 用） ----
  user = await registerUser(ORIGIN, 'jd');
  cleaner.addUser(user.id);
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '阅读页验证行程（临时）', destination: '沙谷', startDate: '2026-06-10', endDate: '2026-06-12'
  })).trip.id);
  await apiU.post('/api/trips/' + tripId + '/days/generate', {});
  const days = (await apiU.get('/api/trips/' + tripId + '/days')).days;
  for (const d of days) cleaner.addDay(tripId, d.id);
  const day1 = days[0];
  const lock = await apiU.post('/api/trips/' + tripId + '/days/' + day1.id + '/edit-lock', {});
  const place1 = await apiU.post('/api/trips/' + tripId + '/places', {
    tripDayId: day1.id, name: '沙脊观景台', type: 'attraction', longitude: 100.1, latitude: 40.2
  });
  cleaner.addPlace(place1.place ? place1.place.id : place1.id);
  await apiU.del('/api/trips/' + tripId + '/days/' + day1.id + '/edit-lock');
  writePngFile('D:/tmp/jd-a.png', 240, 240, [200, 120, 40]);
  writePngFile('D:/tmp/jd-b.png', 320, 200, [13, 110, 110]);
  const photoIds = [];
  for (const f of ['D:/tmp/jd-a.png', 'D:/tmp/jd-b.png']) {
    const dataUrl = 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
    const j = await apiU.post('/api/trips/' + tripId + '/photos', { url: dataUrl, caption: '日落时分的沙脊' });
    const ph = j.photo || j;
    photoIds.push(ph.id);
    cleaner.addPhoto(ph.id, ph.url);
  }
  const j1 = (await apiU.post('/api/trips/' + tripId + '/journals', {
    title: '验证阅读页·入沙', content: MULTI, mood: 'excited', isDraft: false,
    photoIds: photoIds, tripDayId: day1 ? day1.id : null, tripPlaceId: place1.place ? place1.place.id : null
  })).journal;
  if (!j1) bail('seed journal 1 failed');
  cleaner.addJournal(j1.id);
  // 第二篇按 createdAt 排序在 j1 之后（保留 1.1s 间隔确保先后）
  await new Promise(r => setTimeout(r, 1100));
  const j2 = (await apiU.post('/api/trips/' + tripId + '/journals', {
    title: '验证阅读页·扎营', content: '第二篇用来验证上一篇/下一篇导航。', mood: 'relaxed', isDraft: false
  })).journal;
  cleaner.addJournal(j2.id);
  ok('夹具就绪 journal=' + j1.id + ' / ' + j2.id);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token: user.token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- open detail (journal #1, older) ---');
  await page.goto(ORIGIN + '/journal-detail/?id=' + j1.id + '&trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero h1', { timeout: 8000 });
  await page.waitForTimeout(600);

  const h1 = (await page.locator('.hero h1').textContent()).trim();
  check(h1 === '验证阅读页·入沙', 'hero 标题: ' + h1);
  const moodChip = (await page.locator('.mood-chip').textContent()).trim();
  check(moodChip.includes('兴奋'), '心情 chip 兴奋: ' + moodChip);

  // typography / parsing
  const h3 = await page.locator('.prose h3').count();
  check(h3 === 2, '2 个小标题: ' + h3);
  const h3first = (await page.locator('.prose h3').first().textContent()).trim();
  check(h3first === '日出', '首标题 = 日出: ' + h3first);
  const paras = await page.locator('.prose p').count();
  check(paras === 4, '4 段（1 段成拉引句）: ' + paras);
  const dropcaps = await page.locator('.prose p.dropcap').count();
  check(dropcaps === 1, '1 个首字下沉段: ' + dropcaps);
  const pulls = await page.locator('.prose .pull').count();
  check(pulls === 1, '1 个拉引句: ' + pulls);
  // dropcap visual: ::first-letter float
  const fc = await page.locator('.prose p.dropcap').first().evaluate(el => getComputedStyle(el, '::first-letter').float);
  check(fc === 'left', '首字下沉浮动 left: ' + fc);
  console.log('typography OK: 2 headings / 4 paras / 1 dropcap / 1 pull-quote');

  // TOC
  const tocLinks = await page.locator('#toc a').count();
  check(tocLinks === 2, 'TOC 2 锚点: ' + tocLinks);
  const tocHref = await page.locator('#toc a').first().getAttribute('href');
  check(tocHref === '#sec-1', 'TOC 首锚点 #sec-1: ' + tocHref);
  console.log('TOC OK: 2 anchors, first ->', tocHref);

  // photo band
  const band = await page.locator('.pb-item').count();
  check(band === 2, '照片带 2 张: ' + band);
  const cap = (await page.locator('.pb-item .cap').first().textContent()).trim();
  check(cap.includes('日落时分'), '照片说明: ' + cap);

  // aside context
  const asideTxt = (await page.locator('.aside').textContent()).replace(/\s+/g, ' ');
  check(asideTxt.includes('D1'), '侧栏显示 D1 上下文');
  const moodName = (await page.locator('.mood-nm').textContent()).trim();
  check(moodName === '兴奋', '侧栏心情名 兴奋: ' + moodName);
  console.log('aside OK: day context + mood card');

  // pager: j1 is older -> should have next (扎营), no prev
  const pagerLinks = await page.locator('.pager a').count();
  check(pagerLinks === 1, '最旧一篇 pager 仅 1 链接: ' + pagerLinks);
  const pagerTxt = (await page.locator('.pager').textContent()).replace(/\s+/g, ' ');
  check(pagerTxt.includes('扎营'), 'pager 指向扎营: ' + pagerTxt);
  const editHref = await page.locator('.hero-actions .edit').getAttribute('href');
  check(editHref.includes('/journals/') && editHref.includes('edit='), '编辑链接: ' + editHref);
  console.log('pager OK: next -> 扎营 | edit href =', editHref);

  await page.screenshot({ path: path.join(SHOTS, 'detail.png'), fullPage: true });

  // ---- TOC anchor click scrolls ----
  await page.click('#toc a:nth-child(2)');
  await page.waitForTimeout(900);
  const y = await page.evaluate(() => window.scrollY);
  check(y >= 100, 'TOC 点击滚动（scrollY=' + Math.round(y) + '）');
  console.log('TOC anchor scroll OK (scrollY=' + Math.round(y) + ')');

  // ---- open the other journal to check prev link ----
  await page.goto(ORIGIN + '/journal-detail/?id=' + j2.id + '&trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero h1', { timeout: 8000 });
  await page.waitForTimeout(400);
  const pager2 = (await page.locator('.pager').textContent()).replace(/\s+/g, ' ');
  check(pager2.includes('入沙'), '最新一篇 pager 含 prev 入沙: ' + pager2);
  const noToc = await page.locator('#toc').count();
  check(noToc === 0, '单段游记无 TOC');
  console.log('pager/prev OK + no-TOC case OK');

  // mobile
  await page.goto(ORIGIN + '/journal-detail/?id=' + j1.id + '&trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero h1', { timeout: 8000 });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  // ---- delete via UI (on journal #1) ----
  console.log('--- delete via UI ---');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(300);
  page.once('dialog', d => d.accept());
  await page.click('#btnDel');
  try {
    await page.waitForURL(/\/journals\//, { timeout: 6000 });
    ok('删除后跳回 journals');
  } catch (e) {
    check(false, '删除未跳回 journals');
  }

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
