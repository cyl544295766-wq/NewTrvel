'use strict';

const assert = require('node:assert/strict');
const { chromium } = require('playwright-core');
const { Cleaner, api, assertLocalOrigin, registerUser, waitFor } = require('./lib/fixture.cjs');

const origin = (process.env.E2E_ORIGIN || 'http://127.0.0.1:3001').replace(/\/+$/, '');
assertLocalOrigin(origin);
const cleaner = new Cleaner();

async function register(tag) {
  const account = await registerUser(origin, tag);
  cleaner.addUser(account.id);
  return account;
}

async function browserSession(browser, account) {
  const context = await browser.newContext({ timezoneId: 'Asia/Shanghai' });
  await context.addInitScript(({ token, id, username }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify({ id, username }));
  }, { token: account.token, id: account.id, username: account.username });
  return context.newPage();
}

async function invite(ownerApi, trip, member) {
  await ownerApi.post(trip + '/members', { username: member.username, role: 'member' });
}

async function textMatching(page, selector, pattern) {
  const locator = page.locator(selector);
  await locator.waitFor({ state: 'visible' });
  return waitFor(selector + ' ' + pattern, async () => {
    const text = await locator.textContent();
    return pattern.test(text) ? text : null;
  });
}

async function verifyJournalsAndExpenses(browser, owner, member, ownerApi, memberApi) {
  const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai',
    year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
  const { trip } = await ownerApi.post('/api/trips', {
    title: '游记与多币种验收', startDate: today, endDate: today, budget: '500'
  });
  cleaner.addTrip(trip.id);
  const path = '/api/trips/' + trip.id;
  await invite(ownerApi, path, member);
  await ownerApi.post(path + '/days/generate');

  for (let i = 1; i <= 3; i++) {
    const { journal } = await ownerApi.post(path + '/journals', {
      title: '可见验收游记 ' + i, content: '可见摘要 ' + i, visibility: 'public', isDraft: false
    });
    cleaner.addJournal(journal.id);
  }
  for (let i = 1; i <= 21; i++) {
    const { journal } = await ownerApi.post(path + '/journals', {
      title: '隐藏验收游记 ' + i, content: '隐藏验收摘要 ' + i, visibility: 'private', isDraft: false
    });
    cleaner.addJournal(journal.id);
  }

  const ledger = path + '/expenses';
  for (const currency of ['CNY', 'USD']) {
    const { expense } = await ownerApi.post(ledger, {
      title: currency + ' 验收账目', amount: '100.00', currency, category: 'food',
      spentAt: today, shareUserIds: [owner.id, member.id]
    });
    cleaner.addExpense(expense.id);
  }
  const { booking } = await ownerApi.post(path + '/bookings', {
    title: '美元验收预订', type: 'hotel', amount: '125.43', currency: 'USD'
  });
  cleaner.addBooking(booking.id);

  const reader = await browserSession(browser, member);
  const dashboard = await memberApi.get('/api/dashboard');
  const expected = ['可见验收游记 3', '可见验收游记 2', '可见验收游记 1'];
  assert.deepEqual(dashboard.recentJournals.map(j => j.title), expected);
  assert.doesNotMatch(JSON.stringify(dashboard), /隐藏验收(游记|摘要)/);
  await reader.goto(origin + '/home/');
  await reader.locator('#journalBody .journal-card').last().waitFor();
  assert.deepEqual(await reader.locator('#journalBody .journal-card .nm').allTextContents(), expected);
  assert.doesNotMatch(await reader.locator('#journalBody').textContent(), /隐藏验收(游记|摘要)/);
  console.log('TR-01 PASS · 首页真实渲染 3 条正确顺序的游记，过滤 21 条不可见记录');
  const homeUsd = reader.locator('#expBody .exp-row', { hasText: 'USD 验收账目' });
  await homeUsd.waitFor();
  assert.match(await homeUsd.locator('.amt').textContent(), /USD 100/);
  assert.doesNotMatch(await homeUsd.locator('.amt').textContent(), /¥/);
  await textMatching(reader, '#stSpent', /^100$/);
  const brief = await textMatching(reader, '#briefBody .tb-summary', /100\.00 CNY/);
  assert.match(brief, /100\.00 USD/);
  assert.doesNotMatch(brief, /200(?:\.00)? (?:CNY|USD)/);

  const page = await browserSession(browser, owner);
  await page.goto(origin + '/expenses/?trip=' + trip.id);
  const usd = page.locator('.exp-card', { hasText: 'USD 验收账目' });
  await usd.waitFor();
  assert.match(await usd.locator('.exp-amt').textContent(), /USD 100/);
  assert.doesNotMatch(await usd.textContent(), /¥/);
  assert.match(await usd.locator('.exp-shares').textContent(), /USD 50/);
  const cny = page.locator('.exp-card', { hasText: 'CNY 验收账目' });
  assert.match(await cny.locator('.exp-amt').textContent(), /¥ 100/);
  await textMatching(page, '#wForeignEstimated', /USD 125\.43/);
  await textMatching(page, '#wSpent', /¥ 100/);
  await textMatching(page, '#wRemain', /¥ 400/);
  await page.locator('#tab-report').click();
  const report = await textMatching(page, '#rpCurrency', /其他币种/);
  assert.match(report, /USD 100/);
  assert.doesNotMatch(report, /200/);
  await page.locator('#tab-stl').click();
  assert.deepEqual((await page.locator('#stlTransfers h3').allTextContents()).sort(), ['CNY', 'USD']);
  assert.match(await page.locator('#stlTransfers').textContent(), /USD 50/);
  assert.match(await page.locator('#stlTransfers').textContent(), /¥ 50/);
  assert.equal(await page.locator('#btnConfirmStl').isDisabled(), true);
  assert.match(await page.locator('#stlHint').textContent(), /多种币种/);
  console.log('TR-02 PASS · 首页简报、费用明细、报表、结算预览及预算均保留原币');
  return page;
}

