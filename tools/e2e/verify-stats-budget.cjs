// 统计页新能力 e2e：/stats/budgets、/stats/settlement-suggestions、80% AI 预算提醒
const O = 'http://127.0.0.1:3001';
let failures = 0;
function ok(m) { console.log('  ok  ' + m); }
function ck(c, m) { if (c) ok(m); else { failures++; console.error('  !!  ' + m); } }

async function api(method, p, body, token) {
  const r = await fetch(O + '/api' + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch (_) {}
  return { status: r.status, json };
}
const ymd = o => { const d = new Date(); d.setDate(d.getDate() + o); return d.toISOString().slice(0, 10); };

(async () => {
  const stamp = Date.now().toString().slice(-8);
  const regA = await api('POST', '/auth/register', { username: 'st_a_' + stamp, password: 'stt123456' });
  const regC = await api('POST', '/auth/register', { username: 'st_c_' + stamp, password: 'stt123456' });
  ck(regA.status === 200 && regC.status === 200, '注册 A（买单人）/ C（同行伙伴）');
  const TA = regA.json.token, TC = regC.json.token;
  const CID = regC.json.user.id;
  const AID = regA.json.user.id;

  const mk = await api('POST', '/trips', { title: '预算统计_' + stamp, destination: '测试市', startDate: ymd(5), endDate: ymd(7), budget: 1000 }, TA);
  ck(mk.status === 200, '创建 1000 元预算行程');
  const tid = mk.json.trip.id;

  // A、C 互加好友（加成员的前置）
  await api('POST', '/friends', { username: 'st_c_' + stamp }, TA);
  const fb = await api('POST', '/friends', { userId: AID }, TC);
  ck(fb.status === 200 && fb.json.relation === 'mutual', 'A、C 互为好友');

  const addC = await api('POST', '/trips/' + tid + '/members', { username: 'st_c_' + stamp, role: 'member' }, TA);
  ck(addC.status === 200, 'C 加入行程');

  // 1) budgets 进度条数据
  await api('POST', '/trips/' + tid + '/expenses', { amount: '300', title: '票', category: 'ticket' }, TA);
  let b = (await api('GET', '/stats/budgets', null, TA));
  let row = ((b.json || {}).trips || []).find(x => x.tripId === tid);
  ck(row && row.budgetStatus === 'ok' && Number(row.percentage) === 30, '预算进度 ok 30%');
  ck(String(row.remaining) === '700.00' || String(row.remaining) === '700', '剩余 700（实际 ' + (row || {}).remaining + '）');

  // 2) 80% AI 提醒：再记 600 → 90%，应触发 warn 通知（A 收到）
  await api('POST', '/trips/' + tid + '/expenses', { amount: '600', title: '住宿', category: 'hotel' }, TA);
  b = (await api('GET', '/stats/budgets', null, TA));
  row = ((b.json || {}).trips || []).find(x => x.tripId === tid);
  ck(row && row.budgetStatus === 'warn', '预算进度 warn 90%');
  const nA = (await api('GET', '/notifications?page=1&pageSize=50', null, TA));
  const warnNotes = (nA.json.notifications || []).filter(x => x.title && x.title.indexOf('预算提醒') >= 0);
  ck(warnNotes.length === 1, 'A 收到 1 条 80% 预算提醒（实际 ' + warnNotes.length + '）');
  ck(warnNotes.length !== 0 && (warnNotes[0].content || '').includes('90%'), '提醒内容含 90% 用量');

  // 3) 超支提醒：再记 300 → 120% 触 over 通知
  await api('POST', '/trips/' + tid + '/expenses', { amount: '300', title: '购物', category: 'shopping' }, TA);
  b = (await api('GET', '/stats/budgets', null, TA));
  row = ((b.json || {}).trips || []).find(x => x.tripId === tid);
  ck(row && row.budgetStatus === 'over', '预算进度 over 120%');
  const nA2 = (await api('GET', '/notifications?page=1&pageSize=50', null, TA));
  const allWarn = (nA2.json.notifications || []).filter(x => x.title && x.title.indexOf('预算提醒') >= 0);
  ck(allWarn.length === 2, 'warn + over 共 2 条不重复（实际 ' + allWarn.length + '）');

  // 再记一笔也不会重复 over
  await api('POST', '/trips/' + tid + '/expenses', { amount: '50', title: '零花', category: 'other' }, TA);
  const nA3 = (await api('GET', '/notifications?page=1&pageSize=50', null, TA));
  const still = (nA3.json.notifications || []).filter(x => x.title && x.title.indexOf('预算提醒') >= 0);
  ck(still.length === 2, '继续记账 over 提醒不重复（仍 ' + still.length + '）');

  // 4) 结算建议：A 付全款、AA 分摊 → A 应收一半
  const s = (await api('GET', '/stats/settlement-suggestions', null, TA));
  const sug = ((s.json || {}).suggestions || []).filter(x => x.tripId === tid);
  ck(sug.length === 1 && sug[0].direction === 'in', 'A 结算建议 in（实际 ' + sug.length + ' 条）');
  ck(sug.length && Number(sug[0].amount) === 625, 'A 应收 625（1250/2，实际 ' + (sug[0] || {}).amount + '）');
  ck(sug.length && sug[0].counterpartyName && sug[0].counterpartyName.length > 0, '对手方名=' + (sug[0] || {}).counterpartyName);
  const sC = (await api('GET', '/stats/settlement-suggestions', null, TC));
  const sugC = ((sC.json || {}).suggestions || []).filter(x => x.tripId === tid);
  ck(sugC.length === 1 && sugC[0].direction === 'out' && Number(sugC[0].amount) === 625, 'C 结算建议 out 625');

  console.log(failures ? 'FAILURES: ' + failures : 'ALL PASS');
  process.exit(failures ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(2); });
