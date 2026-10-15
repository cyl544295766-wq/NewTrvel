/* Travel OS · 首页（总览）端到端验证 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/home-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }
function ok(msg) { console.log('  OK  ' + msg); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const H = { Authorization: 'Bearer ' + token };
  const dash = await (await fetch(ORIGIN + '/api/dashboard', { headers: H })).json();
  const s = dash.stats || {};

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- 1. 打开 /home/ ---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.hero-pass, .hero-empty', { timeout: 9000 });
  await page.waitForTimeout(700);
  ok('页面渲染完成（无「首页待落地」占位）');
  if ((await page.locator('text=首页待落地').count())) bail('仍是占位页');

  const greet = (await page.locator('#greet').textContent()).trim();
  if (!/好|夜深/.test(greet)) bail('问候语异常: ' + greet);
  const nm = user.displayName || user.username;
  if (!greet.includes(nm)) bail('问候语未包含用户名 ' + nm + ' → ' + greet);
  const today = (await page.locator('#today').textContent()).trim();
  if (!/\d{4}年\d{1,2}月\d{1,2}日/.test(today)) bail('日期异常: ' + today);
  ok('问候 + 日期：' + greet.replace(/\s+/g, ' ') + ' | ' + today.replace(/\s+/g, ' '));

  console.log('--- 2. 登机牌 Hero ---');
  if (!(await page.locator('.hero-pass').count())) bail('缺少登机牌 Hero');
  const apts = await page.locator('.hp-apt').allTextContents();
  const stamp = (await page.locator('.hp-stamp').textContent()).trim();
  const hpTitle = (await page.locator('.hp-title').textContent()).trim();
  const daysN = (await page.locator('.hp-days-n').textContent()).trim();
  if (apts.length !== 2) bail('航线应有 2 端，实为 ' + apts.length);
  ok('航线 ' + apts.join(' → ') + ' | 印章 ' + stamp + ' | ' + daysN + ' 天 | ' + hpTitle);

  console.log('--- 3. 统计四联（对比 /api/dashboard）---');
  const trips = (await page.locator('#stTrips').textContent()).trim();
  const days = (await page.locator('#stDays').textContent()).trim();
  const spent = (await page.locator('#stSpent').textContent()).trim();
  const packing = (await page.locator('#stPacking').textContent()).trim();
  if (trips !== String(s.tripCount)) bail(`旅行次数 期望 ${s.tripCount} 实为 ${trips}`);
  if (!days.startsWith(String(s.totalDays))) bail(`累计天数 期望 ${s.totalDays} 实为 ${days}`);
  if (!packing.startsWith(String(s.pendingPackingItemCount))) bail(`待打包 期望 ${s.pendingPackingItemCount} 实为 ${packing}`);
  if (!/^¥[\d,]+$/.test(spent)) bail('花费格式异常: ' + spent);
  ok(`旅行 ${trips} · 在途 ${days} · 花费 ${spent} · 待打包 ${packing}`);

  console.log('--- 4. 最近的旅行 ---');
  const rows = await page.locator('.trip-row').count();
  if (rows !== dash.recentTrips.length) bail(`最近旅行应 ${dash.recentTrips.length} 条，实为 ${rows}`);
  const statuses = await page.locator('.trip-status').allTextContents();
  ok(rows + ' 条 | 状态章 ' + statuses.join(', '));
  const expect = dash.recentTrips.map(t => t.status);
  if (expect.includes('archived') && !statuses.includes('ARCHIVED')) {
    bail('archived 行程回落了（应渲染 ARCHIVED）: ' + statuses.join(','));
  }
  if (expect.includes('completed') && !statuses.includes('ARRIVED')) {
    bail('completed 行程回落了（应渲染 ARRIVED）: ' + statuses.join(','));
  }
  ok('状态映射正确（未回落到 DRAFT）');

  console.log('--- 5. 最近照片 + 快捷入口 ---');
  const cells = await page.locator('.photo-cell').count();
  if (cells !== dash.recentPhotos.length) bail(`照片应 ${dash.recentPhotos.length} 张，实为 ${cells}`);
  const imgsLoaded = await page.locator('.photo-cell img').count();
  ok('照片 ' + cells + ' 张（含 img ' + imgsLoaded + '）');
  const quick = await page.locator('.quick').count();
  if (quick !== 8) bail('快捷入口应 8 个，实为 ' + quick);
  ok('快捷入口 8 个');

  await page.screenshot({ path: path.join(SHOTS, 'desktop.png'), fullPage: true });
  console.log('  shot -> desktop.png');

  console.log('--- 6. 交互：点旅行行跳详情 ---');
  await page.locator('.trip-row').first().click();
  await page.waitForURL(/\/trip-detail\/\?id=/, { timeout: 9000 });
  ok('已跳转 ' + new URL(page.url()).pathname);

  console.log('--- 7. 侧栏折叠 / 移动端 ---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(600);
  await page.locator('#railToggle').click();
  await page.waitForTimeout(600);
  if (!(await page.locator('.shell[data-collapsed="true"]').count())) bail('侧栏折叠未生效');
  await page.screenshot({ path: path.join(SHOTS, 'rail-collapsed.png') });
  await page.locator('#railToggle').click();
  await page.waitForTimeout(400);
  ok('侧栏折叠正常');

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(800);
  const cols = await page.locator('.cols').evaluate(el => getComputedStyle(el).gridTemplateColumns);
  ok('移动端两列已堆叠为单列：' + cols);
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('  shot -> mobile.png');

  await browser.close();
  console.log('\n=== ALL PASS ===');
}

main().catch(e => bail(e.stack || String(e)));
