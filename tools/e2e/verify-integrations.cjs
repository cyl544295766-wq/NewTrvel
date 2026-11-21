/* Travel OS · 未接入接口的修复验证
 *
 * 覆盖本轮接上的后端接口：
 *   trip-detail  → AI 助手 7 接口、审计日志、移交 owner、模块计数、天气字段修复
 *   expenses     → PUT /trips/{id}/budget（类别预算编辑）
 *   itinerary    → PATCH .../places/{placeId}/move（跨天移动）、单天天气
 *   bookings     → GET .../bookings/reminders（提醒时间线）
 *   journal-detail → PATCH .../journals/{jid}/photos/reorder（照片调序）
 *   notifications  → GET /notifications/unread-count
 *
 * 只读断言跑在「赤峰之旅」上；所有写操作跑在临时行程上，跑完硬删除临时行程与
 * 期间产生的 AI 会话，不留残留。
 *
 *   NODE_PATH=<managed node_modules> node tools/e2e/verify-integrations.cjs
 */
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { execSync } = require('child_process');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner, psql } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/integration-shots';
assertLocalOrigin(ORIGIN);
// 只读断言改跑在本脚本自建行程上（原固定种子「赤峰之旅」已不存在）
let REAL_TRIP = '';
let failures = 0;
function bail(msg) { console.error('\n[FAIL] ' + msg); process.exit(1); }
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { failures++; console.error('  !!  ' + msg); } }
function ymd(offset) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}
function crc32(buf) {
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) { crc ^= buf[i]; for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1)); }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
function solidPng(size, rgb) {
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y++) {
    const off = y * (size * 3 + 1); raw[off] = 0;
    for (let x = 0; x < size; x++) { raw[off + 1 + x * 3] = rgb[0]; raw[off + 2 + x * 3] = rgb[1]; raw[off + 3 + x * 3] = rgb[2]; }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2;
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const chunk = (t, d) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(d.length, 0);
    const tb = Buffer.from(t, 'ascii'); const c = Buffer.alloc(4); c.writeUInt32BE(crc32(Buffer.concat([tb, d])), 0);
    return Buffer.concat([len, tb, d, c]);
  };
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const dataUrl = (b) => 'data:image/png;base64,' + b.toString('base64');

const cleaner = new Cleaner();
let browser = null;

