// Verify journal-detail: hero / prose typography / TOC / photo band / pager / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/journal-detail-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

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
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };

  // ---- seed: 2 photos + 2 journals (for pager) ----
  console.log('--- seed data ---');
  const photoIds = [];
  for (const f of ['D:/tmp/p3-square.png', 'D:/tmp/p1-landscape.png']) {
    const dataUrl = 'data:image/png;base64,' + fs.readFileSync(f).toString('base64');
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos', {
      method: 'POST', headers: H, body: JSON.stringify({ url: dataUrl, caption: '日落时分的沙脊' })
    });
    photoIds.push((await r.json()).photo.id);
  }
  const days = await (await fetch(ORIGIN + '/api/trips/' + TRIP + '/days', { headers: H })).json();
  const day1 = (days.days || [])[0];
  const place1 = day1 && (day1.places || [])[0];

  const j1r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals', {
    method: 'POST', headers: H,
    body: JSON.stringify({
      title: '验证阅读页·入沙', content: MULTI, mood: 'excited', isDraft: false,
      photoIds: photoIds, tripDayId: day1 ? day1.id : null, tripPlaceId: place1 ? place1.id : null
    })
  });
  const j1 = (await j1r.json()).journal;
  if (!j1) bail('seed journal 1 failed');
  // second journal (older timestamp is not controllable, but order by createdAt: j1 then j2)
  await new Promise(r => setTimeout(r, 1100));
  const j2r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals', {
    method: 'POST', headers: H,
    body: JSON.stringify({ title: '验证阅读页·扎营', content: '第二篇用来验证上一篇/下一篇导航。', mood: 'relaxed', isDraft: false })
  });
  const j2 = (await j2r.json()).journal;
  console.log('seeded journal', j1.id, 'and', j2.id, '| photos', photoIds.length);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- open detail (journal #1, older) ---');
  await page.goto(ORIGIN + '/journal-detail/?id=' + j1.id + '&trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero h1', { timeout: 8000 });
  await page.waitForTimeout(600);

  const h1 = (await page.locator('.hero h1').textContent()).trim();
  if (h1 !== '验证阅读页·入沙') bail('hero title wrong: ' + h1);
  const moodChip = (await page.locator('.mood-chip').textContent()).trim();
  if (!moodChip.includes('兴奋')) bail('mood chip expected 兴奋, got ' + moodChip);

  // typography / parsing
  const h3 = await page.locator('.prose h3').count();
  if (h3 !== 2) bail('expected 2 section headings, got ' + h3);
  const h3first = (await page.locator('.prose h3').first().textContent()).trim();
  if (h3first !== '日出') bail('first heading expected 日出, got ' + h3first);
  const paras = await page.locator('.prose p').count();
  if (paras !== 4) bail('expected 4 paragraphs (1 became pull-quote), got ' + paras);
  const dropcaps = await page.locator('.prose p.dropcap').count();
  if (dropcaps !== 1) bail('expected 1 dropcap paragraph, got ' + dropcaps);
  const pulls = await page.locator('.prose .pull').count();
  if (pulls !== 1) bail('expected 1 pull-quote, got ' + pulls);
  // dropcap visual: ::first-letter float
  const fc = await page.locator('.prose p.dropcap').first().evaluate(el => getComputedStyle(el, '::first-letter').float);
  if (fc !== 'left') bail('dropcap ::first-letter should float left, got ' + fc);
  console.log('typography OK: 2 headings / 4 paras / 1 dropcap / 1 pull-quote');

  // TOC
  const tocLinks = await page.locator('#toc a').count();
  if (tocLinks !== 2) bail('TOC expected 2 anchors, got ' + tocLinks);
  const tocHref = await page.locator('#toc a').first().getAttribute('href');
  if (tocHref !== '#sec-1') bail('TOC first href expected #sec-1, got ' + tocHref);
  console.log('TOC OK: 2 anchors, first ->', tocHref);

  // photo band
  const band = await page.locator('.pb-item').count();
  if (band !== 2) bail('photo band expected 2, got ' + band);
  const cap = (await page.locator('.pb-item .cap').first().textContent()).trim();
  if (!cap.includes('日落时分')) bail('photo caption wrong: ' + cap);

  // aside context
  const asideTxt = (await page.locator('.aside').textContent()).replace(/\s+/g, ' ');
  if (!asideTxt.includes('D1')) bail('aside should show day context D1, got: ' + asideTxt.slice(0, 160));
  const moodName = (await page.locator('.mood-nm').textContent()).trim();
  if (moodName !== '兴奋') bail('aside mood name expected 兴奋, got ' + moodName);
  console.log('aside OK: day context + mood card');

  // pager: j1 is older -> should have next (扎营), no prev
  const pagerLinks = await page.locator('.pager a').count();
  if (pagerLinks !== 1) bail('pager expected 1 link for oldest journal, got ' + pagerLinks);
  const pagerTxt = (await page.locator('.pager').textContent()).replace(/\s+/g, ' ');
  if (!pagerTxt.includes('扎营')) bail('pager should point to 扎营, got: ' + pagerTxt);
  const editHref = await page.locator('.hero-actions .edit').getAttribute('href');
  if (!editHref.includes('/journals/') || !editHref.includes('edit=')) bail('edit href wrong: ' + editHref);
  console.log('pager OK: next -> 扎营 | edit href =', editHref);

  await page.screenshot({ path: path.join(SHOTS, 'detail.png'), fullPage: true });

  // ---- TOC anchor click scrolls ----
  await page.click('#toc a:nth-child(2)');
  await page.waitForTimeout(900);
  const y = await page.evaluate(() => window.scrollY);
  if (y < 100) bail('TOC click should scroll down, scrollY=' + y);
  console.log('TOC anchor scroll OK (scrollY=' + Math.round(y) + ')');

  // ---- open the other journal to check prev link ----
  await page.goto(ORIGIN + '/journal-detail/?id=' + j2.id + '&trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero h1', { timeout: 8000 });
  await page.waitForTimeout(400);
  const pager2 = (await page.locator('.pager').textContent()).replace(/\s+/g, ' ');
  if (!pager2.includes('入沙')) bail('newest journal pager should have prev 入沙, got: ' + pager2);
  const noToc = await page.locator('#toc').count();
  if (noToc !== 0) bail('single-paragraph journal should have no TOC');
  console.log('pager/prev OK + no-TOC case OK');

  // mobile
  await page.goto(ORIGIN + '/journal-detail/?id=' + j1.id + '&trip=' + TRIP, { waitUntil: 'domcontentloaded' });
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
    console.log('delete OK -> navigated back to journals');
  } catch (e) {
    bail('delete did not navigate back to journals');
  }

  await browser.close();

  // ---- cleanup ----
  console.log('--- cleanup ---');
  const jl = await (await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals', { headers: H })).json();
  for (const j of (jl.journals || [])) {
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/journals/' + j.id, { method: 'DELETE', headers: H });
    console.log('delete journal', j.id, r.status);
  }
  for (const pid of photoIds) {
    const r = await fetch(ORIGIN + '/api/trips/' + TRIP + '/photos/' + pid, { method: 'DELETE', headers: H });
    console.log('delete photo', pid, r.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
