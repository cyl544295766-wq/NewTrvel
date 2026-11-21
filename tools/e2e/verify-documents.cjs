// Verify documents page: upload / filter / search / remind toggle / edit / preview / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, writePngFile } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/documents-shots';
assertLocalOrigin(ORIGIN);

let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const cleaner = new Cleaner();
let browser = null;
let tripId = null;
let user = null;
let token = null;

function dateOffset(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });
  const PNG = 'D:/tmp/test-doc.png';
  writePngFile(PNG, 280, 180, [13, 110, 110]);

  // ---- 夹具：自注册 + 行程 ----
  user = await registerUser(ORIGIN, 'dc');
  cleaner.addUser(user.id);
  token = user.token;
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '证件验证行程（临时）', destination: '关防镇', startDate: '2026-07-01', endDate: '2026-07-05'
  })).trip.id);
  ok('夹具就绪 trip=' + tripId);

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load documents (empty) ---');
  await page.goto(ORIGIN + '/documents/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  const ov0 = (await page.locator('#ovTotal').textContent()).trim();
  check(ov0 === '0', '空态证件数 0: ' + ov0);
  console.log('empty state OK');

  // ---- upload doc 1: passport, expiring in 3 days, reminder on ----
  console.log('--- upload doc1 (soon expiry) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.setInputFiles('#fileInput', PNG);
  await page.waitForSelector('#fileChip.show', { timeout: 5000 });
  const chipName = (await page.locator('#chipName').textContent()).trim();
  check(chipName === 'test-doc.png', '文件 chip 名: ' + chipName);
  await page.click('#typeChips button[data-t="passport"]');
  await page.fill('#fTitle', '验证护照');
  await page.fill('#fExpired', dateOffset(3));
  await page.click('#fReminder');
  await page.fill('#fNotes', '签证号 G12345');
  await page.click('#btnSaveDoc');
  await waitFor('doc1 卡片出现', () => page.locator('.doc-card', { hasText: '验证护照' }).count()
    .then((n) => n === 1).catch(() => false), 10000);
  for (const d of ((await apiU.get('/api/trips/' + tripId + '/documents')).documents || [])) {
    cleaner.addDocument(d.id, d.url);
  }

  const card1 = page.locator('.doc-card', { hasText: '验证护照' });
  check((await card1.count()) > 0, 'doc1 卡片存在');
  const cls1 = await card1.getAttribute('class');
  check(cls1.includes('soon'), 'doc1 三天内到期 .soon: ' + cls1);
  const remTxt = (await card1.locator('.rem-lab').textContent()).trim();
  check(remTxt.includes('提醒开'), 'doc1 提醒开: ' + remTxt);
  const soonNum = (await page.locator('#ovSoon').textContent()).trim();
  check(soonNum === '1', '即将到期统计 = 1: ' + soonNum);
  const remNum = (await page.locator('#ovRemind').textContent()).trim();
  check(remNum === '1', '提醒统计 = 1: ' + remNum);
  console.log('doc1 OK: soon color band + reminder on + overview 1/1');

  // ---- upload doc 2: ticket, no expiry ----
  console.log('--- upload doc2 (no expiry) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.setInputFiles('#fileInput', PNG);
  await page.waitForSelector('#fileChip.show', { timeout: 5000 });
  await page.click('#typeChips button[data-t="ticket"]');
  await page.fill('#fTitle', '验证门票');
  await page.click('#btnSaveDoc');
  await waitFor('doc2 卡片出现', () => page.locator('.doc-card').count().then((n) => n === 2).catch(() => false), 10000);
  const total = await page.locator('.doc-card').count();
  for (const d of ((await apiU.get('/api/trips/' + tripId + '/documents')).documents || [])) {
    cleaner.addDocument(d.id, d.url);
  }
  const chips = await page.locator('#filters button').allTextContents();
  console.log('doc2 OK: 2 cards. filter chips =', chips.map(s => s.replace(/\s+/g, '')).join(' | '));

  // filter counts
  const ticketBtn = page.locator('#filters button[data-f="ticket"]');
  const ticketTxt = (await ticketBtn.textContent()).trim();
  check(ticketTxt.includes('1'), 'ticket 筛选计数 1: ' + ticketTxt);

  await page.screenshot({ path: path.join(SHOTS, 'list.png'), fullPage: true });

  // ---- filter to passport ----
  console.log('--- filter ---');
  await page.click('#filters button[data-f="passport"]');
  await page.waitForTimeout(300);
  const filtered = await page.locator('.doc-card').count();
  check(filtered === 1, 'passport 筛选 1 张: ' + filtered);
  await page.click('#filters button[data-f="all"]');
  await page.waitForTimeout(300);
  console.log('filter OK');

  // ---- search ----
  console.log('--- search ---');
  await page.fill('#search', '门票');
  await page.waitForTimeout(300);
  const searched = await page.locator('.doc-card').count();
  check(searched === 1, '搜索命中 1 张: ' + searched);
  await page.fill('#search', '');
  await page.waitForTimeout(300);
  console.log('search OK');

  // ---- reminder toggle off on doc1 ----
  console.log('--- remind toggle ---');
  await page.locator('.doc-card', { hasText: '验证护照' }).locator('.rem-lab').click();
  await page.waitForTimeout(800);
  const remTxt2 = (await page.locator('.doc-card', { hasText: '验证护照' }).locator('.rem-lab').textContent()).trim();
  check(remTxt2.includes('提醒关'), '提醒切换为关: ' + remTxt2);
  const remNum2 = (await page.locator('#ovRemind').textContent()).trim();
  check(remNum2 === '0', '提醒统计归零: ' + remNum2);
  console.log('remind toggle OK (on -> off, overview synced)');

  // ---- edit doc2 title ----
  console.log('--- edit ---');
  const card2 = page.locator('.doc-card', { hasText: '验证门票' });
  await card2.hover();
  await card2.locator('.ed').click();
  await page.waitForSelector('#formPanel:not([hidden])');
  const prefill = await page.locator('#fTitle').inputValue();
  check(prefill === '验证门票', '编辑预填标题: ' + prefill);
  await page.fill('#fTitle', '验证门票（改）');
  await page.fill('#fExpired', dateOffset(-2)); // expired 2 days ago
  await page.click('#btnSaveDoc');
  await page.waitForTimeout(1200);
  const card2b = page.locator('.doc-card', { hasText: '验证门票（改）' });
  check((await card2b.count()) > 0, '编辑后标题渲染');
  const cls2 = await card2b.getAttribute('class');
  check(cls2.includes('expired'), '回填过期日 → .expired: ' + cls2);
  const overNum = (await page.locator('#ovOver').textContent()).trim();
  check(overNum === '1', '已过期统计 = 1: ' + overNum);
  console.log('edit OK: renamed + backdated -> expired band, ovOver=1');
  await page.screenshot({ path: path.join(SHOTS, 'states.png'), fullPage: true });

  // ---- preview ----
  console.log('--- preview ---');
  await page.locator('.doc-card', { hasText: '验证护照' }).locator('[data-view]').click();
  await page.waitForSelector('#pvMask:not([hidden])', { timeout: 3000 });
  const imgInPv = await page.locator('#pvBody img').count();
  check(imgInPv === 1, 'PNG 预览显示图片: ' + imgInPv);
  await page.click('#pvClose');
  await page.waitForTimeout(300);
  console.log('preview OK (img modal open/close)');

  // ---- delete both ----
  console.log('--- delete ---');
  page.once('dialog', d => d.accept());
  await page.locator('.doc-card', { hasText: '验证护照' }).hover();
  await page.locator('.doc-card', { hasText: '验证护照' }).locator('.del').click();
  await page.waitForTimeout(900);
  page.once('dialog', d => d.accept());
  await page.locator('.doc-card', { hasText: '验证门票（改）' }).hover();
  await page.locator('.doc-card', { hasText: '验证门票（改）' }).locator('.del').click();
  await page.waitForTimeout(900);
  const end = await page.locator('.doc-card').count();
  check(end === 0, '删除后清零: ' + end);
  console.log('delete OK: all cleaned');

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
