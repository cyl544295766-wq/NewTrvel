// Verify account: profile from /me / export JSON download / change password / preferences load+save+persist / danger gating / logout
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/account-shots';
assertLocalOrigin(ORIGIN);

const cleaner = new Cleaner();
let browser = null;
let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }
function assertTrue(cond, msg) { if (!cond) bail(msg); }

async function main() {
  // ---- 夹具：自注册用户（口令已知）+ 一个含费用的行程（导出 JSON 需要） ----
  const user = await registerUser(ORIGIN, 'ac');
  cleaner.addUser(user.id);
  const token = user.token;
  const apiU = api(ORIGIN, user.token);
  const fxTrip = cleaner.addTrip((await apiU.post('/api/trips', {
    title: '账户验证行程（临时）', destination: '账户镇', startDate: '2026-06-10', endDate: '2026-06-12'
  })).trip.id);
  const fxExp = await apiU.post('/api/trips/' + fxTrip + '/expenses', {
    amount: '55.00', category: 'food', title: '账户验证费用', payerUserId: user.id, shareUserIds: [user.id]
  });
  cleaner.addExpense(fxExp.expense ? fxExp.expense.id : fxExp.id);
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 }, locale: 'zh-CN', acceptDownloads: true
  });
  await ctx.addInitScript(({ token, user }) => {
    /* 只注入一次：logout 后不应被重新写回 */
    if (sessionStorage.getItem('__seeded')) return;
    sessionStorage.setItem('__seeded', '1');
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user: { id: user.id, username: user.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  console.log('--- load account ---');
  await page.goto(ORIGIN + '/account/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('hpName').textContent !== '…', { timeout: 8000 });
  await page.waitForTimeout(400);

  // profile vs API
  const me = await (await fetch(ORIGIN + '/api/auth/me', { headers: { 'Authorization': 'Bearer ' + token } })).json();
  const expectName = me.user.displayName || me.user.username;
  const hpName = (await page.locator('#hpName').textContent()).trim();
  if (hpName !== expectName) bail('profile name expected ' + expectName + ', got ' + hpName);
  const hpAvatar = (await page.locator('#hpAvatar').textContent()).trim();
  if (hpAvatar !== expectName.charAt(0).toUpperCase()) bail('avatar initial wrong: ' + hpAvatar);
  const hpSub = (await page.locator('#hpSub').textContent()).trim();
  if (!hpSub.includes('@' + me.user.username)) bail('profile sub should show @username: ' + hpSub);
  const tagText = (await page.locator('#hpTags').textContent()).trim();
  if (!tagText.includes('正常使用中')) bail('status tag expected 正常使用中, got ' + tagText);
  console.log('profile OK:', hpName, '|', hpSub);

  // info grid 6 items
  const infCount = await page.locator('#infoGrid .inf').count();
  if (infCount !== 6) bail('info grid expected 6 items, got ' + infCount);
  const iUsername = (await page.locator('#iUsername').textContent()).trim();
  if (iUsername !== me.user.username) bail('username mismatch: ' + iUsername);
  const iId = (await page.locator('#iId').textContent()).trim();
  if (iId !== me.user.id) bail('user id mismatch');
  const iCreated = (await page.locator('#iCreated').textContent()).trim();
  if (!/\d{4} 年 \d{1,2} 月 \d{1,2} 日/.test(iCreated)) bail('created date format wrong: ' + iCreated);
  console.log('info grid OK: 6 items, created =', iCreated);

  // subnav
  const navLinks = await page.locator('.subnav a').count();
  if (navLinks !== 5) bail('subnav expected 5 links, got ' + navLinks);
  console.log('subnav OK: 5 links');

  // security: logout present, change-password enabled with inline form
  if (!(await page.locator('#btnLogout').count())) bail('logout button missing');
  const cpBtn = page.locator('#btnChangePwd');
  if (await cpBtn.isDisabled()) bail('change-password button should be enabled now');
  const soonBadges = await page.locator('.pill-soon').count();
  if (soonBadges !== 1) bail('expected exactly 1 待接入 badge (导入), got ' + soonBadges);
  console.log('security OK: change-password enabled, 导入 badge remains');

  // ---- change password flow (real API, restores admin123 at the end) ----
  console.log('--- change password ---');
  await cpBtn.click();
  if (!(await page.locator('#pwdForm').isVisible())) bail('password form did not open');
  await page.fill('#pfOld', user.password);
  await page.fill('#pfNew', 'weak');
  await page.fill('#pfNew2', 'weak');
  if (!(await page.locator('#pfSave').isDisabled())) bail('weak new password should keep save disabled');
  const hintWeak = (await page.locator('#pfHint').textContent()).trim();
  if (!/至少 8 位/.test(hintWeak)) bail('weak-password hint wrong: ' + hintWeak);
  await page.fill('#pfNew', user.password);
  await page.fill('#pfNew2', user.password);
  const hintSame = (await page.locator('#pfHint').textContent()).trim();
  if (!/不能与当前密码相同/.test(hintSame)) bail('same-password hint wrong: ' + hintSame);
  await page.fill('#pfNew', 'NewPass999');
  await page.fill('#pfNew2', 'NewPass999');
  if (await page.locator('#pfSave').isDisabled()) bail('valid form should enable save');
  await page.click('#pfSave');
  await page.waitForFunction(() => document.getElementById('pwdForm').hidden === true, { timeout: 8000 })
    .catch(() => bail('password form did not collapse after save'));
  // verify via API: old password rejected, new accepted, then change back
  const badLogin = await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user.username, password: user.password })
  });
  if (badLogin.status !== 400) bail('old password should be rejected after change, got ' + badLogin.status);
  const newLogin = await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: user.username, password: 'NewPass999' })
  });
  if (newLogin.status !== 200) {
    const bodyTxt = await newLogin.text();
    bail('login with new password failed: ' + newLogin.status + ' body=' + bodyTxt.slice(0, 160));
  }
  const newTok = (await newLogin.json()).token;
  const back = await fetch(ORIGIN + '/api/auth/password', {
    method: 'POST', headers: { 'Authorization': 'Bearer ' + newTok, 'Content-Type': 'application/json' },
    body: JSON.stringify({ oldPassword: 'NewPass999', newPassword: user.password })
  });
  if (back.status !== 200) bail('restore original password failed: ' + back.status);
  console.log('change password OK (weak/same hints, real change, restored original password)');

  // ---- 改密后页面强制清登录态（P2-1 安全语义）→ 重新登录再测偏好 ----
  console.log('--- re-login after password change ---');
  await waitFor('回到登录页', () => /\/login\//.test(page.url()), 10000);
  await page.fill('#username', user.username);
  await page.fill('#password', user.password);
  await page.click('#submitBtn');
  await waitFor('登录成功（离开登录页）', () => !/\/login\//.test(page.url()), 15000);
  await page.goto(ORIGIN + '/account/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => {
    const el = document.getElementById('hpName');
    return el && el.textContent !== '…';
  }, { timeout: 10000 });
  ok('已用原口令重新登录');
  // 改密后旧 token 全部失效（token_valid_after 水位）——改用页面新 token 调 API
  const freshToken = await page.evaluate(() => localStorage.getItem('travelos.token'));
  assertTrue(!!freshToken, '页面持有新 token');
  const apiFresh = api(ORIGIN, freshToken);

  // ---- preferences: enabled selects populated from API, save + persist + restore ----
  console.log('--- preferences ---');
  const enabledSelects = await page.locator('#sec-pref select:enabled').count();
  if (enabledSelects !== 6) bail('prefs expected 6 enabled selects, got ' + enabledSelects);
  if (await page.locator('#sec-pref .notice').count()) bail('prefs notice should be gone');
  const nfSw = await page.locator('#sec-pref .sw[data-ns]').count();
  if (nfSw !== 3) bail('expected 3 notification switches, got ' + nfSw);
  if (!(await page.locator('#sec-pref').textContent()).includes('每周摘要 · 暂未开放')) {
    bail('weekly summary availability is missing');
  }
  if (!(await page.locator('#btnSavePref').isDisabled())) bail('save button should start disabled');
  const prefsApi = await apiFresh.get('/api/auth/preferences');
  const p0 = prefsApi.preferences;
  const themeVal = await page.locator('#prTheme').inputValue();
  if (themeVal !== p0.theme) bail('theme select not populated from API: ' + themeVal + ' vs ' + p0.theme);
  const swOn = await page.locator('#sec-pref .sw[data-ns="notifyTrip"].on').count();
  if (p0.notifyTrip && swOn !== 1) bail('notifyTrip switch should be on');
  const memberSwitch = page.locator('.sw[data-ns="notifyMember"]');
  const notifyMember = !p0.notifyMember;
  console.log('prefs load OK:', p0.theme, p0.currency, '| switches 3');
  // edit: theme→dark, unit→mi, member invitations toggled
  await page.selectOption('#prTheme', 'dark');
  await page.selectOption('#prUnit', 'mi');
  await memberSwitch.click();
  if (await page.locator('#btnSavePref').isDisabled()) bail('save should enable after edits');
  await page.click('#btnSavePref');
  await page.waitForTimeout(700);
  const saved = await apiFresh.get('/api/auth/preferences');
  if (saved.preferences.theme !== 'dark' || saved.preferences.distanceUnit !== 'mi'
      || saved.preferences.notifyMember !== notifyMember || saved.preferences.notifySummary !== p0.notifySummary) {
    bail('saved prefs wrong: ' + JSON.stringify(saved.preferences));
  }
  // reload → persisted in UI
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.getElementById('hpName').textContent !== '…', { timeout: 8000 });
  await page.waitForTimeout(600);
  if ((await page.locator('#prTheme').inputValue()) !== 'dark') bail('theme not persisted in UI after reload');
  if ((await memberSwitch.getAttribute('aria-checked')) !== String(notifyMember)) {
    bail('notifyMember not persisted in UI');
  }
  // restore defaults
  await page.selectOption('#prTheme', p0.theme === 'dark' ? 'system' : p0.theme);
  await page.selectOption('#prUnit', p0.distanceUnit === 'mi' ? 'km' : p0.distanceUnit);
  await memberSwitch.click();
  await page.click('#btnSavePref');
  await page.waitForTimeout(700);
  const restored = await apiFresh.get('/api/auth/preferences');
  if (restored.preferences.theme === 'dark' || restored.preferences.distanceUnit === 'mi'
      || restored.preferences.notifyMember !== p0.notifyMember || restored.preferences.notifySummary !== p0.notifySummary) {
    bail('restore failed: ' + JSON.stringify(restored.preferences));
  }
  console.log('prefs OK: load/edit/save/persist/restore, now =', restored.preferences.theme, restored.preferences.distanceUnit);
  await page.screenshot({ path: path.join(SHOTS, 'prefs.png'), fullPage: true });

  await page.screenshot({ path: path.join(SHOTS, 'account.png'), fullPage: true });

  // ---- export JSON (real download) ----
  console.log('--- export JSON ---');
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 90000 }),
    page.click('#btnExport')
  ]);
  const fname = download.suggestedFilename();
  if (!/^travel-os-export-\d{4}-\d{2}-\d{2}\.json$/.test(fname)) bail('export filename wrong: ' + fname);
  const savePath = path.join(SHOTS, fname);
  await download.saveAs(savePath);
  const raw = fs.readFileSync(savePath, 'utf8');
  const data = JSON.parse(raw);
  if (!data.trips || !data.trips.length) bail('export has no trips');
  const t0 = data.trips[0];
  if (!t0.trip || !t0.trip.id) bail('export trip shape wrong');
  ['days', 'expenses', 'bookings', 'packingLists', 'journals', 'photos', 'documents', 'members'].forEach(function (k) {
    if (!Array.isArray(t0[k])) bail('export missing array field: ' + k);
  });
  const photoLeak = JSON.stringify(t0.photos).includes('data:image');
  if (photoLeak) bail('export should NOT contain base64 image data');
  console.log('export OK:', fname, '|', (raw.length / 1024).toFixed(1), 'KB |', data.trips.length, 'trips | photos meta only');

  // ---- danger gating ----
  console.log('--- danger gating ---');
  const btn = page.locator('#btnDeactivate');
  if (!(await btn.isDisabled())) bail('deactivate should start disabled');
  await page.fill('#dfPassword', 'whatever');
  if (!(await btn.isDisabled())) bail('deactivate should stay disabled without checkbox');
  await page.check('#dfConfirm');
  if (await btn.isDisabled()) bail('deactivate should enable with password + checkbox');
  await page.fill('#dfPassword', '');
  if (!(await btn.isDisabled())) bail('deactivate should disable again when password cleared');
  await page.check('#dfConfirm'); // no-op
  await page.uncheck('#dfConfirm');
  console.log('danger gating OK (never submitted)');

  // ---- logout (last, clears token) ----
  console.log('--- logout ---');
  page.once('dialog', d => d.accept());
  await page.click('#btnLogout');
  try {
    await page.waitForURL(/\/login\//, { timeout: 15000 });
  } catch (e) { bail('logout did not navigate to /login/'); }
  const tok = await page.evaluate(() => localStorage.getItem('travelos.token'));
  if (tok) bail('token should be cleared after logout, got ' + tok);
  console.log('logout OK: navigated to /login/, token cleared');

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
