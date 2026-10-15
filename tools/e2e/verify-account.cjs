// Verify account: profile from /me / export JSON download / disabled-prefs honesty / danger gating / logout
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/account-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
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
  }, { token, user });
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

  // security: logout present, change-password disabled + badge
  if (!(await page.locator('#btnLogout').count())) bail('logout button missing');
  const cpBtn = page.locator('#sec-security .row-item', { hasText: '修改密码' }).locator('button');
  if (!(await cpBtn.isDisabled())) bail('change-password button should be disabled (no backend)');
  const soonBadges = await page.locator('.pill-soon').count();
  if (soonBadges < 3) bail('expected >=3 待接入 badges, got ' + soonBadges);
  console.log('security OK: logout available, change-password disabled +', soonBadges, 'badges');

  // prefs: all selects disabled + notice
  const disabledSelects = await page.locator('#sec-pref select:disabled').count();
  if (disabledSelects !== 6) bail('prefs expected 6 disabled selects, got ' + disabledSelects);
  if (!(await page.locator('#sec-pref .notice').count())) bail('prefs notice missing');
  const lockedSw = await page.locator('#sec-pref .sw.locked').count();
  if (lockedSw !== 1) bail('locked notification switch missing');
  console.log('prefs OK: 6 disabled selects + notice + locked switch');

  await page.screenshot({ path: path.join(SHOTS, 'account.png'), fullPage: true });

  // ---- export JSON (real download) ----
  console.log('--- export JSON ---');
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
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
    await page.waitForURL(/\/login\//, { timeout: 8000 });
  } catch (e) { bail('logout did not navigate to /login/'); }
  const tok = await page.evaluate(() => localStorage.getItem('travelos.token'));
  if (tok) bail('token should be cleared after logout, got ' + tok);
  console.log('logout OK: navigated to /login/, token cleared');

  await browser.close();

  // ---- restore token file for later sessions ----
  const re = await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  });
  const rj = await re.json();
  if (rj.token) {
    fs.writeFileSync('D:/tmp/login.json', JSON.stringify(rj, null, 2));
    fs.writeFileSync('D:/tmp/token.txt', rj.token);
    console.log('token file refreshed (login still works)');
  } else {
    bail('re-login failed: ' + JSON.stringify(rj));
  }
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
