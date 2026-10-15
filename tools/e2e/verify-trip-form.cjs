// Verify trip-form page: new + edit flows
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/trip-form-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  page.on('pageerror', e => console.log('[pageerror]', e.message));

  // ============ Flow 1: NEW ============
  console.log('--- flow 1: new trip form ---');
  await page.goto(ORIGIN + '/trip-form/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(600);

  if (!(await page.locator('#inTitle').isVisible())) bail('form not visible');
  // status card must be hidden in create mode
  if (await page.locator('#cardStatus').isVisible()) bail('status card should be hidden on create');
  console.log('create mode OK (no status card)');

  // empty preview placeholders
  const t0 = await page.locator('#pvTitle').textContent();
  if (t0 !== '未命名旅程') bail('empty preview title expected 未命名旅程, got ' + t0);
  if (!(await page.locator('#pvTitle').getAttribute('class')).includes('empty')) bail('preview title should be .empty');

  // fill fields
  await page.fill('#inTitle', '验证测试行程');
  await page.fill('#inDest', '呼伦贝尔');
  await page.fill('#inStart', '2026-11-01');
  await page.fill('#inEnd', '2026-11-05');

  // preview should update live
  const pt = (await page.locator('#pvTitle').textContent()).trim();
  if (pt !== '验证测试行程') bail('preview title not live: ' + pt);
  const pto = (await page.locator('#pvTo').textContent()).trim();
  if (pto !== '呼伦贝尔') bail('preview dest not live: ' + pto);
  const pd = (await page.locator('#pvDays').textContent()).trim();
  if (pd !== '5 天') bail('preview days expected 5 天, got ' + pd);
  console.log('live preview OK: title/dest/days(5 天)');

  // budget chip: click +5000 -> preview ¥ 5,000
  await page.click('#budgetChips button[data-add="5000"]');
  const pb = (await page.locator('#pvBudget').textContent()).trim();
  if (pb !== '¥ 5,000') bail('preview budget after chip expected ¥ 5,000, got ' + pb);
  console.log('budget chip OK:', pb);

  // checklist: should now have 4 on (title/dest/date/budget)
  const onCount = await page.locator('.ck.on').count();
  if (onCount !== 4) bail('checklist expected 4 on, got ' + onCount);
  const pct = (await page.locator('#ckPct').textContent()).trim();
  if (pct !== '80%') bail('checklist pct expected 80%, got ' + pct);
  console.log('checklist OK: 4/5 =', pct);

  // date order validation
  await page.fill('#inEnd', '2026-10-01');
  await page.waitForTimeout(200);
  const invalid = await page.locator('#fEnd.invalid').count();
  if (invalid !== 1) bail('end<start should mark #fEnd invalid');
  await page.fill('#inEnd', '2026-11-05');
  await page.waitForTimeout(200);
  if (await page.locator('#fEnd.invalid').count()) bail('#fEnd should clear invalid');
  console.log('date order validation OK');

  await page.screenshot({ path: path.join(SHOTS, 'new-filled.png'), fullPage: true });

  // save -> should create and navigate to detail
  await page.click('#btnSave');
  try {
    await page.waitForURL(/\/trip-detail\/\?id=/, { timeout: 8000 });
  } catch (e) {
    const toast = await page.locator('#toast').textContent().catch(() => '');
    bail('did not navigate to detail after save. toast=' + toast);
  }
  await page.waitForTimeout(1500);
  const newUrl = new URL(page.url());
  const newId = newUrl.searchParams.get('id');
  if (!newId) bail('no id in redirect url');
  const detailTitle = (await page.locator('.pass-title').first().textContent().catch(() => '')).trim();
  if (!detailTitle.includes('验证测试行程')) bail('detail title mismatch: ' + detailTitle);
  console.log('create -> detail OK, id =', newId);
  await page.screenshot({ path: path.join(SHOTS, 'after-create-detail.png'), fullPage: false });

  // ============ Flow 2: EDIT ============
  console.log('--- flow 2: edit trip form (id=' + newId + ') ---');
  await page.goto(ORIGIN + '/trip-form/?id=' + newId, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);

  const et = (await page.locator('#inTitle').inputValue()).trim();
  if (et !== '验证测试行程') bail('edit prefill title mismatch: ' + et);
  const eb = (await page.locator('#inBudget').inputValue()).trim();
  if (eb !== '5000') bail('edit prefill budget mismatch: ' + eb);
  if (!(await page.locator('#cardStatus').isVisible())) bail('edit mode should show status card');
  const stamp = (await page.locator('#pvStamp').textContent()).trim();
  if (stamp !== 'DRAFT') bail('edit stamp expected DRAFT, got ' + stamp);
  console.log('edit prefill OK (title/budget/status card, stamp DRAFT)');

  // switch status: planning（中间态）→ completed（数据库真实取值，覆盖枚举对齐回归）
  await page.click('#statusSeg button[data-st="planning"]');
  await page.waitForTimeout(200);
  const stamp2 = (await page.locator('#pvStamp').textContent()).trim();
  if (stamp2 !== 'PLANNING') bail('stamp after planning expected PLANNING, got ' + stamp2);
  const cls = await page.locator('#pvStamp').getAttribute('class');
  if (!cls.includes('s-planning')) bail('stamp class missing s-planning: ' + cls);
  console.log('status switch OK ->', stamp2);

  await page.click('#statusSeg button[data-st="completed"]');
  await page.waitForTimeout(200);
  const stamp3 = (await page.locator('#pvStamp').textContent()).trim();
  if (stamp3 !== 'ARRIVED') bail('stamp after completed expected ARRIVED, got ' + stamp3);
  const cls3 = await page.locator('#pvStamp').getAttribute('class');
  if (!cls3.includes('s-completed')) bail('stamp class missing s-completed: ' + cls3);
  console.log('status switch OK ->', stamp3, '(completed)');

  await page.screenshot({ path: path.join(SHOTS, 'edit-completed.png'), fullPage: true });

  // save edit
  await page.click('#btnSave');
  try { await page.waitForURL(/\/trip-detail\/\?id=/, { timeout: 8000 }); } catch (e) { bail('edit save did not navigate'); }
  await page.waitForTimeout(1200);
  const ribbon = await page.locator('.stub-status').first().textContent().catch(() => '');
  if (!/已完成/.test(ribbon)) bail('detail status should be 已完成, got: ' + ribbon);
  console.log('edit save -> detail status OK:', ribbon.trim());
  await page.screenshot({ path: path.join(SHOTS, 'after-edit-detail.png'), fullPage: false });

  // ============ Flow 3: 列表页状态标签（回归：completed 不得回落为草稿）============
  console.log('--- flow 3: list page status label (completed) ---');
  await page.goto(ORIGIN + '/trips/', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1200);
  await page.fill('#search', '验证测试行程');
  await page.waitForTimeout(600);
  const badge = page.locator('.stamp-badge.st-completed', { hasText: '已完成' }).first();
  if (!(await badge.count())) {
    const actual = await page.locator('.stamp-badge').allTextContents();
    bail('列表页未找到「已完成」标签（completed 被错误回落）。实际标签: ' + JSON.stringify(actual));
  }
  console.log('list status label OK: 已完成 (.stamp-badge.st-completed)');
  await page.screenshot({ path: path.join(SHOTS, 'list-completed.png'), fullPage: false });

  // ============ cleanup: delete test trip ============
  const del = await page.evaluate(async ({ id, token }) => {
    const r = await fetch('/api/trips/' + id, {
      method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token }, credentials: 'omit'
    });
    return r.status;
  }, { id: newId, token });
  console.log('cleanup delete status =', del, del === 200 || del === 204 ? '(OK)' : '(CHECK)');

  await browser.close();
  console.log('OK');
}

main().catch(e => bail(e.stack || String(e)));
