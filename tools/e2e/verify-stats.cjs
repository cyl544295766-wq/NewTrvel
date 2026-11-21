// Travel OS · verify-stats：统计页端到端验证（自包含夹具，不依赖个人账号/种子数据）
// 覆盖：年份 pill（最新年份默认选中）/ 四卡片与 /api/stats/yearly 一致 / 年度柱图 / 月度柱图 /
//       分类环形（金额与夹具一致）/ highlights 最常去 / 年份表 + 表行下钻联动 / 移动端截图。
// 夹具：自注册用户 + 2025/2026 各一行程 + CNY 费用；结束按准确 ID 清理并核验残留。
// 运行：NODE_PATH=<managed node_modules> node tools/e2e/verify-stats.cjs
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');
const { assertLocalOrigin, registerUser, api, waitFor, Cleaner } = require('./lib/fixture.cjs');

const ORIGIN = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
const SHOTS = process.env.E2E_SHOTS || 'D:/tmp/stats-shots';
assertLocalOrigin(ORIGIN);

const cleaner = new Cleaner();
let biz = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { biz++; console.error('  !!  ' + msg); } }
function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

let browser = null;

async function main() {
  fs.mkdirSync(SHOTS, { recursive: true });

  // ---- 夹具：自注册 + 两年份行程 + CNY 费用 ----
  const owner = await registerUser(ORIGIN, 'st');
  cleaner.addUser(owner.id);
  const apiOwner = api(ORIGIN, owner.token);
  const t1 = cleaner.addTrip((await apiOwner.post('/api/trips', {
    title: '统计验证·2025', destination: '基线谷', startDate: '2025-05-10', endDate: '2025-05-14'
  })).trip.id);
  const t2 = cleaner.addTrip((await apiOwner.post('/api/trips', {
    title: '统计验证·2026', destination: '回声湾', startDate: '2026-03-01', endDate: '2026-03-05'
  })).trip.id);
  const e1 = await apiOwner.post(`/api/trips/${t1}/expenses`, {
    amount: '1500', category: 'food', title: '统计验证餐饮', payerUserId: owner.id, shareUserIds: [owner.id]
  });
  cleaner.addExpense(e1.expense ? e1.expense.id : e1.id);
  const e2 = await apiOwner.post(`/api/trips/${t2}/expenses`, {
    amount: '2000', category: 'hotel', title: '统计验证住宿', payerUserId: owner.id, shareUserIds: [owner.id]
  });
  cleaner.addExpense(e2.expense ? e2.expense.id : e2.id);
  ok('夹具就绪（2025+2026 各 1 行程，CNY 费用 1500/2000）');

  browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token: owner.token, user: { id: owner.id, username: owner.username, displayName: '' } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.log('[pageerror]', e.message));

  console.log('--- 加载 /stats/ ---');
  await page.goto(ORIGIN + '/stats/', { waitUntil: 'domcontentloaded' });
  await waitFor('年份 pill 渲染', () => page.locator('#yearPill button').count().then((n) => n >= 2).catch(() => false), 15000);
  await waitFor('卡片渲染', () => page.locator('#sTrips').textContent().then((t) => t.trim().length > 0).catch(() => false), 10000);

  const pills = (await page.locator('#yearPill button').allTextContents()).map((s) => s.trim());
  check(pills.length === 2 && pills.includes('2026') && pills.includes('2025'),
    '年份 pill = 夹具两年份: ' + pills.join('/'));
  const active = (await page.locator('#yearPill button[aria-pressed="true"]').textContent()).trim();
  check(active === '2026', '默认选中最新年份 2026: ' + active);

  // 四卡片 vs API（真实数据一致性）
  const yearly = await apiOwner.get('/api/stats/yearly?year=2026');
  const tripsTxt = (await page.locator('#sTrips').textContent()).trim();
  const daysTxt = (await page.locator('#sDays').textContent()).trim();
  const destTxt = (await page.locator('#sDest').textContent()).trim();
  const costTxt = (await page.locator('#sCost').textContent()).trim();
  check(tripsTxt.startsWith(String(yearly.tripCount)), 'tripCount 卡片 = API: ' + tripsTxt + ' vs ' + yearly.tripCount);
  check(daysTxt.startsWith(String(yearly.totalDays)), 'totalDays 卡片 = API: ' + daysTxt + ' vs ' + yearly.totalDays);
  check(destTxt.startsWith(String(yearly.destinationCount)), 'dest 卡片 = API: ' + destTxt + ' vs ' + yearly.destinationCount);
  check(costTxt.includes('¥'), 'cost 卡片含 ¥: ' + costTxt);

  // 年度柱图：2 列 × 2 条 + 图例
  const cols = await page.locator('#chartYear .bar-col').count();
  const bars = await page.locator('#chartYear .bar').count();
  const legend = await page.locator('#chartYear .legend span').count();
  check(cols === 2 && bars === 4 && legend === 2,
    '年度柱图 2 列 × 2 条 + 图例（实为 ' + cols + '/' + bars + '/' + legend + '）');

  // 月度柱图：12 列，2026 年 3 月活跃
  const mos = await page.locator('#chartMonth .mo').count();
  const hasMonths = await page.locator('#chartMonth .mo.has').count();
  check(mos === 12, '月度柱图 12 列（实为 ' + mos + '）');
  check(hasMonths === 1, '2026 年活跃月份 = 1（3 月，实为 ' + hasMonths + '）');

  // 分类环形：全局（跨年份）= 夹具两笔 CNY（1500 餐饮 + 2000 住宿）
  // 页面 money() 契约：¥ 1,500 / ¥ 2,000（千分位、无强制小数）
  await waitFor('分类环形含夹具金额', () => page.locator('#chartCat').textContent()
    .then((t) => t.includes('¥ 1,500') && t.includes('¥ 2,000') && t.includes('¥ 3,500'))
    .catch(() => false), 10000);
  const catRows = await page.locator('#chartCat .cat-row').count();
  const segs = await page.locator('#chartCat svg circle').count();
  check(catRows >= 2 && segs >= 1, '分类环形行/段 ≥2/≥1（实为 ' + catRows + '/' + segs + '）');

  // highlights：最常去 = 两个目的地之一（并列 1 次，取先出现的「基线谷」）
  const hls = await page.locator('#highlights .hl').count();
  const hlTxt = (await page.locator('#highlights').textContent()).replace(/\s+/g, ' ');
  check(hls === 3, 'highlights 3 张卡（实为 ' + hls + '）');
  check(hlTxt.includes('基线谷') || hlTxt.includes('回声湾'), '最常去为夹具目的地: ' + hlTxt.slice(0, 100));

  // 年份表：2 行 × 3 mini 条；行内下钻联动
  const rowsT = await page.locator('#yearTable tbody tr').count();
  check(rowsT === 2, '年份表 2 行（实为 ' + rowsT + '）');
  const minis = await page.locator('#yearTable tbody tr').first().locator('.mini').count();
  check(minis === 3, '行内 3 mini 条（实为 ' + minis + '）');
  await page.screenshot({ path: path.join(SHOTS, 'overview.png'), fullPage: true });

  console.log('--- 下钻 2025 ---');
  await page.click('#yearTable tbody tr[data-year="2025"]');
  await waitFor('pill 切到 2025', () => page.locator('#yearPill button[aria-pressed="true"]')
    .textContent().then((t) => t.trim() === '2025').catch(() => false), 10000);
  const trips2025 = (await page.locator('#sTrips').textContent()).trim();
  const days2025 = (await page.locator('#sDays').textContent()).trim();
  const mayHas = await page.locator('#chartMonth .mo.has').count();
  check(trips2025.startsWith('1'), '2025 行程数 = 1: ' + trips2025);
  check(days2025.startsWith('5'), '2025 总天数 = 5: ' + days2025);
  check(mayHas === 1, '2025 活跃月份 = 1（5 月，实为 ' + mayHas + '）');
  await page.screenshot({ path: path.join(SHOTS, 'year-2025.png'), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(SHOTS, 'mobile.png'), fullPage: true });
  console.log('shots -> overview/year-2025/mobile.png');

  check(biz === 0, '业务断言失败数 = ' + biz);
  if (biz > 0) bail('存在业务断言失败');
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
