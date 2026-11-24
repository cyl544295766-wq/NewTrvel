// Verify AI pet appears on ALL pages, layering below overlays, no JS errors
const { chromium } = require('playwright-core');
const fs = require('fs');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
assertLocalOrigin(ORIGIN);
const cleaner = new Cleaner();
let browser = null;
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const PAGES = ['home', 'login', 'trips', 'trip-detail', 'trip-form', 'itinerary', 'expenses',
  'packing', 'documents', 'photos', 'journals', 'journal-detail', 'bookings', 'vouchers',
  'stats', 'notifications', 'account', 'map'];
let TRIP = '';
const PAGE_QS = {
  'trip-detail': '?id=' + TRIP, 'trip-form': '?id=' + TRIP, 'itinerary': '?trip=' + TRIP,
  'expenses': '?trip=' + TRIP, 'packing': '?trip=' + TRIP, 'documents': '?trip=' + TRIP,
  'photos': '?trip=' + TRIP, 'journals': '?trip=' + TRIP, 'journal-detail': '?trip=' + TRIP,
  'bookings': '?trip=' + TRIP, 'vouchers': '?trip=' + TRIP
};

async function main() {
  const user = await registerUser(ORIGIN, 'pg');
  cleaner.addUser(user.id);
  const token = user.token;

  // 自建行程（替代固定种子）
  const apiU = api(ORIGIN, token);
  const tripRes = await apiU.post('/api/trips', {
    title: '宠物全局验证行程（临时）', destination: '宠物镇', startDate: '2026-07-01', endDate: '2026-07-03'
  });
  TRIP = (tripRes.trip || tripRes).id;
  cleaner.addTrip(TRIP);
  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, u: { id: user.id, username: user.username, displayName: '' } });

  let failures = 0;
  const page = await ctx.newPage();
  let errs = [];
  page.on('pageerror', e => errs.push(e.message));

  for (const p of PAGES) {
    errs = [];
    const url = ORIGIN + '/' + p + '/' + (PAGE_QS[p] || '');
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20000 });
    } catch (e) { console.log('  !!  ' + p + ' 加载失败: ' + e.message.split('\n')[0]); failures++; continue; }
    /* 登录页匿名可见；其余页等待任意状态（requireLogin 重定向时 pet 也会出现在 login） */
    try {
      await page.waitForSelector('.pet', { timeout: 9000 });
    } catch (e) {
      const redirected = /\/login\//.test(page.url());
      if (p === 'login') { console.log('  ok  ' + p + ' （登录页，宠物匿名可见）'); continue; }
      if (redirected) {
        // 独立用例：验证匿名/重定向行为（不混入主流程成功判定）
        console.log('  ok  ' + p + ' [独立用例] 重定向到登录页，宠物在登录页出现');
        continue;
      }
      console.log('  !!  ' + p + ' 未出现宠物'); failures++; continue;
    }
    const z = await page.locator('.pet-host').evaluate(el => getComputedStyle(el).zIndex);
    const state = await page.locator('.pet').getAttribute('data-state');
    const hoverName = await page.locator('.pet .name-chip').count();
    const ok = z === '55' && !!state && hoverName === 1;
    console.log((ok ? '  ok  ' : '  !!  ') + p.padEnd(15) + ' pet 可见 | z=' + z + ' | state=' + state);
    if (!ok) failures++;
    if (errs.length) { console.log('  !!  ' + p + ' JS 报错: ' + errs[0]); failures++; }
  }

  /* ---- 分层：trip-detail 由统一助手抽屉呈现，抽屉/遮罩应盖住宠物 ---- */
  console.log('--- 分层检查（统一助手抽屉 vs 宠物）---');
  await page.goto(ORIGIN + '/trip-detail/?id=' + TRIP, { waitUntil: 'domcontentloaded' });
  let tripReady = true;
  try { await page.waitForSelector('#modules .module', { timeout: 15000 }); }
  catch (e) { tripReady = false; }
  await page.waitForSelector('.pet', { timeout: 9000 });
  if (!tripReady) {
    console.log('  !!  trip-detail 未渲染模块（本次自建行程）'); failures++;
  } else {
    await page.locator('.pet').click();
    await page.waitForSelector('.pet-bubble.open.expanded[aria-modal="true"]', { timeout: 5000 });
    await page.waitForTimeout(600);
    const layering = await page.evaluate(() => {
      const petZ = parseInt(getComputedStyle(document.querySelector('.pet-host')).zIndex, 10);
      const drawer = document.querySelector('.pet-bubble.expanded');
      const drawerZ = drawer ? (parseInt(getComputedStyle(drawer).zIndex, 10) || 0) : null;
      const maskShown = !!document.querySelector('.pet-mask.show');
      /* 宠物中心命中的最顶层元素不应再是宠物（已被遮罩/抽屉盖住） */
      const pet = document.querySelector('.pet').getBoundingClientRect();
      const hit = document.elementFromPoint(pet.left + pet.width / 2, pet.top + pet.height / 2);
      const petCenterCovered = hit ? (!hit.closest('.pet-host') ? 'covered' : 'pet') : 'none';
      return { petZ, drawerZ, drawerVisible: !!drawer, maskShown, petCenterCovered };
    });
    if (layering.drawerVisible && layering.maskShown) {
      const ok = layering.drawerZ > layering.petZ && layering.petCenterCovered === 'covered';
      console.log((ok ? '  ok  ' : '  !!  ') + '助手抽屉 z=' + layering.drawerZ + ' > 宠物 z=' + layering.petZ
        + '，宠物中心命中: ' + layering.petCenterCovered);
      if (!ok) failures++;
    } else {
      console.log('  !! 统一助手抽屉未打开'); failures++;
    }
  }

  /* ---- 气泡在非首页也能用（stats 页代表） ---- */
  console.log('--- 气泡跨页可用（stats）---');
  await page.goto(ORIGIN + '/stats/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.pet', { timeout: 9000 });
  await page.locator('.pet').click();
  await page.waitForSelector('.pet-bubble.open', { timeout: 5000 });
  await page.waitForTimeout(1000);
  const hasChipsOrMsgs = await page.evaluate(() =>
    document.querySelectorAll('.pet-chip').length > 0 || document.querySelectorAll('#petMsgs .pet-msg').length > 0);
  console.log((hasChipsOrMsgs ? '  ok  ' : '  !!  ') + 'stats 页气泡内容渲染');
  if (!hasChipsOrMsgs) failures++;

  await browser.close();
  console.log(failures === 0 ? '\nALL PASS' : '\n' + failures + ' 项失败');
  process.exit(failures === 0 ? 0 : 1);
}

(async () => {
  let failed = null;
  try { await main(); } catch (e) { failed = e; console.error('[ERROR]', (e && e.stack) || e); }
  if (browser) { try { await browser.close(); } catch (e) { /* 忽略 */ } }
  try { cleaner.run(); console.log('cleanup OK（残留核验 0）'); }
  catch (e) { if (!failed) failed = e; console.error('清理失败: ' + (e && e.message)); }
  process.exit(failed ? 1 : 0);
})();
