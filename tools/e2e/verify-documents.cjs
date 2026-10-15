// Verify documents page: upload / filter / search / remind toggle / edit / preview / delete
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/documents-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

function dateOffset(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });
  const PNG = 'D:/tmp/test-doc.png';

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  const created = [];

  console.log('--- load documents (empty) ---');
  await page.goto(ORIGIN + '/documents/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  const ov0 = (await page.locator('#ovTotal').textContent()).trim();
  if (ov0 !== '0') bail('expected 0 docs, got ' + ov0);
  console.log('empty state OK');

  // ---- upload doc 1: passport, expiring in 3 days, reminder on ----
  console.log('--- upload doc1 (soon expiry) ---');
  await page.click('#btnToggleForm');
  await page.waitForSelector('#formPanel:not([hidden])');
  await page.setInputFiles('#fileInput', PNG);
  await page.waitForSelector('#fileChip.show', { timeout: 5000 });
  const chipName = (await page.locator('#chipName').textContent()).trim();
  if (chipName !== 'test-doc.png') bail('file chip name wrong: ' + chipName);
  await page.click('#typeChips button[data-t="passport"]');
  await page.fill('#fTitle', '验证护照');
  await page.fill('#fExpired', dateOffset(3));
  await page.click('#fReminder');
  await page.fill('#fNotes', '签证号 G12345');
  await page.click('#btnSaveDoc');
  await page.waitForSelector('.doc-card', { timeout: 8000 });
  await page.waitForTimeout(500);
  created.push('doc1');

  const card1 = page.locator('.doc-card', { hasText: '验证护照' });
  if (!(await card1.count())) bail('doc1 card missing');
  const cls1 = await card1.getAttribute('class');
  if (!cls1.includes('soon')) bail('doc1 should have .soon (3-day expiry), got: ' + cls1);
  const remTxt = (await card1.locator('.rem-lab').textContent()).trim();
  if (!remTxt.includes('提醒开')) bail('doc1 reminder should be on, got: ' + remTxt);
  const soonNum = (await page.locator('#ovSoon').textContent()).trim();
  if (soonNum !== '1') bail('ovSoon expected 1, got ' + soonNum);
  const remNum = (await page.locator('#ovRemind').textContent()).trim();
  if (remNum !== '1') bail('ovRemind expected 1, got ' + remNum);
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
  await page.waitForTimeout(1200);
  const total = await page.locator('.doc-card').count();
  if (total !== 2) bail('expected 2 cards, got ' + total);
  created.push('doc2');
  const chips = await page.locator('#filters button').allTextContents();
  console.log('doc2 OK: 2 cards. filter chips =', chips.map(s => s.replace(/\s+/g, '')).join(' | '));

  // filter counts
  const ticketBtn = page.locator('#filters button[data-f="ticket"]');
  const ticketTxt = (await ticketBtn.textContent()).trim();
  if (!ticketTxt.includes('1')) bail('ticket filter count expected 1, got: ' + ticketTxt);

  await page.screenshot({ path: path.join(SHOTS, 'list.png'), fullPage: true });

  // ---- filter to passport ----
  console.log('--- filter ---');
  await page.click('#filters button[data-f="passport"]');
  await page.waitForTimeout(300);
  const filtered = await page.locator('.doc-card').count();
  if (filtered !== 1) bail('passport filter expected 1 card, got ' + filtered);
  await page.click('#filters button[data-f="all"]');
  await page.waitForTimeout(300);
  console.log('filter OK');

  // ---- search ----
  console.log('--- search ---');
  await page.fill('#search', '门票');
  await page.waitForTimeout(300);
  const searched = await page.locator('.doc-card').count();
  if (searched !== 1) bail('search expected 1 card, got ' + searched);
  await page.fill('#search', '');
  await page.waitForTimeout(300);
  console.log('search OK');

  // ---- reminder toggle off on doc1 ----
  console.log('--- remind toggle ---');
  await page.locator('.doc-card', { hasText: '验证护照' }).locator('.rem-lab').click();
  await page.waitForTimeout(800);
  const remTxt2 = (await page.locator('.doc-card', { hasText: '验证护照' }).locator('.rem-lab').textContent()).trim();
  if (!remTxt2.includes('提醒关')) bail('reminder should be off now, got: ' + remTxt2);
  const remNum2 = (await page.locator('#ovRemind').textContent()).trim();
  if (remNum2 !== '0') bail('ovRemind expected 0, got ' + remNum2);
  console.log('remind toggle OK (on -> off, overview synced)');

  // ---- edit doc2 title ----
  console.log('--- edit ---');
  const card2 = page.locator('.doc-card', { hasText: '验证门票' });
  await card2.hover();
  await card2.locator('.ed').click();
  await page.waitForSelector('#formPanel:not([hidden])');
  const prefill = await page.locator('#fTitle').inputValue();
  if (prefill !== '验证门票') bail('edit prefill title wrong: ' + prefill);
  await page.fill('#fTitle', '验证门票（改）');
  await page.fill('#fExpired', dateOffset(-2)); // expired 2 days ago
  await page.click('#btnSaveDoc');
  await page.waitForTimeout(1200);
  const card2b = page.locator('.doc-card', { hasText: '验证门票（改）' });
  if (!(await card2b.count())) bail('edited title not rendered');
  const cls2 = await card2b.getAttribute('class');
  if (!cls2.includes('expired')) bail('doc2 should be .expired after backdating, got: ' + cls2);
  const overNum = (await page.locator('#ovOver').textContent()).trim();
  if (overNum !== '1') bail('ovOver expected 1, got ' + overNum);
  console.log('edit OK: renamed + backdated -> expired band, ovOver=1');
  await page.screenshot({ path: path.join(SHOTS, 'states.png'), fullPage: true });

  // ---- preview ----
  console.log('--- preview ---');
  await page.locator('.doc-card', { hasText: '验证护照' }).locator('[data-view]').click();
  await page.waitForSelector('#pvMask:not([hidden])', { timeout: 3000 });
  const imgInPv = await page.locator('#pvBody img').count();
  if (imgInPv !== 1) bail('preview should show img for png');
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
  if (end !== 0) bail('expected 0 cards after deletes, got ' + end);
  console.log('delete OK: all cleaned');

  await browser.close();
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
