// Verify friend chat + journal collaborative editing with mutex lock
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
  const A = await (async () => { const r = await api('POST', '/api/auth/register', { username: 'fc_a_' + rand, password: 'friend1234' }); return { token: r.json.token, id: r.json.user.id, name: r.json.user.username }; })();
  const B = await (async () => { const r = await api('POST', '/api/auth/register', { username: 'fc_b_' + rand, password: 'friend1234' }); return { token: r.json.token, id: r.json.user.id, name: r.json.user.username }; })();
  const C = await (async () => { const r = await api('POST', '/api/auth/register', { username: 'fc_c_' + rand, password: 'friend1234' }); return { token: r.json.token, id: r.json.user.id, name: r.json.user.username }; })();

  /* 好友关系：A↔B 互为好友；C 陌生 */
  await api('POST', '/api/friends', { username: B.name }, A.token);
  await api('POST', '/api/friends', { userId: A.id }, B.token);

  console.log('\n=== 1. 好友聊天 ===');
  let r = await api('POST', `/api/friends/${B.id}/messages`, { content: '嗨，周末一起去徒步吗？' }, A.token);
  check(r.status === 200, 'A 发消息给 B');
  r = await api('POST', `/api/friends/${C.id}/messages`, { content: 'hello' }, A.token);
  check(r.status === 403 && /互为好友/.test(r.message), '非好友发消息 → 403「只能与互为好友的用户聊天」');
  r = await api('POST', `/api/friends/${B.id}/messages`, { content: '' }, A.token);
  check(r.status === 400, '空消息 → 400');
  r = await api('POST', `/api/friends/${A.id}/messages`, { content: '好呀！走哪条线？' }, B.token);
  check(r.status === 200, 'B 回复');
  let chats = (await api('GET', '/api/friends/chats', null, B.token)).json.chats;
  check(chats.length === 1 && chats[0].unread === 1, 'B 的会话列表：未读 1（A 的首条）');
  let conv = (await api('GET', `/api/friends/${B.id}/messages?latest=true`, null, A.token)).json;
  check(conv.messages.length === 2 && conv.messages[conv.messages.length - 1].senderId === B.id,
    '聊天记录升序、最新在最后');
  chats = (await api('GET', '/api/friends/chats', null, A.token)).json.chats;
  check(chats[0].unread === 0, 'A 打开会话后未读清零（自动已读）');
  check((chats[0].lastMessage || '').includes('好呀'), '会话列表显示最新消息');
  const unreadAll = (await api('GET', '/api/friends/unread-count', null, B.token)).json.unread;
  check(unreadAll === 1, 'B 的未读总数 = 1（A 首条未读）');

  console.log('\n=== 2. 共同编辑：锁的获取与互斥 ===');
  const trip = (await api('POST', '/api/trips', {
    title: '共同编辑行程（临时）', destination: '稻城', startDate: ymd(1), endDate: ymd(2)
  }, A.token)).json.trip;
  const j = (await api('POST', `/api/trips/${trip.id}/journals`, {
    title: '亚丁攻略', content: '牛奶海徒步路线……', isDraft: false, visibility: 'friends'
  }, A.token)).json.journal;

  // C（陌生）无权取锁
  let lk = await api('POST', `/api/journals/${j.id}/edit-lock`, {}, C.token);
  check(lk.status === 403 && /共同编辑/.test(lk.message), '陌生人取锁 → 403「只有作者与互为好友…」');
  // A 取锁成功
  lk = await api('POST', `/api/journals/${j.id}/edit-lock`, {}, A.token);
  check(lk.status === 200 && lk.json.holderId === A.id, 'A 获取编辑锁');
  // B 取锁 → 409 互斥
  lk = await api('POST', `/api/journals/${j.id}/edit-lock`, {}, B.token);
  check(lk.status === 409 && /正在编辑/.test(lk.message), 'B 取锁 → 409「A 正在编辑这篇攻略」（互斥生效）');
  // 锁状态
  let st = (await api('GET', `/api/journals/${j.id}/edit-lock`, null, B.token)).json;
  check(st.locked === true && st.holderName === A.name && st.heldByMe === false, '锁状态：heldByMe=false，展示持有人');
  // B 未持锁保存 → 409
  let sv = await api('PATCH', `/api/journals/${j.id}`, { content: 'B 的恶意覆盖' }, B.token);
  check(sv.status === 409 && /编辑权限/.test(sv.message), '未持锁保存 → 409「请先获取编辑权限」');
  // A（持锁）保存成功
  sv = await api('PATCH', `/api/journals/${j.id}`, { content: 'A 更新后的攻略正文' }, A.token);
  check(sv.status === 200 && sv.json.journal.content.includes('A 更新后'), 'A 持锁保存成功');

  console.log('\n=== 3. 释放锁后 B 可编辑 ===');
  const rl = await api('DELETE', `/api/journals/${j.id}/edit-lock`, null, A.token);
  check(rl.status === 200, 'A 释放编辑锁');
  lk = await api('POST', `/api/journals/${j.id}/edit-lock`, {}, B.token);
  check(lk.status === 200 && lk.json.holderId === B.id, 'B 获取编辑锁（释放后立即可抢）');
  sv = await api('PATCH', `/api/journals/${j.id}`, { content: 'B 补充的住宿建议' }, B.token);
  check(sv.status === 200 && sv.json.journal.content.includes('B 补充'), 'B 持锁共同编辑保存成功');
  // 非作者改可见性 → 拒绝（作者专属）
  const vis = await api('PATCH', `/api/journals/${j.id}`, { visibility: 'public' }, B.token);
  check(vis.status === 200 && vis.json.journal.visibility === 'friends', '非作者改可见性被忽略（保持 friends）');

  console.log('\n=== 4. 私有攻略：好友不可编辑 ===');
  const j2 = (await api('POST', `/api/trips/${trip.id}/journals`, {
    title: '私人笔记', content: '……', isDraft: false, visibility: 'private'
  }, A.token)).json.journal;
  lk = await api('POST', `/api/journals/${j2.id}/edit-lock`, {}, B.token);
  check(lk.status === 403, '私有攻略好友取锁 → 403');

  console.log('\n=== 5. 清理 ===');
  await api('DELETE', `/api/journals/${j.id}/edit-lock`, null, B.token).catch(() => {});
  await api('DELETE', '/api/trips/' + trip.id, null, A.token);
  for (const u of [A, B, C]) await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, u.token);
  console.log('临时行程软删、临时用户已注销');
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
