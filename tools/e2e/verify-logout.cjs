// Verify logout: sidebar entry on all pages / confirm → server logout → local cleanup → redirect / login edge / network-failure retry path
const { chromium } = require('playwright-core');
const fs = require('fs');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
assertLocalOrigin(ORIGIN);
const cleaner = new Cleaner();
let browser = null;
let failures = 0;
function check(cond, label) {
  console.log((cond ? '  ok  ' : '  !!  ') + label);
  if (!cond) failures++;
}

async function main() {
  const user = await registerUser(ORIGIN, 'lg');
  cleaner.addUser(user.id);
  const token = user.token;
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });

  /* ---- 1. 侧栏退出按钮全站存在 ---- */
  console.log('--- 1. 侧栏退出入口（抽样 5 页）');
  for (const p of ['/home/', '/trips/', '/stats/', '/map/', '/packing/']) {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
    await page.evaluate(([t, u]) => {
      localStorage.setItem('travelos.token', t);
      localStorage.setItem('travelos.user', JSON.stringify(u));
    }, [token, { id: user.id, username: user.username, displayName: '' }]);
    await page.goto(ORIGIN + p, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(400);
    const ok1 = await page.evaluate(() => {
      const b = document.getElementById('sideLogout');
      return !!b && getComputedStyle(b).display !== 'none';
    });
    check(ok1, p + ' 侧栏退出按钮可见');
    await page.close();
  }

  /* ---- 2. 正常退出：确认 → 调接口 → 清本地 → 跳登录页 ---- */
  console.log('--- 2. 正常退出流程');
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    let logoutApiCalled = false;
    page.on('request', r => { if (r.url().includes('/auth/logout')) logoutApiCalled = true; });
    page.on('dialog', d => d.accept());
    await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
    await page.evaluate(([t, u]) => {
      localStorage.setItem('travelos.token', t);
      localStorage.setItem('travelos.user', JSON.stringify(u));
    }, [token, { id: user.id, username: user.username, displayName: '' }]);
    await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
    await page.click('#sideLogout');
    await page.waitForURL('**/login/', { timeout: 15000 });
    const cleaned = await page.evaluate(() =>
      !localStorage.getItem('travelos.token') && !localStorage.getItem('travelos.user'));
    check(logoutApiCalled, '已调用 /auth/logout（清服务端 Cookie）');
    check(cleaned, '本地 token 与用户缓存已清除');
    check(page.url().includes('/login/'), '已跳转登录页');
    await page.close();
  }

  /* ---- 3. 接口失败（断网模拟）：重试后仍完成本地登出并跳转 ---- */
  console.log('--- 3. 登出接口失败 → 重试 → 本地兜底');
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    let attempts = 0;
    await page.route('**/api/auth/logout', r => { attempts++; r.abort(); });
    page.on('dialog', d => d.accept());
    await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
    await page.evaluate(([t, u]) => {
      localStorage.setItem('travelos.token', t);
      localStorage.setItem('travelos.user', JSON.stringify(u));
    }, [token, { id: user.id, username: user.username, displayName: '' }]);
    await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
    await page.click('#sideLogout');
    await page.waitForURL('**/login/', { timeout: 15000 });
    const cleaned = await page.evaluate(() => !localStorage.getItem('travelos.token'));
    check(attempts >= 2, '接口失败后自动重试（实测 ' + attempts + ' 次请求）');
    check(cleaned, '接口失败时本地凭证仍被清除（兜底生效）');
    check(page.url().includes('/login/'), '失败路径同样跳转登录页');
    await page.close();
  }

  /* ---- 4. 未登录边界：不调接口直接去登录页 ---- */
  console.log('--- 4. 未登录状态退出');
  {
    const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
    let called = false;
    page.on('request', r => { if (r.url().includes('/auth/logout')) called = true; });
    await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => { localStorage.clear(); });
    await page.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(300);
    const r = await page.evaluate(async () => {
      await performLogout();
      return { url: location.pathname, token: localStorage.getItem('travelos.token') };
    });
    check(!called, '未登录时不调用登出接口');
    check(r.url.includes('/login/') && !r.token, '未登录时直接跳登录页且无凭证残留');
    await page.close();
  }

  await browser.close();
  console.log(failures ? `\n${failures} FAILED` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
(async () => {
  let failed = null;
  try { await main(); } catch (e) { failed = e; console.error(e.stack || String(e)); }
  if (browser) { try { await browser.close(); } catch (e) { /* 忽略 */ } }
  try { cleaner.run(); console.log('cleanup OK（残留核验 0）'); }
  catch (e) { if (!failed) failed = e; console.error('清理失败: ' + (e && e.message)); }
  process.exit(failed ? 1 : 0);
})();
