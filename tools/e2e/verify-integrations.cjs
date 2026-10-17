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

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/integration-shots';
const REAL_TRIP = 'cmrykzwh90000fmvg8xr7bclf';   // 赤峰之旅：只读断言
const PG = 'docker exec travel-os-postgres psql -U travel_os -d travel_os -t -A -c';

let failures = 0;
function bail(msg) { console.error('\n[FAIL] ' + msg); process.exit(1); }
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { failures++; console.error('  !!  ' + msg); } }
function psql(sql) { return execSync(`${PG} "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' }).trim(); }
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

(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });

  const login = await (await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  })).json();
  const token = login.token;
  const H = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };
  const jreq = async (method, p, body) => {
    const r = await fetch(ORIGIN + p, { method, headers: H, body: body === undefined ? undefined : JSON.stringify(body) });
    const t = await r.text();
    return { status: r.status, json: t ? (() => { try { return JSON.parse(t); } catch (e) { return null; } })() : null, text: t };
  };
  const jget = (p) => jreq('GET', p).then((r) => r.json);
  const jpost = (p, b) => jreq('POST', p, b || {});

  const me = await jget('/api/auth/me');
  const adminId = me.user.id;
  const baselineTripCount = String((await jget('/api/dashboard')).stats.tripCount);

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
  console.log(`  trip=${TEMP} place=${place.id} booking=${booking.id} journal=${journal.id} photos=${seededPhotos.join(',')}`);

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
  check(badges.length === 8, '8 个模块磁贴');
  check(!badges.includes('–'), '模块计数不再是「–」：' + badges.join(' | '));
  check(badges.filter((b) => /^\d+ /.test(b)).length === 8, '8 个计数都是真实数字');

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
  check(wx > 0, '天气卡渲染 ' + wx + ' 张');
  check(wxTexts.length > 0 && wxTexts.every((t) => t.includes('°') && t.trim() !== '–'),
    '天气温度已显示（字段修复）：' + wxTexts.join(' / '));
  const wxIcon = (await page.locator('#weatherBox .weather .ic2').first().textContent()).trim();
  check(/[☀☁🌧❄⛈]/.test(wxIcon), '天气图标按 iconCode 映射：' + wxIcon);
  const wxTitle = await page.locator('#weatherBox .weather').first().getAttribute('title');
  check(/降水概率|天气/.test(wxTitle || ''), '天气卡 tooltip 含天气与降水概率：' + wxTitle);
  await page.screenshot({ path: path.join(SHOTS, 'detail-weather.png'), fullPage: true });

  /* ---- AI 助手 ---- */
  console.log('  --- AI 助手（真实 LLM 调用）---');
  await page.locator('.pet').click();
  await page.waitForSelector('.pet-bubble.open', { timeout: 8000 });
  await page.locator('.pet-bubble .ex').click();
  await page.waitForSelector('.pet-bubble.expanded', { timeout: 8000 });
  await page.waitForTimeout(800);
  check(await page.locator('.pet-bubble.expanded').isVisible(), 'AI 助手打开（统一悬浮助手）');
  check((await page.locator('.pet-bubble .pet-empty').count()) === 1, '空会话显示引导态');
  check((await page.locator('.pet-bubble .pet-chips .pet-chip').count()) === 3, '空态含 3 个示例问题');
  check(await page.locator('.pet-bubble #petInput').isVisible(), '输入框可用');

  // 让 AI 产出「记账」草稿
  async function askForDraft(text, attempt) {
    await page.locator('.pet-bubble #petInput').fill(text);
    await page.locator('.pet-bubble #petSend').click();
    try {
      await page.waitForSelector('.pet-bubble .pet-draft', { timeout: 120000 });
      return true;
    } catch (e) {
      console.log('    （第 ' + attempt + ' 次未产出草稿）');
      return false;
    }
  }
  let gotDraft = await askForDraft('帮我在这个行程记一笔账：门票 240 元，标题写「验证门票」', 1);
  if (!gotDraft) gotDraft = await askForDraft('请调用记账工具，记一笔门票花费 240 元', 2);
  check(gotDraft, 'AI 产出「变更预览」草稿卡片');
  check((await page.locator('.pet-bubble .pb-bar select.conv option').count()) >= 1, '会话已落库并出现在下拉里');
  const draftKind = (await page.locator('.pet-bubble .pet-draft .pd-h').last().textContent()).trim();
  check(/预算|变更预览/.test(draftKind), '草稿卡标题：' + draftKind);
  check((await page.locator('.pet-bubble .pet-draft .pd-list li').count()) >= 1, '草稿卡列出变更明细');
  const msgsBefore = await page.locator('.pet-bubble .pet-msg').count();
  check(msgsBefore >= 2, '对话消息 ' + msgsBefore + ' 条（用户 + 助手）');
  await page.screenshot({ path: path.join(SHOTS, 'ai-draft.png'), fullPage: false });

  // 应用草稿 → 应真的写入账目
  const expBefore = (await jget(`/api/trips/${TEMP}/expenses`)).expenses.length;
  await page.locator('.pet-bubble .pet-draft .pd-btn.apply').last().click();
  await page.waitForSelector('.pet-bubble .pet-draft .pd-stat.ok', { timeout: 30000 });
  await page.waitForTimeout(800);
  const expAfter = (await jget(`/api/trips/${TEMP}/expenses`)).expenses;
  check(expAfter.length === expBefore + 1, '「应用」真的写入了一条账目（' + expBefore + ' → ' + expAfter.length + '）');
  const applied = expAfter[expAfter.length - 1];
  check(String(applied.amount).indexOf('240') >= 0, '写入金额正确：' + applied.amount);
  check((await page.locator('.pet-bubble .pet-draft .pd-stat.ok').count()) >= 1, '草稿卡显示「已应用」');

  // 放弃草稿
  const gotDraft2 = await askForDraft('再记一笔餐饮 100 元，标题「验证餐饮」', 1);
  if (gotDraft2) {
    await page.locator('.pet-bubble .pet-draft .pd-btn.discard').last().click();
    await page.waitForTimeout(1500);
    check((await page.locator('.pet-bubble .pet-draft .pd-stat.no').count()) >= 1, '「放弃」后草稿卡显示已放弃');
    const expNow = (await jget(`/api/trips/${TEMP}/expenses`)).expenses.length;
    check(expNow === expBefore + 1, '放弃的草稿没有写入账目（仍 ' + expNow + ' 条）');
  } else {
    check(false, '第二次草稿未产出，跳过放弃路径');
  }

  // 新建 / 删除会话
  // 新建 / 删除会话（统一助手为懒创建：点「＋ 新建」后首次发消息才落库）
  const convs0 = (await jget('/api/ai/conversations?tripId=' + TEMP)).conversations.length;
  await page.locator('.pet-bubble .pb-bar .new').click();
  await page.waitForTimeout(600);
  await page.locator('.pet-bubble #petInput').fill('你好');
  await page.locator('.pet-bubble #petSend').click();
  // 等待 AI 回复完成（打字指示消失）再删除，避免删除在途会话导致 500
  await page.waitForSelector('.pet-bubble .pet-typing', { timeout: 10000 }).catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.pet-bubble .pet-typing'), { timeout: 120000 });
  let convs = convs0;
  for (let i = 0; i < 60; i++) {
    await page.waitForTimeout(1000);
    convs = (await jget('/api/ai/conversations?tripId=' + TEMP)).conversations.length;
    if (convs >= convs0 + 1) break;
  }
  check(convs >= convs0 + 1, '「＋ 新建」后首次发消息创建了第二个会话（共 ' + convs + '）');
  await page.waitForTimeout(800);
  check(await page.locator('.pet-bubble .pb-bar .del').isEnabled(), '新会话下「删除」可用');
  page.once('dialog', (d) => d.accept());
  await page.locator('.pet-bubble .pb-bar .del').click();
  let convs2 = convs;
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(500);
    convs2 = (await jget('/api/ai/conversations?tripId=' + TEMP)).conversations.length;
    if (convs2 === convs - 1) break;
  }
  check(convs2 === convs - 1, '「删除」生效（' + convs + ' → ' + convs2 + '）');
  await page.locator('.pet-bubble .x').click();
  await page.waitForTimeout(500);
  check((await page.locator('.pet-bubble.expanded').count()) === 0, 'AI 助手可关闭');

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
  check(psql(`SELECT count(*) FROM trips WHERE id=${q(TEMP)};`) === '0', '临时行程已清除');
  check((await jget('/api/ai/conversations?tripId=' + TEMP)).conversations.length === 0, 'AI 会话已清空');
  const dash = await jget('/api/dashboard');
  check(String(dash.stats.tripCount) === baselineTripCount, '总览行程数回到基线 ' + baselineTripCount + '（实际 ' + dash.stats.tripCount + '）');

  console.log(failures === 0 ? '\nALL PASS' : `\n${failures} 项断言失败`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => { console.error('[ERROR]', (e && e.stack) || e); process.exit(1); });
