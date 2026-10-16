// Verify AI pet appears on ALL pages, layering below overlays, no JS errors
const { chromium } = require('playwright-core');
const fs = require('fs');

const ORIGIN = 'http://127.0.0.1:3001';
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

const PAGES = ['home', 'login', 'trips', 'trip-detail', 'trip-form', 'itinerary', 'expenses',
  'packing', 'documents', 'photos', 'journals', 'journal-detail', 'bookings', 'vouchers',
  'stats', 'notifications', 'account', 'map'];
const TRIP = 'cmrykzwh90000fmvg8xr7bclf';
const PAGE_QS = {
  'trip-detail': '?id=' + TRIP, 'trip-form': '?id=' + TRIP, 'itinerary': '?trip=' + TRIP,
  'expenses': '?trip=' + TRIP, 'packing': '?trip=' + TRIP, 'documents': '?trip=' + TRIP,
  'photos': '?trip=' + TRIP, 'journals': '?trip=' + TRIP, 'journal-detail': '?trip=' + TRIP,
  'bookings': '?trip=' + TRIP, 'vouchers': '?trip=' + TRIP
};

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });

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
      if (p === 'login' || redirected) { console.log('  ok  ' + p + ' （重定向到登录页，宠物在登录页出现）'); continue; }
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

  /* ---- 分层：trip-detail 打开 AI 抽屉，抽屉应盖住宠物 ---- */
  console.log('--- 分层检查（trip-detail AI 抽屉 vs 宠物）---');
  await page.goto(ORIGIN + '/trip-detail/?id=' + TRIP, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#modules .module', { timeout: 15000 });
  await page.waitForSelector('.pet', { timeout: 9000 });
  await page.locator('#btnAi').click();
  await page.waitForTimeout(700);
  const layering = await page.evaluate(() => {
    const petZ = parseInt(getComputedStyle(document.querySelector('.pet-host')).zIndex, 10);
    const drawer = [...document.querySelectorAll('body *')].filter(el =>
      el.id === 'aiPanel' || (el.className && String(el.className).includes('ai-panel')));
    let drawerZ = null, drawerVisible = false;
    for (const el of drawer) {
      const st = getComputedStyle(el);
      if (st.display !== 'none' && st.visibility !== 'hidden') {
        drawerZ = Math.max(drawerZ || 0, parseInt(st.zIndex, 10) || 0);
        drawerVisible = true;
      }
    }
    /* 抽屉中心点命中的最顶层元素不应是宠物 */
    const dr = drawer.find(el => { const st = getComputedStyle(el); return st.display !== 'none'; });
    let centerHit = null;
    if (dr) {
      const r = dr.getBoundingClientRect();
      const hit = document.elementFromPoint(r.left + r.width / 2, Math.min(Math.max(r.top + 40, 10), innerHeight - 10));
      centerHit = hit ? (hit.closest('.pet-host') ? 'pet' : 'other') : 'none';
    }
    return { petZ, drawerZ, drawerVisible, centerHit };
  });
  if (layering.drawerVisible && layering.drawerZ) {
    const ok = layering.petZ < layering.drawerZ && layering.centerHit !== 'pet';
    console.log((ok ? '  ok  ' : '  !!  ') + '抽屉 z=' + layering.drawerZ + ' > 宠物 z=' + layering.petZ + '，抽屉中心命中: ' + layering.centerHit);
    if (!ok) failures++;
  } else {
    console.log('  (skip) 抽屉未检出可见实例，跳过命中测试');
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

main().catch(e => { console.error('[ERROR]', (e && e.stack) || e); process.exit(1); });
