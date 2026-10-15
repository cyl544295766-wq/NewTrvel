// Verify trip-detail page via Playwright + system Chrome
// usage: node verify-trip-detail.cjs
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const TOKEN_PATH = path.resolve('D:/tmp/login.json');

function bail(msg, code = 1) {
  console.error('FAIL: ' + msg);
  process.exit(code);
}

async function main() {
  if (!fs.existsSync(TOKEN_PATH)) bail('missing login.json (run curl first)');
  const { token, user } = JSON.parse(fs.readFileSync(TOKEN_PATH, 'utf8'));
  if (!token || !user) bail('login.json missing token/user');

  const TRIP_ID = 'cmrykzwh90000fmvg8xr7bclf';
  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
  });

  // Seed token + user into localStorage BEFORE the app boots
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });

  const page = await ctx.newPage();

  // console + request log
  page.on('console', m => {
    const t = m.type();
    if (t === 'error' || t === 'warning') console.log('[browser ' + t + ']', m.text());
  });
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  page.on('requestfailed', r => console.log('[reqfail]', r.url(), r.failure() && r.failure().errorText));
  page.on('response', r => {
    if (r.status() >= 400) console.log('[http ' + r.status() + ']', r.url());
  });

  // 1. login page sanity (clear token so we don't auto-redirect)
  console.log('--- step 1: visit /login/ (no token) ---');
  const ctx2 = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  const anonPage = await ctx2.newPage();
  await anonPage.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
  await anonPage.waitForTimeout(700);
  const loginVisible = await anonPage.locator('form#form, .auth .card').first().isVisible().catch(() => false);
  if (!loginVisible) bail('login form not visible (anon)');
  console.log('login form OK');
  await ctx2.close();

  // 2. navigate directly to detail
  console.log('--- step 2: visit /trip-detail/?id=' + TRIP_ID + ' ---');
  await page.goto(ORIGIN + '/trip-detail/?id=' + TRIP_ID, { waitUntil: 'domcontentloaded' });
  // wait for pass to unhide
  await page.waitForSelector('#pass:not([hidden])', { timeout: 8000 });
  // wait for modules to render
  await page.waitForSelector('#modules button.module', { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(1200); // settle promise.allSettled

  // 3. boarding pass assertions
  const title = (await page.locator('.pass-title').first().textContent({ timeout: 4000 }).catch(() => null)) || '';
  console.log('pass-title =', title.trim());
  if (!title.includes('赤峰')) bail('expected title contains 赤峰, got: ' + title);

  const fields = await page.locator('.fields .field .v').allTextContents();
  console.log('field values =', fields);

  const dayCount = await page.locator('.stub-days').first().textContent({ timeout: 2000 }).catch(() => 'n/a');
  console.log('days =', dayCount);

  // 4. module tiles (8)
  const modulesHTML = await page.locator('#modules').innerHTML().catch(() => '');
  console.log('#modules html len =', modulesHTML.length, modulesHTML.slice(0, 200).replace(/\s+/g, ' '));
  const tiles = await page.locator('#modules button.module').count();
  console.log('module tile count =', tiles);
  if (tiles < 6) bail('expected >=6 module tiles, got ' + tiles);

  // 5. weather panel: either real cards OR fallback message
  const weatherHasContent = await page.locator('#weatherBox').innerHTML();
  console.log('weather box len =', weatherHasContent.length, weatherHasContent.slice(0, 120).replace(/\s+/g, ' '));

  // 6. members tab
  console.log('--- step 3: switch to 成员 tab ---');
  await page.click('#tab-members');
  await page.waitForSelector('#pane-members:not([hidden])', { timeout: 3000 });
  await page.waitForTimeout(500);
  const memberCount = await page.locator('#memberList .member').count();
  console.log('member count =', memberCount);
  if (memberCount < 1) bail('expected at least 1 member row');

  // 7. screenshot overview
  await page.click('#tab-overview');
  await page.waitForTimeout(500);
  const shotDir = path.resolve('D:/tmp/trip-detail-shots');
  fs.mkdirSync(shotDir, { recursive: true });
  await page.screenshot({ path: path.join(shotDir, 'overview.png'), fullPage: true });
  console.log('shot -> overview.png');

  await page.click('#tab-members');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(shotDir, 'members.png'), fullPage: true });
  console.log('shot -> members.png');

  // 8. mobile viewport shot
  await page.setViewportSize({ width: 390, height: 844 });
  await page.click('#tab-overview');
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(shotDir, 'mobile.png'), fullPage: true });
  console.log('shot -> mobile.png');

  await browser.close();
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