async function verifyReschedule(page, ownerApi, memberApi, member) {
  const { trip } = await ownerApi.post('/api/trips', {
    title: '改期验收', startDate: '2027-06-01', endDate: '2027-06-03'
  });
  cleaner.addTrip(trip.id);
  const path = '/api/trips/' + trip.id;
  await invite(ownerApi, path, member);
  const { days } = await ownerApi.post(path + '/days/generate');
  days.forEach(day => cleaner.addDay(trip.id, day.id));
  let patches = 0;
  let previews = 0;
  page.on('request', req => {
    if (req.method() === 'PATCH' && req.url() === origin + path) patches++;
    if (req.url().startsWith(origin + path + '/date-change-preview?')) previews++;
  });
  await page.goto(origin + '/trip-form/?id=' + trip.id);
  await page.waitForFunction(() => document.querySelector('#inEnd').value === '2027-06-03');
  await page.locator('#inTitle').fill('改期验收 · 标题修改');
  await page.locator('#inBudget').fill('500');
  await page.locator('#btnSave').click();
  await page.waitForURL('**/trip-detail/?id=' + trip.id);
  assert.equal(previews, 0);
  assert.equal(patches, 1);
  assert.equal((await ownerApi.get(path)).trip.budget, '500.00');
  patches = 0;

  await page.goto(origin + '/trip-form/?id=' + trip.id);
  await page.waitForFunction(() => document.querySelector('#inEnd').value === '2027-06-03');
  await page.locator('#inEnd').fill('2027-06-01');
  await page.locator('#btnSave').click();
  const dialog = page.locator('#dateImpact');
  await textMatching(page, '#dateImpactBody', /移除空白天 2 天/);
  await dialog.getByRole('button', { name: '返回编辑' }).click();
  await dialog.waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#inEnd').inputValue(), '2027-06-01');
  assert.equal(patches, 0);
  assert.equal((await ownerApi.get(path + '/days')).days.length, 3);
  assert.match((await ownerApi.get(path)).trip.endDate, /^2027-06-03/);

  await page.locator('#btnSave').click();
  await textMatching(page, '#dateImpactBody', /移除空白天 2 天/);
  const { expense } = await memberApi.post(path + '/expenses', {
    amount: '12.34', title: '另一会话新增费用', tripDayId: days[2].id, spentAt: '2027-06-03'
  });
  cleaner.addExpense(expense.id);
  const conflict = page.waitForResponse(response => response.url() === origin + path
    && response.request().method() === 'PATCH' && response.status() === 409);
  await dialog.getByRole('button', { name: '确认并保存' }).click();
  await conflict;
  const fresh = await textMatching(page, '#dateImpactBody', /请重新核对这份预览/);
  assert.match(fresh, /保留 1 天/);
  assert.match(fresh, /移除空白天 1 天/);
  assert.equal(patches, 1);
  assert.match((await ownerApi.get(path)).trip.endDate, /^2027-06-03/);
  await dialog.getByRole('button', { name: '确认并保存' }).click();
  await page.waitForURL('**/trip-detail/?id=' + trip.id);
  assert.equal(patches, 2);
  assert.match((await ownerApi.get(path)).trip.endDate, /^2027-06-01/);
  assert.deepEqual((await ownerApi.get(path + '/days')).days.map(day => day.id), [days[0].id, days[2].id]);
  assert.equal((await ownerApi.get(path + '/expenses')).expenses[0].tripDayId, days[2].id);
  console.log('TR-03 PASS · 改期可取消、旧预览拒绝、刷新后二次确认、关联原样保留');
}

async function main() {
  let browser;
  try {
    const owner = await register('r1o');
    const member = await register('r1m');
    const ownerApi = api(origin, owner.token);
    const memberApi = api(origin, member.token);
    await ownerApi.post('/api/friends', { username: member.username });
    await memberApi.post('/api/friends/' + owner.id + '/accept');
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await verifyJournalsAndExpenses(browser, owner, member, ownerApi, memberApi);
    await verifyReschedule(page, ownerApi, memberApi, member);
  } finally {
    try {
      if (browser) await browser.close();
    } finally {
      cleaner.run();
      console.log('夹具清理通过 · 测试账号、行程及已登记子资源残留为 0');
    }
  }
}

main()
  .then(() => console.log('ALL PASS'))
  .catch(error => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
