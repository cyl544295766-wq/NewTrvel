// Travel OS · verify-packing：打包清单页端到端验证（自包含夹具）
// 覆盖：空态 / 模板建单（衣物 4 项）/ 加自定义项（数量徽标）/ 勾选进度（环/条/计数）
//       / 全部打包 → READY 印章 / 重命名 / 复制 / 删除 / 移动端截图。
// 夹具：自注册用户 + 自建行程；清单经页面创建，结束按准确 ID 清理并核验残留。
// 运行：NODE_PATH=<managed node_modules> node tools/e2e/verify-packing.cjs
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/packing-shots';
assertLocalOrigin(ORIGIN);

const cleaner = new Cleaner();
let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

let browser = null;
let tripId = null;
const createdListIds = [];

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册用户 + 行程 ----
  const user = await registerUser(ORIGIN, 'pk');
  cleaner.addUser(user.id);
  const apiU = api(ORIGIN, user.token);
  tripId = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '打包验证行程（临时）', destination: '清单港', startDate: '2026-07-01', endDate: '2026-07-03'
  })).trip.id);
  ok('夹具就绪 trip=' + tripId);

  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token: user.token, u: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));

  console.log('--- 空态 ---');
  await page.goto(ORIGIN + '/packing/?trip=' + tripId, { waitUntil: 'domcontentloaded' });
  await waitFor('空态出现', () => page.locator('.empty-state').isVisible().catch(() => false), 10000);
  const ovLists0 = (await page.locator('#ovLists').textContent()).trim();
  check(ovLists0 === '0', '清单数为 0: ' + ovLists0);
  ok('空态正确');

  // ---- 模板建单 ----
  console.log('--- 模板建单（衣物） ---');
  await page.selectOption('#newCat', 'clothing');
  await page.fill('#newName', '验证衣物清单');
  await page.click('#btnNewList');
  await waitFor('清单卡出现', () => page.locator('.list-card').count().then((n) => n === 1).catch(() => false), 10000);
  const card = page.locator('.list-card').first();
  await waitFor('模板 4 项渲染', () => card.locator('.it-row').count().then((n) => n === 4).catch(() => false), 8000);
  const firstNm = (await card.locator('.it-nm').first().textContent()).trim();
  check(firstNm === '上衣', '模板首项 = 上衣: ' + firstNm);
  const catIc = (await card.locator('.cat-nm').textContent()).trim();
  check(catIc === '衣物', '分类标签 = 衣物: ' + catIc);
  await page.screenshot({ path: path.join(SHOTS, 'created.png'), fullPage: true });
  const listId = await card.getAttribute('data-list');
  createdListIds.push(listId);
  cleaner.addPackingList(listId);

  // ---- 加自定义项 ----
  console.log('--- 加自定义项 ---');
  const addBox = card.locator('.add-item');
  await addBox.locator('input[type="text"]').first().fill('冲锋衣');
  await addBox.locator('.q').fill('2');
  await addBox.locator('button').click();
  await waitFor('项目数变 5', () => page.locator('.list-card').first().locator('.it-row').count()
    .then((n) => n === 5).catch(() => false), 8000);
  const newRow = page.locator('.list-card').first().locator('.it-row', { hasText: '冲锋衣' });
  const qty = ((await newRow.locator('.qty').textContent().catch(() => '')) || '').trim();
  check(qty === '×2', '数量徽标 ×2: ' + qty);
  ok('加项后 5 项');

  // ---- 勾选 2 项 ----
  console.log('--- 勾选打包 ---');
  await page.locator('.list-card').first().locator('.it-row').nth(0).locator('.chk').click();
  await waitFor('进度 1/5', () => page.locator('.list-card').first().locator('.it-row.done').count()
    .then((n) => n === 1).catch(() => false), 8000);
  await page.locator('.list-card').first().locator('.it-row').nth(1).locator('.chk').click();
  await waitFor('进度 2/5', () => page.locator('.list-card').first().locator('.it-row.done').count()
    .then((n) => n === 2).catch(() => false), 8000);
  const c4 = page.locator('.list-card').first();
  const ringNum = (await c4.locator('.ring .num').textContent()).trim();
  const barW = await c4.locator('.l-bar i').evaluate((el) => el.style.width);
  const cnt = (await c4.locator('.l-cnt').textContent()).trim();
  check(ringNum === '40%' && barW === '40%' && cnt.includes('2 已打包'),
    '环/条/计数均 40% 与 2 已打包（环=' + ringNum + ' 条=' + barW + ' 计数=' + cnt + '）');
  await page.screenshot({ path: path.join(SHOTS, 'packed-40.png'), fullPage: true });

  // ---- 全部打包 → READY 印章 ----
  console.log('--- 全部打包 → READY ---');
  for (let i = 2; i < 5; i++) {
    await page.locator('.list-card').first().locator('.it-row').nth(i).locator('.chk').click();
    await waitFor('进度 ' + (i + 1) + '/5', () => page.locator('.list-card').first().locator('.it-row.done').count()
      .then((n) => n === i + 1).catch(() => false), 8000);
  }
  const c5 = page.locator('.list-card').first();
  check((await c5.locator('.ready-stamp').count()) === 1, '100% 出现 READY 印章');
  check((await c5.locator('.ring .num').textContent()).trim() === '100%', '环 100%');
  await page.screenshot({ path: path.join(SHOTS, 'ready.png'), fullPage: false });

  // ---- 重命名（真实保存） ----
  console.log('--- 重命名 ---');
  const title = c5.locator('.l-title');
  await title.fill('验证衣物清单（改）');
  await title.press('Enter');
  await waitFor('重命名生效（页面）', () => page.locator('.list-card').first().locator('.l-title')
    .inputValue().then((v) => v === '验证衣物清单（改）').catch(() => false), 8000);
  // 真实保存核验：重新拉取 API 确认服务端已改名
  await waitFor('重命名落库（API 确认）', () => apiU.get('/api/trips/' + tripId + '/packing-lists')
    .then((r) => JSON.stringify(r).includes('验证衣物清单（改）')).catch(() => false), 8000);
  ok('重命名已落库');

  // ---- 复制 ----
  console.log('--- 复制 ---');
  await page.locator('.list-card').first().locator('.l-ops .dup').click();
  await waitFor('卡片变 2', () => page.locator('.list-card').count().then((n) => n === 2).catch(() => false), 10000);
  const dupId = await page.locator('.list-card').nth(1).getAttribute('data-list');
  createdListIds.push(dupId);
  cleaner.addPackingList(dupId);
  ok('复制出第 2 张清单');

  // ---- 删除复制件 ----
  console.log('--- 删除复制件 ---');
  page.once('dialog', (d) => d.accept());
  await page.locator('.list-card').nth(1).locator('.l-ops .del').click();
  await waitFor('卡片回到 1', () => page.locator('.list-card').count().then((n) => n === 1).catch(() => false), 10000);
  ok('删除后回到 1 张');

  await page.setViewportSize({ width: 390, height: 844 });
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
