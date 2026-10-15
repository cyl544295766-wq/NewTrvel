// Verify packing page: create (template items) / add / toggle / rename / duplicate / delete + cleanup
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/packing-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  const createdListIds = [];

  console.log('--- load packing (empty state) ---');
  await page.goto(ORIGIN + '/packing/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.empty-state', { timeout: 8000 });
  const ovLists0 = (await page.locator('#ovLists').textContent()).trim();
  if (ovLists0 !== '0') bail('expected 0 lists, got ' + ovLists0);
  console.log('empty state OK');

  // ---- create clothing list -> template items ----
  console.log('--- create list (clothing) ---');
  await page.selectOption('#newCat', 'clothing');
  await page.fill('#newName', '验证衣物清单');
  await page.click('#btnNewList');
  await page.waitForSelector('.list-card', { timeout: 8000 });
  await page.waitForTimeout(400);
  const card = page.locator('.list-card').first();
  const items = await card.locator('.it-row').count();
  if (items !== 4) bail('clothing template expected 4 items (上衣/裤子/内衣/袜子), got ' + items);
  const firstNm = (await card.locator('.it-nm').first().textContent()).trim();
  if (firstNm !== '上衣') bail('first template item expected 上衣, got ' + firstNm);
  const catIc = (await card.locator('.cat-nm').textContent()).trim();
  if (catIc !== '衣物') bail('stub category label expected 衣物, got ' + catIc);
  console.log('create OK: 4 template items, first =', firstNm);
  await page.screenshot({ path: path.join(SHOTS, 'created.png'), fullPage: true });

  // capture list id for cleanup
  const listId = await card.getAttribute('data-list');
  createdListIds.push(listId);

  // ---- add custom item with qty ----
  console.log('--- add item ---');
  const addBox = card.locator('.add-item');
  await addBox.locator('input[type="text"]').first().fill('冲锋衣');
  await addBox.locator('.q').fill('2');
  await addBox.locator('button').click();
  await page.waitForTimeout(900);
  const card2 = page.locator('.list-card').first();
  const items2 = await card2.locator('.it-row').count();
  if (items2 !== 5) bail('expected 5 items after add, got ' + items2);
  const newRow = card2.locator('.it-row', { hasText: '冲锋衣' });
  const qty = (await newRow.locator('.qty').textContent().catch(() => '')).trim();
  if (qty !== '×2') bail('qty badge expected ×2, got ' + qty);
  console.log('add OK: 5 items, qty badge', qty);

  // ---- toggle 2 items packed ----
  console.log('--- toggle packed ---');
  await card2.locator('.it-row').nth(0).locator('.chk').click();
  await page.waitForTimeout(700);
  const c3 = page.locator('.list-card').first();
  await c3.locator('.it-row').nth(1).locator('.chk').click();
  await page.waitForTimeout(700);
  const c4 = page.locator('.list-card').first();
  const doneCount = await c4.locator('.it-row.done').count();
  if (doneCount !== 2) bail('expected 2 done rows, got ' + doneCount);
  const ringNum = (await c4.locator('.ring .num').textContent()).trim();
  if (ringNum !== '40%') bail('ring expected 40% (2/5), got ' + ringNum);
  const barW = await c4.locator('.l-bar i').evaluate(el => el.style.width);
  if (barW !== '40%') bail('bar width expected 40%, got ' + barW);
  const cnt = (await c4.locator('.l-cnt').textContent()).trim();
  if (!cnt.includes('2 已打包')) bail('head cnt expected 2 已打包, got ' + cnt);
  console.log('toggle OK: ring/bar/cnt all 40%, 2/5');
  await page.screenshot({ path: path.join(SHOTS, 'packed-40.png'), fullPage: true });

  // ---- pack all -> READY stamp ----
  console.log('--- pack all -> READY ---');
  for (let i = 2; i < 5; i++) {
    const cc = page.locator('.list-card').first();
    await cc.locator('.it-row').nth(i).locator('.chk').click();
    await page.waitForTimeout(500);
  }
  const c5 = page.locator('.list-card').first();
  if (!(await c5.locator('.ready-stamp').count())) bail('READY stamp missing at 100%');
  const ring100 = (await c5.locator('.ring .num').textContent()).trim();
  if (ring100 !== '100%') bail('ring expected 100%, got ' + ring100);
  console.log('READY stamp OK at 100%');
  await page.screenshot({ path: path.join(SHOTS, 'ready.png'), fullPage: false });

  // ---- rename ----
  console.log('--- rename ---');
  const title = c5.locator('.l-title');
  await title.fill('验证衣物清单（改）');
  await title.press('Enter');
  await page.waitForTimeout(800);
  const tv = (await page.locator('.list-card').first().locator('.l-title').inputValue()).trim();
  if (tv !== '验证衣物清单（改）') bail('rename failed: ' + tv);
  console.log('rename OK');

  // ---- duplicate ----
  console.log('--- duplicate ---');
  await page.locator('.list-card').first().locator('.l-ops .dup').click();
  await page.waitForTimeout(1200);
  const cardsN = await page.locator('.list-card').count();
  if (cardsN !== 2) bail('expected 2 cards after duplicate, got ' + cardsN);
  const dupId = await page.locator('.list-card').nth(1).getAttribute('data-list');
  createdListIds.push(dupId);
  console.log('duplicate OK: 2 cards');

  // ---- delete duplicate ----
  console.log('--- delete duplicate ---');
  page.once('dialog', d => d.accept());
  await page.locator('.list-card').nth(1).locator('.l-ops .del').click();
  await page.waitForTimeout(1000);
  const cardsEnd = await page.locator('.list-card').count();
  if (cardsEnd !== 1) bail('expected 1 card after delete, got ' + cardsEnd);
  console.log('delete OK: back to 1 card');

  // mobile
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();

  // ---- cleanup ----
  console.log('--- cleanup lists ---');
  for (const id of createdListIds) {
    const res = await fetch(ORIGIN + '/api/trips/' + TRIP + '/packing-lists/' + id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }, credentials: 'omit'
    });
    console.log('delete list', id, res.status);
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