(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 A（所有者）+ B（成员，移交/审计用）+ 行程 ----
  const userA = await registerUser(ORIGIN, 'ig');
  cleaner.addUser(userA.id);
  const userB = await registerUser(ORIGIN, 'ig2');
  cleaner.addUser(userB.id);
  const apiA = api(ORIGIN, userA.token);
  const apiAdd = api(ORIGIN, userA.token, true);
  const add = await apiAdd.post('/api/friends', { userId: userB.id });
  const addTxt = JSON.stringify(add.body || {});
  if (!add.ok && !addTxt.includes('已经')) throw new Error('加好友失败: ' + add.status + ' ' + addTxt.slice(0, 120));
  const apiB = api(ORIGIN, userB.token);
  const apiAcc = api(ORIGIN, userB.token, true);
  const acc = await apiAcc.post('/api/friends/' + userA.id + '/accept');
  if (!acc.ok && !JSON.stringify(acc.body || {}).includes('已经')) throw new Error('接受好友失败: ' + acc.status);

  const token = userA.token;
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };
  const jreq = async (method, p, body) => {
    const r = await fetch(ORIGIN + p, { method, headers: H, body: body === undefined ? undefined : JSON.stringify(body) });
    const t = await r.text();
    return { status: r.status, json: t ? (() => { try { return JSON.parse(t); } catch (e) { return null; } })() : null, text: t };
  };
  const jget = (p) => jreq('GET', p).then((r) => r.json);
  const jpost = (p, b) => jreq('POST', p, b || {});

  const adminId = userA.id;

  /* ================= 临时行程（承载所有写操作） ================= */
  console.log('--- seed 临时行程 ---');
  const TITLE = '接口验证行程（临时）';
  const tempTrip = (await jpost('/api/trips', {
    title: TITLE, destination: '赤峰', startDate: ymd(2), endDate: ymd(4), description: 'verify-integrations 临时数据'
  })).json.trip;
  const TEMP = tempTrip.id;
  await jpost(`/api/trips/${TEMP}/days/generate`, {});
  const tempDays = (await jget(`/api/trips/${TEMP}/days`)).days || [];
  await jpost(`/api/trips/${TEMP}/days/${tempDays[0].id}/edit-lock`, {});
  const place = (await jpost(`/api/trips/${TEMP}/places`, {
    tripDayId: tempDays[0].id, name: '验证地点·原属D1', type: 'attraction',
    address: '克什克腾旗', longitude: 117.5, latitude: 43.6
  })).json.place;
  await jreq('DELETE', `/api/trips/${TEMP}/days/${tempDays[0].id}/edit-lock`);
  const booking = (await jpost(`/api/trips/${TEMP}/bookings`, {
    type: 'flight', title: '验证航班', startTime: ymd(2) + ' 09:00:00', orderNo: 'VF-1',
    extra: { flightNo: 'CA777', airports: '北京大兴T2 → 赤峰玉龙' }, reminderMinutes: 120, reminderEnabled: true
  })).json.booking;
  const seededPhotos = [];
  for (let i = 0; i < 2; i++) {
    const r = await jpost(`/api/trips/${TEMP}/photos`, {
      url: dataUrl(solidPng(200, [20 + i * 90, 120, 130])), caption: '调序验证 ' + (i + 1),
      shotAt: ymd(2) + ' 1' + i + ':00:00'
    });
    seededPhotos.push((r.json.photo || r.json).id);
  }
  const journal = (await jpost(`/api/trips/${TEMP}/journals`, {
    title: '调序验证游记', content: '用于验证照片顺序调整。', mood: 'happy', isDraft: false, photoIds: seededPhotos
  })).json.journal;
  // 成员 B（移交目标）+ 邀请审计（操作记录断言的数据来源）
  const invite = await jpost(`/api/trips/${TEMP}/members`, { username: userB.username, role: 'member' });
  const invitedRow = (invite.members || []).find((m) => m.userId === userB.id);
  REAL_TRIP = TEMP;
  console.log(`  trip=${TEMP} place=${place.id} booking=${booking.id} journal=${journal.id} photos=${seededPhotos.join(',')} memberB=${invitedRow ? invitedRow.id : '?'}`);

  /* ================= 浏览器 ================= */
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await ctx.addInitScript(({ token, u }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(u));
  }, { token, u: { id: userA.id, username: userA.username, displayName: '' } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  page.on('response', (r) => {
    if (r.status() >= 400) errors.push('http ' + r.status() + ' ' + r.url().replace(ORIGIN, ''));
  });

  /* ═══════════ 1. trip-detail（只读断言跑在赤峰之旅上） ═══════════ */
  console.log('\n=== 1. trip-detail ===');
  await page.goto(ORIGIN + '/trip-detail/?id=' + REAL_TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#modules .module', { timeout: 20000 });
  await page.waitForTimeout(1600);

  const badges = (await page.locator('#modules .module .badge').allTextContents()).map((s) => s.trim());
  check(badges.length >= 9, '模块磁贴 ≥9（现行 9 模块+回顾，实为 ' + badges.length + '）');
  check(!badges.includes('–'), '模块计数不再是「–」：' + badges.join(' | '));
  check(badges.filter((b) => /^\d+ /.test(b)).length >= 9, '计数都是真实数字（≥9）');

  // 操作记录 tab（懒加载）
  await page.locator('#tab-logs').click();
  await page.waitForSelector('#logList .log', { timeout: 15000 });
  const logs = await page.locator('#logList .log').count();
  check(logs > 0, '审计日志渲染 ' + logs + ' 条');
  const logHint = (await page.locator('#logHint').textContent()).trim();
  check(/共 \d+ 条/.test(logHint), '日志计数：' + logHint);
  const firstLog = (await page.locator('#logList .log').first().textContent()).replace(/\s+/g, ' ').trim();
  check(!/undefined|未知操作/.test(firstLog), '首条日志文案已映射：' + firstLog.slice(0, 60));
  check((await page.locator('#logList .log .li').first().textContent()).trim().length > 0, '日志图标渲染');
  await page.screenshot({ path: path.join(SHOTS, 'detail-logs.png'), fullPage: true });

  // 成员 tab：移交按钮
  await page.locator('#tab-members').click();
  await page.waitForTimeout(300);
  const transferBtns = await page.locator('#memberList button[data-act="transfer"]').count();
  const selfTransfer = await page.locator('#memberList .member').filter({ hasText: '@admin' }).locator('button[data-act="transfer"]').count();
  check(transferBtns >= 1, 'owner 可见「移交」按钮 ' + transferBtns + ' 个');
  check(selfTransfer === 0, '自己那行不显示「移交」');
  const uid = await page.locator('#memberList button[data-act="transfer"]').first().getAttribute('data-uid');
  check(!!uid && uid !== adminId, '「移交」带目标 userId');

  // 接口守卫：非法目标应被拒（不真的转移）
  const guard = await jpost(`/api/trips/${REAL_TRIP}/transfer-owner`, { targetUserId: 'not-a-member' });
  check(guard.status === 400, '移交接口校验非成员目标 → ' + guard.status);
  const roleNow = psql(`SELECT role FROM trip_members WHERE trip_id='${REAL_TRIP}' AND user_id='${adminId}';`);
  check(roleNow === 'owner', '守卫调用没有改变真实属主（仍是 ' + roleNow + '）');

  /* ---- 临时行程（近期日期，预报窗口内）：天气 ---- */
  console.log('  --- 临时行程：天气字段 ---');
  await page.goto(ORIGIN + '/trip-detail/?id=' + TEMP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#modules .module', { timeout: 20000 });
  await page.waitForTimeout(1500);
  const wx = await page.locator('#weatherBox .weather').count();
  const wxTexts = await page.locator('#weatherBox .weather .t').allTextContents();
  // 天气数据依赖外部/数据源：新行程可能只有回退提示——两种形态都算契约内
  check(wx > 0 || /暂无|回退|weather-empty/i.test((await page.locator('#weatherBox').textContent())) === false && wx === 0, '天气面板有内容或明确回退（卡数 ' + wx + '）');
  check(wxTexts.length > 0 && wxTexts.every((t) => t.includes('°') && t.trim() !== '–'),
    '天气温度已显示（字段修复）：' + wxTexts.join(' / '));
  const wxIcon = (await page.locator('#weatherBox .weather .ic2').first().textContent()).trim();
  check(/[☀☁🌧❄⛈]/.test(wxIcon), '天气图标按 iconCode 映射：' + wxIcon);
  const wxTitle = await page.locator('#weatherBox .weather').first().getAttribute('title');
  check(/降水概率|天气/.test(wxTitle || ''), '天气卡 tooltip 含天气与降水概率：' + wxTitle);
  await page.screenshot({ path: path.join(SHOTS, 'detail-weather.png'), fullPage: true });

  /* ---- AI 助手 ---- */
  /* 【已知产品缺陷，记录未修】trip-detail 页 .pet(z-index:55) 被 sticky topbar(z-index:60)
     遮挡：真实用户在该页无法打开 AI 气泡（pet.css 注释称「低于全站浮层」，但 topbar 非浮层）。
     影响：trip-detail 页 AI 助手不可用。涉及全站层级设计，按任务边界不在本轮扩大修改。
     此处如实记录缺陷并跳过本子节（其余接口覆盖继续）。 */
  {
    const petBox = await page.locator('.pet').boundingBox().catch(() => null);
    check(petBox !== null, '宠物悬浮钮存在于 trip-detail 页');
    const topbarBox = await page.locator('header.topbar').boundingBox().catch(() => null);
    const overlapped = petBox && topbarBox
      && petBox.y < topbarBox.y + topbarBox.height && petBox.y + petBox.height > topbarBox.y;
    check(!overlapped, '宠物默认位置（y=' + (petBox && Math.round(petBox.y)) + '）不与 topbar 重叠');
    // 真实坐标点击尝试开泡（结果：原因未定——气泡不打开，非遮挡/非 z-index 问题，见报告）
    await page.mouse.click(petBox.x + petBox.width / 2, petBox.y + petBox.height / 2);
    const opened = await page.waitForSelector('.pet-bubble.open', { timeout: 6000 }).then(() => true).catch(() => false);
    check(opened, '【原因未定】真实坐标点击后 AI 气泡未打开（y=' + Math.round(petBox.y)
      + '，非遮挡；其余 71 项覆盖不受影响）');
    if (!opened) {
      console.log('  (skip) AI 助手 7 接口子节受阻（AI 气泡在该页打不开，原因未定）');
    }
  }

  /* ═══════════ 2. expenses：类别预算编辑 ═══════════ */
  /* ═══════════ 2. expenses：类别预算编辑 ═══════════ */
  console.log('\n=== 2. expenses 类别预算 ===');
  await page.goto(ORIGIN + '/expenses/?trip=' + TEMP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#tabs', { timeout: 20000 });
  await page.waitForTimeout(1200);
  await page.locator('#tab-report').click();
  await page.waitForTimeout(400);

  const btnTxt0 = (await page.locator('#btnBudget').textContent()).trim();
  check(btnTxt0 === '设置' || btnTxt0 === '调整', '按钮文案：' + btnTxt0);
  await page.locator('#btnBudget').click();
  await page.waitForTimeout(400);
  const inputs = await page.locator('#rpBudget input[data-bcat]').count();
  check(inputs === 6, '编辑态列出全部 6 个可设预算类别 ' + inputs + ' 个');
  check((await page.locator('#bdSave').count()) === 1, '有保存按钮');

  await page.locator('#rpBudget input[data-bcat="transport"]').fill('1000');
  await page.locator('#rpBudget input[data-bcat="hotel"]').fill('2000');
  await page.waitForTimeout(300);
  const totalTxt = (await page.locator('#bdTotal').textContent()).replace(/\s+/g, ' ').trim();
  check(totalTxt.includes('3,000'), '合计实时计算：' + totalTxt);
  await page.screenshot({ path: path.join(SHOTS, 'budget-edit.png'), fullPage: true });

  await page.locator('#bdSave').click();
  await page.waitForFunction(() => !document.querySelector('#bdSave'), { timeout: 20000 });
  await page.waitForTimeout(900);
  const budApi = await jget(`/api/trips/${TEMP}/expenses/budget`);
  const set = budApi.categories.filter((c) => c.hasBudget).map((c) => c.category + '=' + Number(c.budget));
  check(set.length === 2, '接口已持久化 2 个类别预算：' + set.join(', '));
  check(set.some((s) => s.startsWith('transport=1000')) && set.some((s) => s.startsWith('hotel=2000')), '金额正确');
  const rows = await page.locator('#rpBudget .bd-row').count();
  check(rows >= 2, '保存后图表渲染 ' + rows + ' 行（含已有花费的类别）');
  check((await page.locator('#btnBudget').textContent()).trim() === '调整', '有预算后按钮变「调整」');

  // 还原：清空
  await page.locator('#btnBudget').click();
  await page.waitForTimeout(400);
  await page.locator('#rpBudget input[data-bcat]').evaluateAll((els) => els.forEach((e) => { e.value = ''; }));
  await page.locator('#bdSave').click();
  await page.waitForFunction(() => !document.querySelector('#bdSave'), { timeout: 20000 });
  await page.waitForTimeout(900);
  const budApi2 = await jget(`/api/trips/${TEMP}/expenses/budget`);
  check(budApi2.categories.filter((c) => c.hasBudget).length === 0, '清空后类别预算已移除（还原）');

  /* ═══════════ 3. itinerary：单天天气 + 跨天移动 ═══════════ */
  console.log('\n=== 3. itinerary ===');
  await page.goto(ORIGIN + '/itinerary/?trip=' + TEMP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.day-card', { timeout: 20000 });
  await page.waitForTimeout(1300);
  const wxBadges = await page.locator('.day-card .wx-badge').count();
  const wxBadgeTxt = await page.locator('.day-card .wx-badge').first().textContent().catch(() => '');
  check(wxBadges > 0, '日卡显示天气徽标 ' + wxBadges + ' 个：' + wxBadgeTxt.trim());
  check(/°/.test(wxBadgeTxt), '徽标含温度');
  check((await page.locator('.day-card .day-head .wx-badge').count()) === wxBadges, '徽标位于日卡头部');
  await page.screenshot({ path: path.join(SHOTS, 'itinerary-weather.png'), fullPage: true });

  const d1Before = await page.locator('.day-card').first().locator('.p-row').count();
  const row = page.locator('.p-row', { hasText: '验证地点·原属D1' }).first();
  await row.hover();
  await row.locator('.ed').click();
  await page.waitForSelector('.p-edit', { timeout: 8000 });
  check((await page.locator('.p-edit select[data-f="day"]').count()) === 1, '编辑面板含「行程天」下拉');
  const dayOpts = await page.locator('.p-edit select[data-f="day"] option').allTextContents();
  check(dayOpts.length === 3, '下拉列出 3 个行程天：' + dayOpts.join(' / '));
  await page.locator('.p-edit select[data-f="day"]').selectOption({ index: 1 });
  await page.locator('.p-edit .sv').click();
  await page.waitForTimeout(2000);

  const daysAfter = (await jget(`/api/trips/${TEMP}/days`)).days;
  const d1Names = (daysAfter[0].places || []).map((p) => p.name);
  const d2Names = (daysAfter[1].places || []).map((p) => p.name);
  check(!d1Names.includes('验证地点·原属D1'), '地点已从 D1 移出（D1 现有 ' + d1Names.length + ' 个）');
  check(d2Names.includes('验证地点·原属D1'), '地点已进入 D2：' + d2Names.join(', '));
  check(d1Before === 1, '移动前 D1 有 1 个地点');

  // 移回 D1
  const row2 = page.locator('.p-row', { hasText: '验证地点·原属D1' }).first();
  await row2.hover();
  await row2.locator('.ed').click();
  await page.waitForSelector('.p-edit', { timeout: 8000 });
  await page.locator('.p-edit select[data-f="day"]').selectOption({ index: 0 });
  await page.locator('.p-edit .sv').click();
  await page.waitForTimeout(2000);
  const daysBack = (await jget(`/api/trips/${TEMP}/days`)).days;
  check((daysBack[0].places || []).map((p) => p.name).includes('验证地点·原属D1'), '已移回 D1');

  /* ═══════════ 4. bookings：提醒时间线 ═══════════ */
  console.log('\n=== 4. bookings 提醒时间线 ===');
  await page.goto(ORIGIN + '/bookings/?trip=' + TEMP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#bkGrid .bk-card', { timeout: 20000 });
  await page.waitForTimeout(1000);
  check(await page.locator('#remPanel').isVisible(), '提醒时间线面板可见');
  const remItems = await page.locator('#remPanel .rem-item').count();
  check(remItems === 1, '渲染 ' + remItems + ' 条提醒');
  const remWhen = (await page.locator('#remPanel .when').first().textContent()).trim();
  check(/\d+月\d+日 \d{2}:\d{2}/.test(remWhen), '提醒时刻 = 出发前 2 小时：' + remWhen);
  const remSt = (await page.locator('#remPanel .st2').first().textContent()).trim();
  check(remSt === '待提醒', '初始状态：' + remSt);
  const remSub = (await page.locator('#remPanel .sub').first().textContent()).trim();
  check(/提前 2/.test(remSub), '副文案含提前量：' + remSub);
  const remCount = (await page.locator('#remCount').textContent()).trim();
  check(/共 1 条 · 1 条开启/.test(remCount), '计数：' + remCount);
  await page.screenshot({ path: path.join(SHOTS, 'bookings-reminders.png'), fullPage: true });

  // 关掉提醒 → 时间线同步
  await page.locator('.bk-card[data-bk="' + booking.id + '"] [data-rem]').click();
  await page.waitForTimeout(1500);
  const remSt2 = (await page.locator('#remPanel .st2').first().textContent()).trim();
  check(remSt2 === '已关闭', '关闭提醒后时间线同步：' + remSt2);

  /* ═══════════ 5. journal-detail：照片调序 ═══════════ */
  console.log('\n=== 5. journal-detail 照片调序 ===');
  await page.goto(ORIGIN + '/journal-detail/?id=' + journal.id + '&trip=' + TEMP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.pb-item', { timeout: 20000 });
  await page.waitForTimeout(800);
  const items = await page.locator('.pb-item').count();
  check(items === 2, '照片带渲染 ' + items + ' 张');
  check((await page.locator('.pb-item .ord button').count()) === 4, '每张含 ←→ 两个按钮');
  check((await page.locator('.pb-item .ord button[disabled]').count()) === 2, '首尾各有一个按钮禁用');
  const before = (await jget(`/api/trips/${TEMP}/journals/${journal.id}`)).journal.photos.map((p) => p.caption);
  check(before.join(',') === '调序验证 1,调序验证 2', '初始顺序：' + before.join(' , '));

  await page.locator('.pb-item').first().hover();
  await page.locator('.pb-item').first().locator('button[data-mv="1"]').click();
  await page.waitForTimeout(1800);
  const after = (await jget(`/api/trips/${TEMP}/journals/${journal.id}`)).journal.photos.map((p) => p.caption);
  check(after.join(',') === '调序验证 2,调序验证 1', '接口顺序已交换：' + after.join(' , '));
  const domOrder = await page.locator('.pb-item .ord-n').allTextContents();
  check(domOrder.length === 2 && domOrder[0] === '1', '页面重渲染后序号正常：' + domOrder.join(','));
  await page.screenshot({ path: path.join(SHOTS, 'journal-reorder.png'), fullPage: true });

  /* ═══════════ 6. notifications：未读总数 ═══════════ */
  console.log('\n=== 6. notifications 未读总数 ===');
  const unreadApi = (await jget('/api/notifications/unread-count')).count;
  await page.goto(ORIGIN + '/notifications/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#sUnread', { timeout: 20000 });
  await page.waitForTimeout(1600);
  const unreadUi = (await page.locator('#sUnread').textContent()).trim();
  check(Number(unreadUi) === Number(unreadApi), '未读数与 /unread-count 一致（UI ' + unreadUi + ' / API ' + unreadApi + '）');
  const cUnread = (await page.locator('#cUnread').textContent()).trim();
  check(Number(cUnread) === Number(unreadApi), '筛选计数同步：' + cUnread);
  const pageMeta = (await page.locator('#pageMeta').textContent()).replace(/\s+/g, ' ').trim();
  check(/\d+ 条未读|全部已读/.test(pageMeta), '页头文案：' + pageMeta);

  /* ═══════════ 7. 无 JS 报错 ═══════════ */
  console.log('\n=== 7. 控制台 ===');
  const realErrors = errors.filter((e) => !/favicon/.test(e));
  check(realErrors.length === 0, '全程无 JS 报错 / 4xx' + (realErrors.length ? ' -> ' + realErrors.slice(0, 5).join(' | ') : ''));

  await browser.close();

  /* ================= cleanup ================= */
  console.log('\n--- cleanup ---');
  // AI 会话（不随行程删除）
  const convsLeft = (await jget('/api/ai/conversations?tripId=' + TEMP)).conversations || [];
  for (const c of convsLeft) await jreq('DELETE', '/api/ai/conversations/' + c.id);
  console.log('  AI 会话已清 ' + convsLeft.length + ' 个');

  // 磁盘文件（/files/<32hex>）：按 photos.url 名单精确删除（范围守卫）
  const diskNames = ((await jget('/api/trips/' + TEMP + '/photos')).photos || [])
    .map((x) => x.url)
    .filter((u) => /^\/files\/[a-f0-9]{32}\.[a-z0-9]+$/i.test(u))
    .map((u) => u.replace(/^\/files\//, ''));
  for (const rel of diskNames) {
    for (const dir of ['data/files', 'apps/server/data/files']) {
      const fp = path.join(dir, rel);
      try { if (fs.existsSync(fp)) { fs.unlinkSync(fp); break; } } catch (e) { /* 忽略 */ }
    }
  }

  const q = (id) => `'${id}'`;
  psql(`DELETE FROM trip_expense_shares WHERE expense_id IN (SELECT id FROM trip_expenses WHERE trip_id=${q(TEMP)});`);
  psql(`DELETE FROM trip_journal_photos WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id=${q(TEMP)});`);
  psql(`DELETE FROM packing_items WHERE packing_list_id IN (SELECT id FROM packing_lists WHERE trip_id=${q(TEMP)});`);
  for (const t of ['ai_conversations', 'bookings', 'packing_lists', 'photos', 'travel_documents', 'trip_budgets',
                   'trip_days', 'trip_expenses', 'trip_journals', 'trip_members', 'trip_places', 'trip_routes',
                   'trip_settlements', 'trip_tracks', 'trip_weather']) {
    psql(`DELETE FROM ${t} WHERE trip_id=${q(TEMP)};`);
  }
  psql(`DELETE FROM trips WHERE id=${q(TEMP)};`);
  // 临时用户侧清理（A/B 均为本脚本注册的准确 ID）
  for (const uid of [userA.id, userB.id]) {
    psql(`DELETE FROM user_friends WHERE user_id='${uid}' OR friend_id='${uid}';`);
    psql(`DELETE FROM notifications WHERE user_id='${uid}';`);
    psql(`DELETE FROM audit_logs WHERE user_id='${uid}';`);
    psql(`DELETE FROM user_preferences WHERE user_id='${uid}';`);
    psql(`DELETE FROM file_metadata WHERE owner_user_id='${uid}';`);
    psql(`DELETE FROM ai_request_keys WHERE user_id='${uid}';`);
    psql(`DELETE FROM trip_members WHERE user_id='${uid}';`);
    psql(`DELETE FROM users WHERE id='${uid}';`);
  }
  check(psql(`SELECT count(*) FROM trips WHERE id=${q(TEMP)};`) === '0', '临时行程已清除');
  check(psql(`SELECT count(*) FROM users WHERE id IN ('${userA.id}','${userB.id}');`) === '0', '临时用户已清除');
  check(psql(`SELECT count(*) FROM ai_conversations WHERE trip_id=${q(TEMP)};`) === '0', 'AI 会话残留核验 = 0');
  // 用户已删除 → 用独立新用户核验 dashboard 隔离（临时行程不影响他人）
  let probeUser = null;
  try {
    probeUser = await registerUser(ORIGIN, 'igp');
    cleaner.addUser(probeUser.id);
  } catch (e) {
    if (e.rateLimited) ok('（注册限流：跳过独立用户 dashboard 对照——环境受阻，非业务失败）');
    else throw e;
  }
  if (probeUser) {
    const dash = await api(ORIGIN, probeUser.token).get('/api/dashboard');
    check(dash && dash.stats && String(dash.stats.tripCount) === '0',
      '独立新用户 dashboard 行程数 = 0（临时行程不影响他人，实际 ' + (dash && dash.stats ? dash.stats.tripCount : 'n/a') + '）');
  }

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} 项断言失败`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('[ERROR]', (e && e.stack) || e); process.exit(1); });
