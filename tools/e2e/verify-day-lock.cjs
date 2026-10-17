// Verify day-level edit lock: 同一天的行程（地点/天标题/攻略）同一时间只允许一人编辑
// actors: A=trip owner, B=trip member (mutual friend of A), C=stranger
const ORIGIN = process.env.ORIGIN || 'http://127.0.0.1:3001';
let failures = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { failures++; console.error('  !!  ' + msg); } }
function ymd(o) { const d = new Date(); d.setDate(d.getDate() + o); return d.toISOString().slice(0, 10); }

async function api(method, p, body, token) {
  const r = await fetch(ORIGIN + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch (_) {}
  return { status: r.status, json, message: (json && (json.message || json.error)) || '' };
}

async function main() {
  const rand = Math.floor(Math.random() * 100000);
  const mk = async (n) => { const r = await api('POST', '/api/auth/register', { username: n, password: 'friend1234' }); return { token: r.json.token, id: r.json.user.id, name: r.json.user.username }; };
  const A = await mk('dl_a_' + rand), B = await mk('dl_b_' + rand), C = await mk('dl_c_' + rand);

  /* A↔B 互为好友；A 建行程并邀请 B 为成员 */
  await api('POST', '/api/friends', { username: B.name }, A.token);
  await api('POST', '/api/friends', { userId: A.id }, B.token);
  const trip = (await api('POST', '/api/trips', {
    title: '天锁验证行程（临时）', destination: '稻城', startDate: ymd(1), endDate: ymd(2)
  }, A.token)).json.trip;
  await api('POST', `/api/trips/${trip.id}/days/generate`, {}, A.token);
  const inv = await api('POST', `/api/trips/${trip.id}/members`, { username: B.name, role: 'member' }, A.token);
  check(inv.status === 200, 'B 加入账本（互为好友）');
  const days = (await api('GET', `/api/trips/${trip.id}/days`, null, A.token)).json.days;
  const [day1, day2] = days;

  console.log('\n=== 1. 地点写操作：持锁才能进行 ===');
  let r = await api('POST', `/api/trips/${trip.id}/places`, { tripDayId: day1.id, name: '未持锁添加' }, B.token);
  check(r.status === 409 && /正被他人编辑|编辑权限/.test(r.message), 'B 未持锁添加地点 → 409「这一天正被他人编辑」');
  r = await api('PATCH', `/api/trips/${trip.id}/days/${day1.id}/places/reorder`, { placeIds: [] }, B.token);
    check(r.status === 409, 'B 未持锁重排 → 409');
  r = await api('PATCH', `/api/trips/${trip.id}/days/${day1.id}`, { title: '未持锁改标题' }, B.token);
  check(r.status === 409, 'B 未持锁改天标题 → 409');

  console.log('\n=== 2. A 取 day1 锁 → B 被互斥 ===');
  r = await api('POST', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, {}, A.token);
  check(r.status === 200 && r.json.holderId === A.id, 'A 获取 day1 编辑锁');
  r = await api('POST', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, {}, B.token);
  check(r.status === 409 && /正在编辑这一天的行程/.test(r.message), 'B 取 day1 锁 → 409「A 正在编辑这一天的行程」');
  r = await api('POST', `/api/trips/${trip.id}/places`, { tripDayId: day1.id, name: 'B 的地点' }, B.token);
  check(r.status === 409, 'B 持锁失败后添加地点仍被拦 → 409');
  let st = (await api('GET', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, null, B.token)).json;
  check(st.locked === true && st.holderName === A.name && st.heldByMe === false, 'day1 锁状态：A 持有，heldByMe=false');
  st = (await api('GET', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, null, A.token)).json;
  check(st.heldByMe === true, 'A 视角 heldByMe=true');

  console.log('\n=== 3. 锁是「天」级：day2 不受影响 ===');
  r = await api('POST', `/api/trips/${trip.id}/days/${day2.id}/edit-lock`, {}, B.token);
  check(r.status === 200, 'A 持 day1 锁时 B 可取 day2 锁（不同天互不影响）');
  r = await api('POST', `/api/trips/${trip.id}/places`, { tripDayId: day2.id, name: 'B 在第二天加的地点' }, B.token);
  check(r.status === 200, 'B 持 day2 锁在 day2 添加地点成功');
  await api('DELETE', `/api/trips/${trip.id}/days/${day2.id}/edit-lock`, null, B.token);

  console.log('\n=== 4. 攻略编辑也挂在天上：同一天的两篇攻略互斥 ===');
  const j1 = (await api('POST', `/api/trips/${trip.id}/journals`, { tripDayId: day1.id, title: '攻略一', content: '…', isDraft: false, visibility: 'public' }, A.token)).json.journal;
  const j2 = (await api('POST', `/api/trips/${trip.id}/journals`, { tripDayId: day1.id, title: '攻略二', content: '…', isDraft: false, visibility: 'public' }, A.token)).json.journal;
  r = await api('POST', `/api/journals/${j2.id}/edit-lock`, {}, B.token);
  check(r.status === 409 && /正在编辑这一天的行程/.test(r.message), 'A 持 day1 锁（经攻略一）时 B 编辑攻略二取锁 → 409（同天互斥）');
  const j3 = (await api('POST', `/api/trips/${trip.id}/journals`, { tripDayId: day2.id, title: '攻略三', content: '…', isDraft: false, visibility: 'public' }, A.token)).json.journal;
  r = await api('POST', `/api/journals/${j3.id}/edit-lock`, {}, B.token);
  check(r.status === 200, 'B 编辑 day2 的攻略三 → 正常取锁');
  await api('DELETE', `/api/journals/${j3.id}/edit-lock`, null, B.token);

  console.log('\n=== 5. 释放后 B 立即可编辑 ===');
  r = await api('DELETE', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, null, A.token);
  check(r.status === 200, 'A 释放 day1 锁');
  r = await api('POST', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, {}, B.token);
  check(r.status === 200 && r.json.holderId === B.id, 'B 获取 day1 锁');
  r = await api('POST', `/api/trips/${trip.id}/places`, { tripDayId: day1.id, name: 'B 持锁添加的地点' }, B.token);
  check(r.status === 200, 'B 持锁添加地点成功');
  // B 未持锁改地点（用 A 直接改 → 应 409，因为 B 持锁）
  const pid = r.json.place.id;
  r = await api('PATCH', `/api/trips/${trip.id}/places/${pid}`, { name: 'A 未持锁改' }, A.token);
  check(r.status === 409, 'A（未持锁）改 B 持锁天的地点 → 409');
  r = await api('PATCH', `/api/trips/${trip.id}/places/${pid}`, { name: 'B 持锁改' }, B.token);
  check(r.status === 200 && r.json.place.name === 'B 持锁改', 'B（持锁）改地点成功');

  console.log('\n=== 6. 杂项护栏 ===');
  r = await api('DELETE', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, null, A.token);
  check(r.status === 403 && /不是你持有/.test(r.message), '非持锁人释放 → 403');
  r = await api('POST', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, {}, C.token);
  check(r.status === 403, '非成员（陌生人）取天锁 → 403');

  console.log('\n=== 7. 清理 ===');
  await api('DELETE', `/api/trips/${trip.id}/days/${day1.id}/edit-lock`, null, B.token);
  await api('DELETE', '/api/trips/' + trip.id, null, A.token);
  for (const u of [A, B, C]) await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, u.token);
  console.log('临时行程软删、临时用户已注销');
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
