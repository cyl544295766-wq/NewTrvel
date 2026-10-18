/* Travel OS · map 页端到端验证
 * 覆盖：地图渲染 / 标点与路线 / 联动聚焦 / 底图切换 / 侧栏折叠 resize / 移动端抽屉
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const SHOTS = 'D:/tmp/map-shots';
fs.mkdirSync(SHOTS, { recursive: true });

function bail(msg) { throw new Error('FAIL: ' + msg); }
function ok(msg) { console.log('  OK  ' + msg); }

(async () => {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--no-sandbox', '--no-proxy-server', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({ viewport: { width: 1512, height: 900 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
    localStorage.setItem('travel-os-rail-collapsed', 'false');
  }, { token, user });

  const page = await ctx.newPage();
  const amapFails = [];
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('[console error]', m.text().slice(0, 220));
  });
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  page.on('requestfailed', (r) => {
    const u = r.url();
    const why = (r.failure() && r.failure().errorText) || '';
    // 视野变化时高德会主动取消旧瓦片请求（ERR_ABORTED），不是真失败
    if (/amap|autonavi/.test(u) && !/\/tile\//.test(u) && why !== 'net::ERR_ABORTED') {
      amapFails.push(u);
      console.log('[reqfail amap]', u.slice(0, 110), why);
    }
  });

  /* ---------- 1. 打开地图页 ---------- */
  console.log('--- 1. open /map/?trip=' + TRIP + ' ---');
  await page.goto(ORIGIN + '/map/?trip=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const s = document.getElementById('mapStage');
    return s && s.children.length > 0;
  }, { timeout: 45000 });
  ok('高德容器已挂载 (#mapStage 有子节点)');

  await page.waitForFunction(() => document.getElementById('veilLoad').hidden, { timeout: 45000 });
  if (await page.locator('#veilErr').isVisible()) {
    const t = (await page.locator('#errTitle').textContent()).trim();
    const s = (await page.locator('#errSub').textContent()).trim();
    bail('出现错误遮罩 → ' + t + ' | ' + s);
  }
  ok('加载完成，无错误遮罩');

  /* ---------- 2. 地图画布与瓦片 ---------- */
  const canvases = await page.locator('#mapStage canvas').count();
  const tileImgs = await page.locator('#mapStage img').count();
  console.log('  canvas =', canvases, '| tile <img> =', tileImgs);
  if (canvases === 0 && tileImgs === 0) bail('地图未渲染任何画布/瓦片');
  ok('地图画布就绪');

  /* ---------- 3. HUD 概览 ---------- */
  console.log('--- 3. HUD ---');
  const title = (await page.locator('#tcTitle').textContent()).trim();
  if (title !== '赤峰之旅') bail('行程标题不符: ' + title);
  const dates = (await page.locator('#tcDates').textContent()).trim();
  const points = (await page.locator('#stPoints').textContent()).trim();
  const km = (await page.locator('#stKm').textContent()).trim();
  const dur = (await page.locator('#stTime').textContent()).trim();
  console.log('  title =', title, '| dates =', dates);
  console.log('  points =', points, '| km =', km, '| dur =', dur);
  if (points !== '10') bail('地点数应为 10，实为 ' + points);
  if (km === '—' || dur === '—') bail('里程/时长未算出 → 驾车路线规划可能全部失败');
  if (!/\d/.test(km) || !/\d/.test(dur)) bail('里程/时长格式异常');
  const kmNum = parseFloat(km.replace(/[^\d.]/g, ''));
  if (!(kmNum > 400)) bail('总里程未包含跨天转场（应 > 400，实为 ' + km + '）');
  ok('概览：10 个地点 · ' + km + ' · ' + dur + '（含跨天转场）');

  /* ---------- 4. 每日面板 ---------- */
  console.log('--- 4. 每日面板 ---');
  const blocks = await page.locator('.day-block').count();
  const places = await page.locator('.pl').count();
  const markers = await page.locator('.mk-dot').count();
  console.log('  day blocks =', blocks, '| list places =', places, '| markers =', markers);
  if (blocks !== 3) bail('应 3 个行程天，实为 ' + blocks);
  if (places !== 10) bail('列表地点应 10 个，实为 ' + places);
  if (markers !== 10) bail('地图标点应 10 个，实为 ' + markers);
  const metas = await page.locator('.db-meta').allTextContents();
  metas.forEach((m, i) => console.log('  D' + (i + 1) + ' meta:', m.replace(/\s+/g, ' ').trim()));
  if (metas.some((m) => /路线计算中/.test(m))) bail('仍有天的路线未计算完');
  if (!metas.slice(1).every((m) => /转场/.test(m))) bail('第 2、3 天应显示跨天转场里程（点未连接）');
  ok('3 天 · 10 个地点 · 10 个标点，每天里程与跨天转场已算');

  const noticeVisible = await page.locator('#routeNotice').isVisible();
  console.log('  路线降级提示 =', noticeVisible ? '显示' : '隐藏');

  await page.screenshot({ path: path.join(SHOTS, 'desktop.png') });
  console.log('  shot -> desktop.png');

  /* ---------- 5. 地点联动 ---------- */
  console.log('--- 5. 地点联动定位 ---');
  await page.locator('.pl').first().click();
  await page.waitForTimeout(1000);
  const hotMarker = await page.locator('.mk-wrap.hot').count();
  const hotLi = await page.locator('.pl.is-hot').count();
  if (hotMarker !== 1) bail('点击列表项后应有 1 个高亮标点，实为 ' + hotMarker);
  if (hotLi !== 1) bail('列表项应高亮自身，实为 ' + hotLi);
  ok('点列表项 → 标点放大高亮 + 列表高亮');

  /* ---------- 6. 天聚焦 ---------- */
  console.log('--- 6. 按天聚焦 ---');
  await page.locator('.day-block').nth(1).locator('.db-head').click();
  await page.waitForTimeout(900);
  const focused = await page.locator('.day-block.is-focus').count();
  if (focused !== 1) bail('应有 1 个天块处于聚焦态，实为 ' + focused);
  const resetVisible = await page.locator('#dpReset').isVisible();
  if (!resetVisible) bail('聚焦后「重置聚焦」按钮应出现');
  ok('聚焦 D2 → 天块高亮 + 重置按钮出现');

  await page.screenshot({ path: path.join(SHOTS, 'focus-day.png') });

  await page.locator('#dpReset').click();
  await page.waitForTimeout(700);
  if (await page.locator('.day-block.is-focus').count() !== 0) bail('重置后不应有聚焦天块');
  ok('重置聚焦 → 恢复全览');

  /* ---------- 7. 底图切换 ---------- */
  console.log('--- 7. 底图切换 ---');
  await page.locator('#layerSeg button[data-layer="satellite"]').click();
  await page.waitForTimeout(9000);
  const satPressed = await page.locator('#layerSeg button[data-layer="satellite"]').getAttribute('aria-pressed');
  const normalPressed = await page.locator('#layerSeg button[data-layer="normal"]').getAttribute('aria-pressed');
  if (satPressed !== 'true' || normalPressed !== 'false') {
    bail('卫星态切换异常: sat=' + satPressed + ' normal=' + normalPressed);
  }
  ok('切换卫星底图 → aria-pressed 正确');
  await page.screenshot({ path: path.join(SHOTS, 'satellite.png') });

  await page.locator('#layerSeg button[data-layer="normal"]').click();
  await page.waitForTimeout(1200);
  if (await page.locator('#layerSeg button[data-layer="normal"]').getAttribute('aria-pressed') !== 'true') {
    bail('切回标准底图失败');
  }
  ok('切回标准底图');

  /* ---------- 8. 侧栏折叠 → 地图 resize ---------- */
  console.log('--- 8. 侧栏折叠 resize ---');
  const before = await page.evaluate(() => document.getElementById('mapStage').clientWidth);
  await page.locator('#railToggle').click();
  await page.waitForTimeout(900);
  const after = await page.evaluate(() => document.getElementById('mapStage').clientWidth);
  console.log('  map width:', before, '->', after);
  if (!(after > before + 100)) bail('折叠侧栏后地图未变宽: ' + before + ' -> ' + after);
  ok('折叠侧栏 → 地图自适应变宽');
  await page.screenshot({ path: path.join(SHOTS, 'rail-collapsed.png') });

  await page.locator('#railToggle').click();
  await page.waitForTimeout(700);

  /* ---------- 9. 移动端 ---------- */
  console.log('--- 9. 移动端 390×844 ---');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(1000);
  const fbtnVisible = await page.locator('#btnPanel').isVisible();
  if (!fbtnVisible) bail('移动端应显示「每日行程」开关');
  const panelOpenBefore = await page.locator('#dayPanel.open').count();
  if (panelOpenBefore !== 0) bail('移动端抽屉初始应收起');
  ok('移动端抽屉初始收起 + 开关可见');
  await page.screenshot({ path: path.join(SHOTS, 'mobile-closed.png') });

  await page.locator('#btnPanel').click();
  await page.waitForTimeout(900);
  if (await page.locator('#dayPanel.open').count() !== 1) bail('点击后抽屉未展开');
  const sheetBox = await page.locator('#dayPanel').boundingBox();
  if (!sheetBox || sheetBox.y < 100) bail('抽屉位置异常');
  ok('抽屉展开于底部（y=' + Math.round(sheetBox.y) + '）');
  await page.screenshot({ path: path.join(SHOTS, 'mobile-open.png') });

  await page.locator('#dpClose').click();
  await page.waitForTimeout(700);
  if (await page.locator('#dayPanel.open').count() !== 0) bail('抽屉未收起');
  ok('抽屉可收起');

  /* ---------- 10. 无参数进入 → 行程选择器 ---------- */
  console.log('--- 10. 参数缺失降级（行程选择器） ---');
  await page.setViewportSize({ width: 1512, height: 900 });
  await page.goto(ORIGIN + '/map/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#veilPick:not([hidden])', { timeout: 10000 });
  await page.waitForFunction(() => document.querySelectorAll('#tripSelect option').length > 0, { timeout: 10000 });
  const optCount = await page.locator('#tripSelect option').count();
  if (optCount === 0) bail('行程选择器没有选项');
  const firstOpt = (await page.locator('#tripSelect option').first().textContent()).trim();
  ok('无 trip 参数 → 显示行程选择器（' + optCount + ' 个选项，默认「' + firstOpt + '」）');
  // 选择首个行程打开 → 地图正常加载
  await page.selectOption('#tripSelect', { index: 0 });
  await page.locator('#btnOpenTrip').click();
  await page.waitForSelector('#dayList .day-block', { timeout: 60000 });
  ok('选择行程后地图就地加载（URL=' + page.url().split('?')[1] + '）');
  await page.screenshot({ path: path.join(SHOTS, 'no-param.png') });

  console.log('\namap 请求失败数 =', amapFails.length);
  await browser.close();
  console.log('\n=== ALL PASS ===');
})().catch((e) => {
  console.error('\n' + (e && e.message));
  process.exit(1);
});
