// Verify AI pet module: drag-to-move with edge snap & hover restore / click opens bubble / real AI conversation / login gating
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const ORIGIN = 'http://127.0.0.1:3001';
const SHOTS = 'D:/tmp/pet-shots';

function bail(msg) { console.error('FAIL: ' + msg); process.exit(1); }
let failures = 0;
function check(cond, label) {
  console.log((cond ? '  ok  ' : '  !!  ') + label);
  if (!cond) failures++;
}

async function main() {
  const { token, user } = JSON.parse(fs.readFileSync('D:/tmp/login.json', 'utf8'));
  fs.mkdirSync(SHOTS, { recursive: true });

  const browser = await chromium.launch({ channel: 'chrome', headless: true, args: ['--no-sandbox', '--no-proxy-server'] });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' });
  await ctx.addInitScript(({ token, user }) => {
    if (sessionStorage.getItem('__seeded')) return;
    sessionStorage.setItem('__seeded', '1');
    localStorage.setItem('travelos.token', token);
    localStorage.setItem('travelos.user', JSON.stringify(user));
  }, { token, user });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error' && !/favicon|amap|autonavi/.test(m.text())) errs.push('console: ' + m.text()); });

  console.log('--- load home ---');
  await page.goto(ORIGIN + '/home/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.pet', { timeout: 15000 });
  await page.waitForTimeout(800);

  /* ---- 1. 无自动移动：位置静止 ---- */
  console.log('--- static (roam disabled) ---');
  const readPos = () => page.evaluate(() => {
    const t = document.querySelector('.pet').style.transform;
    const m = t.match(/translate\((-?[\d.]+)px,\s*(-?[\d.]+)px\)/);
    return m ? { x: parseFloat(m[1]), y: parseFloat(m[2]) } : null;
  });
  const waitX = (exp, timeout = 6000) => page.waitForFunction(
    (expX) => {
      const t = document.querySelector('.pet').style.transform;
      const m = t && t.match(/translate\((-?[\d.]+)px/);
      return m && Math.abs(parseFloat(m[1]) - expX) < 0.6;
    }, exp, { timeout });

  const p0 = await readPos();
  check(!!p0, '宠物有初始位置: ' + JSON.stringify(p0));
  await page.waitForTimeout(2600);
  const p1 = await readPos();
  check(p1.x === p0.x && p1.y === p0.y, '自动移动已禁用（2.6s 内位置不变）');

  /* ---- 2. 拖拽：实时跟随，松手停止 ---- */
  console.log('--- drag ---');
  const cx = p0.x + 23, cy = p0.y + 27;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 200, cy - 150, { steps: 6 });
  const mid = await readPos();
  check(mid.x < p0.x - 100, '拖拽中实时跟随指针（mid x = ' + mid.x + '）');
  await page.mouse.move(700, 450, { steps: 4 });
  await page.mouse.up();
  await page.waitForTimeout(120);
  const dropped = await readPos();
  check(Math.abs(dropped.x - 700 + 23) <= 2 && Math.abs(dropped.y - 450 + 27) <= 2,
    '松手后停在释放位置（grab 偏移保持）: ' + JSON.stringify(dropped));
  await page.waitForTimeout(900);
  const settled = await readPos();
  check(settled.x === dropped.x && settled.y === dropped.y, '松手后不再自动移动');

  /* ---- 3. 边界钳制：拖出屏幕会被收回边界内 ---- */
  await page.mouse.move(700, 450);
  await page.mouse.down();
  await page.mouse.move(-120, -200, { steps: 5 });
  await page.mouse.up();
  await page.waitForTimeout(120);
  const clamped = await readPos();
  /* 拖拽过程被钳制在边界内；松手时中心距左缘 49px < 90px 吸附区 → 吸附左缘半隐藏（x = -23） */
  check(clamped.x === -23 && clamped.y === 26, '贴左上角松手 → 吸附左缘半隐藏（x = -23, y = 26）');

  /* ---- 4. 边缘吸附：拖近右边缘松手 → 半隐藏 ---- */
  console.log('--- edge snap ---');
  const expSnapX = 1440 - 23;
  await page.mouse.move(clamped.x + 23, clamped.y + 27);
  await page.mouse.down();
  await page.mouse.move(1436, 500, { steps: 8 });
  await page.mouse.up();
  await waitX(expSnapX);           /* 轮询等吸附过渡完成（负载下 650ms 可能不够） */
  const snapped = await readPos();
  check(snapped.x === expSnapX, '吸附右缘并半隐藏（x = ' + snapped.x + '，期望 ' + expSnapX + '）');
  /* rect 随过渡飞行，等它真正越过视口右缘（而非 style 瞬时值） */
  await page.waitForFunction(() => {
    const r = document.querySelector('.pet').getBoundingClientRect();
    return r.right > window.innerWidth;
  }, null, { timeout: 5000 });
  check(true, '元素一半超出视口（半隐藏，rect.right > innerWidth）');

  /* ---- 5. 悬停恢复完整显示 ---- */
  await page.mouse.move(1425, 500);   /* 移入可见的一半 */
  await waitX(1440 - 46);
  const full = await readPos();
  check(full.x === 1440 - 46, '悬停恢复完整显示（x = ' + full.x + '）');
  await page.mouse.move(700, 400);    /* 移开 */
  await waitX(expSnapX);
  const reHalf = await readPos();
  check(reHalf.x === expSnapX, '移开后回到半隐藏');

  /* ---- 6. resize：吸附态随窗口重贴边 ---- */
  await page.setViewportSize({ width: 1000, height: 800 });
  await waitX(1000 - 23);
  const rs = await readPos();
  check(rs.x === 1000 - 23, '窗口缩小后重新贴右缘（x = ' + rs.x + '）');
  await page.screenshot({ path: path.join(SHOTS, 'snapped.png'), fullPage: false, timeout: 60000 }).catch((e) => console.log('  (skip) snapped 截图失败：' + e.message.split('\n')[0]));

  /* ---- 7. 点按吸附中的宠物 → 恢复完整 + 打开气泡 ---- */
  await page.mouse.move(977 + 10, (await readPos()).y + 27);
  await page.mouse.down();
  await page.mouse.up();
  await Promise.all([
    page.waitForSelector('.pet-bubble.open', { timeout: 5000 }),
    waitX(1000 - 46)
  ]);
  const openedFull = await readPos();
  check(openedFull.x === 1000 - 46, '点按吸附宠物：恢复完整显示（x = ' + openedFull.x + '）');
  check(await page.locator('.pet-bubble.open').count() === 1, '点按后气泡打开');
  await page.locator('.pb-head .x').click();
  await waitX(1000 - 23);
  const backHalf = await readPos();
  check(backHalf.x === 1000 - 23, '气泡关闭后回到半隐藏（x = ' + backHalf.x + '）');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.waitForTimeout(400);

  await page.screenshot({ path: path.join(SHOTS, 'roam.png'), fullPage: false, timeout: 60000 }).catch((e) => console.log('  (skip) roam 截图失败：' + e.message.split('\n')[0]));

  /* ---- 2. 点击 → 气泡打开 ---- */
  console.log('--- bubble open ---');
  await page.locator('.pet').click();
  await page.waitForSelector('.pet-bubble.open', { timeout: 5000 });
  const nm = (await page.locator('.pb-head .nm').textContent()).trim();
  check(nm === '游游', '气泡标题 = 宠物名: ' + nm);
  const tailX = await page.locator('.pet-bubble').evaluate(el => el.style.getPropertyValue('--tail-x'));
  check(!!tailX, '气泡尾巴已对准宠物 (--tail-x = ' + tailX + ')');

  /* ---- 3. 已登录：加载历史（可能为空态含示例问题） ---- */
  await page.waitForTimeout(1200);
  const hasChips = await page.locator('.pet-chip').count();
  const hasMsgs = await page.locator('#petMsgs .pet-msg').count();
  check(hasChips > 0 || hasMsgs > 0, '空态示例问题或历史消息已渲染（chips=' + hasChips + ', msgs=' + hasMsgs + ')');
  const inputVisible = await page.locator('#petInput').isVisible();
  check(inputVisible, '已登录：输入框可见');

  /* ---- 8. 示例问题 chip（空态直接发送，真实 AI 回复） ---- */
  console.log('--- AI conversation (chip) ---');
  /* 重新打开气泡（视口已还原 1440，宠物吸附在右缘半隐藏） */
  await page.mouse.move(1417 + 10, (await readPos()).y + 27);
  await page.mouse.down();
  await page.mouse.up();
  await page.waitForSelector('.pet-bubble.open', { timeout: 5000 });
  await page.waitForSelector('.pet-chip', { timeout: 10000 });
  await page.locator('.pet-chip').first().click();
  try {
    await page.waitForFunction(() => {
      return !document.getElementById('petTyping')
        && document.querySelectorAll('#petMsgs .pet-msg').length >= 2;
    }, { timeout: 90000 });
    check(true, 'chip 发送成功并等到回复');
  } catch (e) { bail('chip 发送后未等到回复: ' + e.message); }
  const reply1 = (await page.locator('#petMsgs .pet-msg.ai .txt').last().textContent()).replace(/\s+/g, ' ').trim();
  check(reply1.length > 4 && !reply1.includes('[object'), 'AI 真实回复: ' + reply1.slice(0, 70));

  /* ---- 9. 手动输入发送（第二个问题） ---- */
  console.log('--- AI conversation (typed) ---');
  await page.fill('#petInput', '用一句话推荐一个草原旅行目的地');
  await page.click('#petSend');
  try {
    await page.waitForFunction(() => {
      return !document.getElementById('petTyping')
        && document.querySelectorAll('#petMsgs .pet-msg').length >= 4;
    }, { timeout: 90000 });
    check(true, '手动输入发送成功并等到第二条回复');
  } catch (e) { bail('手动发送后未等到回复: ' + e.message); }
  const lastAi = (await page.locator('#petMsgs .pet-msg.ai .txt').last().textContent()).replace(/\s+/g, ' ').trim();
  check(lastAi.length > 4 && !lastAi.includes('[object'), 'AI 真实回复: ' + lastAi.slice(0, 80));
  /* 会话 id 已持久化 */
  const convId = await page.evaluate(() => localStorage.getItem('travelos.pet.convId'));
  check(!!convId, '会话 id 已存 localStorage（跨页延续）');
  await page.screenshot({ path: path.join(SHOTS, 'bubble.png'), fullPage: false, timeout: 60000 }).catch((e) => console.log('  (skip) bubble 截图失败：' + e.message.split('\n')[0]));

  /* ---- 10. 二次打开：历史从后端恢复 ---- */
  await page.locator('.pb-head .x').click();
  await page.waitForTimeout(400);
  const openAfterClose = await page.locator('.pet-bubble.open').count();
  check(openAfterClose === 0, '点击 ✕ 气泡收起');
  await page.locator('.pet').click();
  await page.waitForSelector('.pet-bubble.open', { timeout: 5000 });
  await page.waitForFunction(() => document.querySelectorAll('#petMsgs .pet-msg').length >= 4, { timeout: 15000 })
    .catch(() => bail('重新打开后历史消息未恢复'));
  const msgsRe = await page.locator('#petMsgs .pet-msg').count();
  check(msgsRe >= 4, '重开气泡恢复历史消息（' + msgsRe + ' 条）');

  /* ---- 11. 未登录态（登录页本身无需登录，宠物可见且给登录引导） ---- */
  console.log('--- login gating ---');
  const page2 = await ctx.newPage();
  /* ctx 级 initScript 会播种 token（先执行），页面级再移除（后执行）→ 匿名访问 */
  await page2.addInitScript(() => {
    localStorage.removeItem('travelos.token');
    localStorage.removeItem('travelos.user');
  });
  await page2.goto(ORIGIN + '/login/', { waitUntil: 'domcontentloaded' });
  await page2.waitForSelector('.pet', { timeout: 15000 });
  await page2.locator('.pet').click();
  await page2.waitForSelector('.pet-bubble.open', { timeout: 5000 });
  await page2.waitForSelector('.pet-login', { timeout: 5000 }).catch(() => {});
  const loginShown = await page2.locator('.pet-login').count();
  const inputHidden = await page2.locator('#petInput').isHidden().catch(() => true);
  check(loginShown === 1 && inputHidden, '未登录：显示登录引导、隐藏输入框');
  await page2.screenshot({ path: path.join(SHOTS, 'login-gate.png'), timeout: 60000 }).catch((e) => console.log('  (skip) login-gate 截图失败：' + e.message.split('\n')[0]));
  await page2.close();

  /* ---- 清理：删除本次创建的宠物会话 ---- */
  console.log('--- cleanup ---');
  const list = await (await fetch(ORIGIN + '/api/ai/conversations', {
    headers: { 'Authorization': 'Bearer ' + token } })).json();
  let n = 0;
  for (const c of (list.conversations || [])) {
    if ((c.title || '').startsWith('宠物·')) {
      const r = await fetch(ORIGIN + '/api/ai/conversations/' + c.id, {
        method: 'DELETE', headers: { 'Authorization': 'Bearer ' + token } });
      if (r.ok) n++;
    }
  }
  check(n >= 1, '清理测试 AI 会话 ' + n + ' 个');

  check(errs.length === 0, '无 JS 报错' + (errs.length ? '：' + errs.slice(0, 3).join(' | ') : ''));
  await browser.close();

  console.log(failures === 0 ? '\nALL PASS' : '\n' + failures + ' 项断言失败');
  process.exit(failures === 0 ? 0 : 1);
}

main().catch(e => { console.error('[ERROR]', (e && e.stack) || e); process.exit(1); });
