const { chromium } = require('playwright-core');
const fs = require('fs');

const ORIGIN = 'http://127.0.0.1:3001';
const OUT = 'D:/NewTrevl/docs/ui-audit';

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const loginResponse = await fetch(ORIGIN + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'admin123' })
  });
  const login = await loginResponse.json();
  if (!login.token) throw new Error('登录失败，无法截取首页');

  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
    args: ['--no-sandbox', '--no-proxy-server']
  });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1
  });
  await context.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token: login.token, user: login.user || { username: 'admin' } });
  const page = await context.newPage();
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => !document.querySelector('#tripBody .skeleton'), { timeout: 15000 });
  await page.waitForTimeout(1800);
  await page.evaluate(async () => {
    for (let y = 0; y < document.documentElement.scrollHeight; y += 600) {
      window.scrollTo(0, y);
      await new Promise(resolve => setTimeout(resolve, 120));
    }
    window.scrollTo(0, 0);
    await new Promise(resolve => setTimeout(resolve, 500));
  });
  await page.screenshot({ path: OUT + '/home-desktop-full.png', fullPage: true });

  const desktop = await page.evaluate(() => {
    const rect = (selector) => {
      const el = document.querySelector(selector);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { width: Math.round(r.width), height: Math.round(r.height) };
    };
    return {
      viewport: { width: innerWidth, height: innerHeight },
      documentWidth: document.documentElement.scrollWidth,
      documentHeight: document.documentElement.scrollHeight,
      hero: rect('.hero'),
      stats: rect('.stats'),
      aiForm: rect('.ag-form'),
      topbar: rect('.tb-inner'),
      navItems: document.querySelectorAll('.tb-nav a').length,
      reducedMotionRule: matchMedia('(prefers-reduced-motion: reduce)').matches
    };
  });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(700);
  await page.screenshot({ path: OUT + '/home-mobile-full.png', fullPage: true });
  const mobile = await page.evaluate(() => {
    const form = document.querySelector('.ag-form');
    const nav = document.querySelector('.tb-nav');
    const stat = document.querySelector('.stat');
    const countdown = document.querySelector('.countdown');
    return {
      viewport: { width: innerWidth, height: innerHeight },
      documentWidth: document.documentElement.scrollWidth,
      documentHeight: document.documentElement.scrollHeight,
      formWidth: form && Math.round(form.getBoundingClientRect().width),
      formHeight: form && Math.round(form.getBoundingClientRect().height),
      navClientWidth: nav && nav.clientWidth,
      navScrollWidth: nav && nav.scrollWidth,
      statWidth: stat && Math.round(stat.getBoundingClientRect().width),
      statHeight: stat && Math.round(stat.getBoundingClientRect().height),
      countdownHeight: countdown && Math.round(countdown.getBoundingClientRect().height),
      visibleHeroIcons: [...document.querySelectorAll('.hero-icons .fx')].filter(el => getComputedStyle(el).display !== 'none').length,
      smallTouchTargets: [...document.querySelectorAll('a,button,[role="button"]')].filter(el => {
        const r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && r.height < 40;
      }).slice(0, 20).map(el => ({ text: (el.textContent || '').trim().slice(0, 24), height: Math.round(el.getBoundingClientRect().height) }))
    };
  });
  fs.writeFileSync(OUT + '/layout-metrics.json', JSON.stringify({ desktop, mobile }, null, 2));
  await browser.close();
  console.log(JSON.stringify({ desktop, mobile }, null, 2));
})().catch(error => {
  console.error(error);
  process.exit(1);
});
