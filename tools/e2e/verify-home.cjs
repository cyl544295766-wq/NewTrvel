/* Travel OS · 首页（/home/）端到端验证
 *
 * 自包含：脚本自行 seed 一份临时行程（未来出发 + 3 个带坐标的地点 + 1 笔花费
 * + 3 张照片 + 1 篇游记），验证页面后**硬删除**全部测试数据，不留残留。
 *
 * 运行：
 *   NODE_PATH=<managed node_modules> node tools/e2e/verify-home.cjs
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { execSync } = require('child_process');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/home-shots';
const PG = 'docker exec travel-os-postgres psql -U travel_os -d travel_os -t -A -c';

let failures = 0;
function bail(msg) { console.error('\n[FAIL] ' + msg); process.exit(1); }
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) { ok(msg); } else { failures++; console.error('  !!  ' + msg); } }
function psql(sql) {
  return execSync(`${PG} "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' }).trim();
}
function ymd(offsetDays) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

/* ---------- 生成一张纯色 PNG（避免依赖外部素材） ---------- */
function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) {
    crc ^= buf[i];
    for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
function solidPng(size, rgb) {
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y++) {
    const off = y * (size * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < size; x++) {
      raw[off + 1 + x * 3] = rgb[0];
      raw[off + 2 + x * 3] = rgb[1];
      raw[off + 3 + x * 3] = rgb[2];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
    const t = Buffer.from(type, 'ascii');
    const c = Buffer.alloc(4); c.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
    return Buffer.concat([len, t, data, c]);
  };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const dataUrl = (buf) => 'data:image/png;base64,' + buf.toString('base64');

/* ---------- main ---------- */
(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });

  const login = await (await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })).json();
  const token = login.token;
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };
  const jpost = async (p, body) => {
    const r = await fetch(ORIGIN + p, { method: 'POST', headers: H, body: JSON.stringify(body) });
    const t = await r.text();
    if (!r.ok) bail('POST ' + p + ' -> ' + r.status + ' ' + t.slice(0, 160));
    return t ? JSON.parse(t) : {};
  };
  const jget = async (p) => (await (await fetch(ORIGIN + p, { headers: H })).json());

  const me = await jget('/api/auth/me');
  const adminId = me.user.id;

  /* ================= seed ================= */
  console.log('--- seed ---');
  const TITLE = '首页验证行程（临时）';
  const DEST = '阿斯哈图';
  const tripRes = (await jpost('/api/trips', {
    title: TITLE, destination: DEST,
    startDate: ymd(12), endDate: ymd(16),
    description: '首页 e2e 临时数据'
  })).trip;
  const tripId = tripRes && tripRes.id;
  if (!tripId) bail('trip seed failed: ' + JSON.stringify(tripRes).slice(0, 200));
  console.log('  tripId =', tripId);

  await jpost(`/api/trips/${tripId}/days/generate`, {});
  const days = (await jget(`/api/trips/${tripId}/days`)).days || [];
  if (days.length < 3) bail('days generate failed, got ' + days.length);

  const PLACES = [
    { day: 0, name: '阿斯哈图石林', type: 'attraction', lng: 117.53, lat: 43.63, address: '克什克腾旗' },
    { day: 1, name: '达里诺尔湖', type: 'attraction', lng: 116.62, lat: 43.29, address: '克什克腾旗西部' },
    { day: 2, name: '乌兰布统草原', type: 'attraction', lng: 117.42, lat: 42.45, address: '克什克腾旗南部' }
  ];
  for (const p of PLACES) {
    await jpost(`/api/trips/${tripId}/places`, {
      tripDayId: days[p.day].id, name: p.name, type: p.type,
      address: p.address, longitude: p.lng, latitude: p.lat
    });
  }
  console.log('  places =', PLACES.length);

  const EXP_TITLE = '首页验证·门票支出';
  await jpost(`/api/trips/${tripId}/expenses`, {
    amount: '480', category: 'ticket', title: EXP_TITLE,
    payerUserId: adminId, shareUserIds: [adminId]
  });

  const photos = [];
  for (let i = 0; i < 3; i++) {
    const res = await jpost(`/api/trips/${tripId}/photos`, {
      url: dataUrl(solidPng(240, [13 + i * 60, 110, 110 + i * 20])),
      caption: '首页验证照片 ' + (i + 1),
      shotAt: ymd(12 + i) + ' 10:00:00'
    });
    photos.push((res.photo || res).id);
  }
  console.log('  photos =', photos.length);

  const J_TITLE = '首页验证·林间的第一天';
  await jpost(`/api/trips/${tripId}/journals`, {
    title: J_TITLE,
    content: '早上六点从帐篷里钻出来，石林的轮廓还浸在雾里。风从北面吹过来，带着草和松脂的气味。',
    mood: 'happy', isDraft: false, photoIds: photos.slice(0, 1)
  });
  console.log('  journal seeded');

  /* 期望值直接取自 API，避免硬编码 */
  const dash = await jget('/api/dashboard');
  const ov = await jget('/api/stats/overview');
  const expected = {
    trips: String(dash.stats.tripCount),
    days: String(dash.stats.totalDays),
    spent: Number(dash.stats.totalExpenseAmount).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 0 }),
    packing: String(dash.stats.pendingPackingItemCount)
  };
  console.log('  expected =', JSON.stringify(expected), '| mostVisited =', JSON.stringify(ov.mostVisitedDestination));

  /* ================= 浏览器 ================= */
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user: login.user || { username: 'admin' } });

  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/favicon/.test(m.text())) errors.push('console: ' + m.text()); });

  console.log('--- 打开 /home/ ---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.trip-row', { timeout: 15000 });
  await page.waitForFunction(() => !document.querySelector('#expBody .skeleton'), { timeout: 15000 });
  await page.waitForTimeout(900);

  /* ---- Hero ---- */
  const greet = (await page.locator('#greet').textContent()).trim();
  check(/好，/.test(greet), '问候语渲染：' + greet);
  const sub = (await page.locator('#heroSub').textContent()).trim();
  check(/月\d+日\s*周/.test(sub), '日期副标：' + sub);

  const cdNum = (await page.locator('.cd-num').textContent()).replace(/\s+/g, ' ').trim();
  check(/^12\s*天$/.test(cdNum), '倒计时（+12 天出发）：' + cdNum);
  const cdDest = (await page.locator('.cd-dest').textContent()).trim();
  check(cdDest.includes(DEST) && cdDest.includes('出发'), '倒计时行程名与出发日：' + cdDest);

  check((await page.locator('#hrTrips').textContent()).trim() === expected.trips, '本年度段数 = ' + expected.trips);
  check((await page.locator('#hrDays').textContent()).trim() === expected.days, '在路上天数 = ' + expected.days);
  const far = (await page.locator('#hrFarthest').textContent()).trim();
  check(far.length > 0 && far !== '—', '最远抵达：' + far);

  /* ---- 统计四联 ---- */
  check((await page.locator('#stTrips').textContent()).trim() === expected.trips, '统计·旅行次数 = ' + expected.trips);
  check((await page.locator('#stSpent').textContent()).trim() === expected.spent, '统计·累计花费 = ' + expected.spent);
  check((await page.locator('#stDays').textContent()).trim() === expected.days, '统计·累计天数 = ' + expected.days);
  check((await page.locator('#stPacking').textContent()).trim() === expected.packing, '统计·待打包 = ' + expected.packing);
  check(await page.locator('.stat .foot').first().isVisible(), '统计卡脚注可见');

  /* ---- 行程地图（真实高德底图） ---- */
  check((await page.locator('#mapTrip').textContent()).trim() === TITLE, '地图面板副标 = 行程名');
  check((await page.locator('#mapLink').getAttribute('href')).includes('/map/?trip=' + tripId), '「完整地图」带行程参数');
  /* AMap 就绪：容器显示、canvas 就绪、标点渲染为 AMap marker 的自定义 DOM */
  await page.waitForFunction(() => {
    const host = document.getElementById('mapAmap');
    return host && !host.hidden && host.querySelector('.amap-maps, canvas, .amap-container');
  }, { timeout: 30000 });
  check((await page.locator('#mapLoading').isHidden()), '加载态已隐藏');
  await page.waitForTimeout(1200); /* 等 setFitView 完成 */
  const pins = await page.locator('#mapAmap .pin').count();
  check(pins === 3, '地图标点数（AMap marker）= ' + pins);
  const pinTags = await page.locator('#mapAmap .pin .tag').count();
  check(pinTags === 3, '标点名称标签数 = ' + pinTags);
  const firstTag = (await page.locator('#mapAmap .pin .tag').first().textContent()).trim();
  check(firstTag === PLACES[0].name, '标点名称渲染真实地点：' + firstTag);
  const isDense = await page.locator('#mapCanvas').evaluate((el) => el.classList.contains('dense'));
  check(!isDense, '3 个标点不启用紧凑模式（名称常显，与设计稿一致）');
  check((await page.locator('#mapBadgeT').textContent()).trim() === 'DAY 1 — 3', '地图徽标：DAY 1 — 3');
  const badgeD = (await page.locator('#mapBadgeD').textContent()).trim();
  check(badgeD.includes(PLACES[0].name) && badgeD.includes(PLACES[2].name), '徽标路线摘要：' + badgeD);
  check(await page.locator('#mapBadge').isVisible(), '地图徽标可见');
  check((await page.locator('#mapHint').isHidden()) === false, '连线示意提示可见');
  /* 卫星切换：真实图层显示/隐藏（经 AMap 实例状态验证） */
  await page.locator('.map-ctl span[data-layer="satellite"]').click();
  await page.waitForTimeout(2500); /* 等卫星瓦片开始渲染 */
  check((await page.locator('#mapCanvas').getAttribute('data-layer')) === 'satellite', '底图切到卫星');
  check((await page.locator('.map-ctl span[data-layer="satellite"]').getAttribute('data-on')) === 'true', '卫星按钮选中态');
  /* 卫星图层节点被 show()：存在且非 display:none */
  const satVisible = await page.evaluate(() => {
    const sat = document.querySelector('#mapAmap .amap-layers .amap-layer');
    return !!sat;
  });
  check(satVisible, 'AMap 图层容器存在（卫星/路网 TileLayer 已挂载）');
  await page.screenshot({ path: path.join(SHOTS, 'map-satellite.png'), clip: { x: 0, y: await page.locator('.map-canvas').evaluate(el => el.getBoundingClientRect().top + window.scrollY) - 8, width: 1280, height: 430 } }).catch(() => {});
  await page.locator('.map-ctl span[data-layer="standard"]').click();
  await page.waitForTimeout(300);
  check((await page.locator('#mapCanvas').getAttribute('data-layer')) === 'standard', '底图切回标准');

  /* ---- 行程速览 ---- */
  const tripRows = await page.locator('#tripBody .trip-row').count();
  check(tripRows >= 1, '行程速览行数 = ' + tripRows);
  const firstNm = (await page.locator('#tripBody .trip-row').first().locator('.nm').textContent()).trim();
  check(firstNm.includes(TITLE), '首行是最新行程：' + firstNm);
  const firstDt = (await page.locator('#tripBody .trip-row').first().locator('.dt').textContent()).trim();
  check(/\d{2}\/\d{2} – \d{2}\/\d{2} · 5 天/.test(firstDt), '行程日期行：' + firstDt);
  const stPill = (await page.locator('#tripBody .trip-row').first().locator('.st').textContent()).trim();
  check(stPill === '草稿', '状态胶囊（新建即草稿）：' + stPill);
  const thumbSvg = await page.locator('#tripBody .trip-row').first().locator('.thumb svg').count();
  check(thumbSvg === 1, '行程缩略 3D 图标已渲染（svg × ' + thumbSvg + '）');
  check((await page.locator('#tripBody .trip-row').first().getAttribute('role')) === 'link', '行程行可访问（role=link）');

  /* ---- 最近花费 ---- */
  const expRows = await page.locator('#expBody .exp-row').count();
  check(expRows >= 1, '花费行数 = ' + expRows);
  check((await page.locator('#expBody .exp-row').first().locator('.ttl').textContent()).trim() === EXP_TITLE, '花费标题正确');
  const catTxt = (await page.locator('#expBody .exp-row').first().locator('.cat').textContent()).trim();
  check(catTxt.startsWith('门票 · '), '花费分类+日期：' + catTxt);
  const amtTxt = (await page.locator('#expBody .exp-row').first().locator('.amt').textContent()).trim();
  check(/480/.test(amtTxt), '花费金额：' + amtTxt);
  check((await page.locator('#expLink').getAttribute('href')).includes('/expenses/?trip=' + tripId), '「全部账目」带行程参数');

  /* ---- 最近照片 ---- */
  const photoCount = await page.locator('#photoBody .photo').count();
  check(photoCount >= 3, '照片格数 = ' + photoCount + '（本次 seed 3 张，含库中既有照片）');
  const bgs = await page.locator('#photoBody .photo').evaluateAll((els) => els.map((el) => getComputedStyle(el).backgroundImage));
  check(bgs.filter((b) => /data:image\//.test(b)).length === bgs.length, '全部照片以真实缩略图为背景（' + bgs.length + '/' + bgs.length + '）');
  check(bgs.some((b) => /data:image\//.test(b)), '缩略图已内联渲染');
  const seededShown = await page.locator('#photoBody .photo[title^="首页验证照片"]').count();
  check(seededShown >= 3, '本次 seed 的照片已展示 ' + seededShown + ' 张');
  const own = (await page.locator('#photoBody .photo[title^="首页验证照片"]').first().locator('.own').textContent()).trim();
  check(own === TITLE, '照片左上角行程归属：' + own);
  check((await page.locator('#photoLink').getAttribute('href')).includes('/photos/?trip='), '「全部照片」带行程参数');

  /* ---- 最近游记 ---- */
  const jCards = await page.locator('#journalBody .journal-card').count();
  check(jCards === 1, '游记卡数 = ' + jCards);
  check((await page.locator('#journalBody .nm').first().textContent()).trim() === J_TITLE, '游记标题正确');
  const jMeta = (await page.locator('#journalBody .dt').first().textContent()).trim();
  check(jMeta.includes('·') && jMeta.includes(TITLE), '游记元信息含行程名：' + jMeta);
  const jEx = (await page.locator('#journalBody .excerpt').first().textContent()).trim();
  check(jEx.length > 10, '游记摘要非空');
  check((await page.locator('#journalLink').getAttribute('href')).includes('/journals/?trip=' + tripId), '「全部游记」带行程参数');

  /* ---- 侧栏 ---- */
  check((await page.locator('.nav a[aria-current="page"] .txt').textContent()).trim() === '首页', '侧栏首页高亮');
  const unreadHidden = await page.locator('#navUnread').isHidden();
  check(unreadHidden === (Number(dash.stats.unreadNotificationCount) === 0), '未读角标与接口一致（' + dash.stats.unreadNotificationCount + '）');
  await page.locator('#railToggle').click();
  await page.waitForTimeout(400);
  check((await page.locator('#shell').getAttribute('data-collapsed')) === 'true', '侧栏可折叠');
  await page.screenshot({ path: path.join(SHOTS, 'rail-collapsed.png') });
  await page.locator('#railToggle').click();
  await page.waitForTimeout(400);
  check((await page.locator('#shell').getAttribute('data-collapsed')) === 'false', '侧栏可展开');

  check(errors.length === 0, '无 JS 报错' + (errors.length ? ' -> ' + errors.slice(0, 3).join(' | ') : ''));

  await page.screenshot({ path: path.join(SHOTS, 'desktop.png'), fullPage: true });
  console.log('  shot -> desktop.png');

  /* 响应式：1080 断点 */
  await page.setViewportSize({ width: 1000, height: 900 });
  await page.waitForTimeout(500);
  const statsCols = await page.locator('.stats').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(statsCols === 2, '<=1080px 统计变 2 列（实测 ' + statsCols + '）');
  const duoCols = await page.locator('.duo').first().evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(duoCols === 1, '<=1080px 双栏堆叠（实测 ' + duoCols + '）');
  await page.screenshot({ path: path.join(SHOTS, 'tablet.png'), fullPage: true });

  /* 响应式：720 断点 */
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(500);
  const shellCols = await page.locator('#shell').evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  check(shellCols.indexOf(' ') === -1, '<=720px 侧栏变顶栏（单列）');
  check(await page.locator('.rail-toggle').isHidden(), '<=720px 折叠按钮隐藏');
  const photoCols = await page.locator('.photo-grid').evaluate((el) => getComputedStyle(el).gridTemplateColumns.split(' ').length);
  check(photoCols === 2, '<=720px 照片网格 2 列');
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('  shot -> mobile.png');

  await browser.close();

  /* ================= cleanup ================= */
  console.log('--- cleanup ---');
  const q = (id) => `'${id}'`;
  psql(`DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=${q(tripId)});`);
  psql(`DELETE FROM trip_journal_photos WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=${q(tripId)});`);
  psql(`DELETE FROM packing_items WHERE packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id=${q(tripId)});`);
  for (const t of ['ai_conversations', 'bookings', 'packing_lists', 'photos', 'travel_documents', 'trip_budgets',
                   'trip_days', 'trip_expenses', 'trip_journals', 'trip_members', 'trip_places', 'trip_routes',
                   'trip_settlements', 'trip_tracks', 'trip_weather']) {
    psql(`DELETE FROM ${t} WHERE trip_id=${q(tripId)};`);
  }
  psql(`DELETE FROM trips WHERE id=${q(tripId)};`);

  const left = psql(`SELECT count(*) FROM trips WHERE id=${q(tripId)};`);
  check(left === '0', '测试行程已清除');
  /* 复核基线（瞬时网络抖动重试一次） */
  let dashAfter = null;
  for (let i = 0; i < 2 && !dashAfter; i++) {
    try { dashAfter = await jget('/api/dashboard'); }
    catch (e) { if (i === 1) console.log('  (skip) dashboard 复核请求失败：', (e && e.cause && e.cause.code) || e.message); }
  }
  if (dashAfter) {
    check(String(dashAfter.stats.tripCount) === String(Number(expected.trips) - 1),
      '总览行程数回到清理前（' + dashAfter.stats.tripCount + '）');
  }

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} 项断言失败`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('[ERROR]', (e && e.stack) || e); process.exit(1); });
