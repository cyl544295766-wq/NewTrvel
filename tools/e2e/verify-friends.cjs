// Verify friends: search by id/username/displayName + error handling + mutual rule for trip(账本) invite
const ORIGIN = process.env.ORIGIN || 'http://127.0.0.1:3001';
let failures = 0;
function ok(msg) { console.log('  ok  ' + msg); }
function check(cond, msg) { if (cond) ok(msg); else { failures++; console.error('  !!  ' + msg); } }

async function api(method, p, body, token) {
  const r = await fetch(ORIGIN + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  let json = null; try { json = JSON.parse(text); } catch (_) {}
  return { status: r.status, json };
}
function ymd(offset) {
  const d = new Date(); d.setDate(d.getDate() + offset);
  return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
}

async function main() {
  const stamp = Date.now().toString().slice(-8);
  const U1 = 'friend_a_' + stamp, U2 = 'friend_b_' + stamp;
  const r1 = await api('POST', '/api/auth/register', { username: U1, password: 'friend1234' });
  const r2 = await api('POST', '/api/auth/register', { username: U2, password: 'friend1234' });
  check(r1.status === 200 && r2.status === 200, '注册临时用户 A/B');
  const T1 = r1.json.token, T2 = r2.json.token;
  const idA = r1.json.user.id, idB = r2.json.user.id;
  const nickB = r2.json.user.displayName || U2;

  console.log('\n=== 1. 搜索（昵称/用户名/用户 ID，A 视角） ===');
  let s = await api('GET', '/api/friends/search?keyword=' + encodeURIComponent(U2), null, T1);
  check(s.status === 200 && (s.json.users || []).length === 1 && s.json.users[0].id === idB, '按用户名搜到 B');
  s = await api('GET', '/api/friends/search?keyword=' + encodeURIComponent(idB), null, T1);
  check(s.status === 200 && (s.json.users || []).length === 1 && s.json.users[0].id === idB, '按用户 ID 搜到 B');
  s = await api('GET', '/api/friends/search?keyword=' + encodeURIComponent(nickB), null, T1);
  check(s.status === 200 && (s.json.users || []).some(u => u.id === idB), '按昵称搜到 B（' + nickB + '）');
  s = await api('GET', '/api/friends/search?keyword=' + encodeURIComponent(idA), null, T1);
  check(s.status === 200 && (s.json.users || []).length === 0, '搜索自己 → 不出现在结果中');

  console.log('\n=== 2. 异常情况 ===');
  s = await api('GET', '/api/friends/search?keyword=' + encodeURIComponent('不存在的人_xyz'), null, T1);
  check(s.status === 200 && (s.json.users || []).length === 0, '搜索不存在 → 空结果（前端展示友好文案）');
  s = await api('POST', '/api/friends', { userId: 'nonexistent_id_123' }, T1);
  check(s.status === 404 && /未找到/.test(s.json.message || ''), 'ID 不存在 → 404「未找到该用户」');
  s = await api('POST', '/api/friends', { username: 'no_such_user_9x' }, T1);
  check(s.status === 404 && /未找到/.test(s.json.message || ''), '用户名不存在 → 404');
  s = await api('POST', '/api/friends', { displayName: '没人叫这个昵称_xyz' }, T1);
  check(s.status === 404 && /未找到/.test(s.json.message || ''), '昵称不存在 → 404');
  s = await api('POST', '/api/friends', { userId: idA }, T1);
  check(s.status === 400 && /不能添加自己/.test(s.json.message || ''), '添加自己 → 400 拦截');
  s = await api('POST', '/api/friends', {}, T1);
  check(s.status === 400, '空参数 → 400 提示提供方式');

  console.log('\n=== 3. 添加与关系状态（三种方式） ===');
  s = await api('POST', '/api/friends', { username: U2 }, T1);
  check(s.status === 200 && s.json.relation === 'added', '按用户名添加 B → relation=added，提示「' + (s.json.message || '') + '」');
  s = await api('POST', '/api/friends', { username: U2 }, T1);
  check(s.status === 409 && /已添加过/.test(s.json.message || ''), '重复添加 → 409');
  let list = (await api('GET', '/api/friends', null, T1)).json;
  check((list.friends || []).length === 1 && list.friends[0].relation === 'added', 'A 的列表：1 个好友（待对方回加）');
  check((list.incoming || []).length === 0, 'A 无待回加');
  list = (await api('GET', '/api/friends', null, T2)).json;
  check((list.incoming || []).length === 1 && (list.friends || []).length === 0, 'B 视角：incoming=1');
  s = await api('POST', '/api/friends', { userId: idA }, T2);
  check(s.status === 200 && s.json.relation === 'mutual', 'B 按用户 ID 回加 → relation=mutual');
  s = await api('POST', '/api/friends', { userId: U1 }, T2);
  check(s.status === 404, 'userId 误传用户名值 → 404（ID 精确匹配校验）');

  console.log('\n=== 4. 账本邀请好友规则 ===');
  const trip = (await api('POST', '/api/trips', {
    title: '好友规则验证行程（临时）', destination: '测试', startDate: ymd(1), endDate: ymd(2)
  }, T1)).json.trip;
  let del = await api('DELETE', '/api/friends/' + idB, null, T1);
  check(del.status === 200, 'A 移除好友（制造非好友场景）');
  let inv = await api('POST', `/api/trips/${trip.id}/members`, { username: U2, role: 'member' }, T1);
  check(inv.status === 403 && /请先添加对方为好友/.test(inv.json.message || ''),
    '非好友邀请被拦截：「' + (inv.json.message || '') + '」');
  s = await api('POST', '/api/friends', { displayName: nickB }, T1);
  check(s.status === 200 && s.json.relation === 'mutual', 'A 按昵称回加 → relation=mutual');
  inv = await api('POST', `/api/trips/${trip.id}/members`, { username: U2, role: 'member' }, T1);
  check(inv.status === 200, '互为好友后邀请加入账本成功');
  const members = inv.json.members || [];
  check(members.some(m => (m.username || (m.user || {}).username) === U2), '成员列表含 B');

  console.log('\n=== 5. 移除 ===');
  s = await api('DELETE', '/api/friends/' + idB, null, T1);
  check(s.status === 200, '移除好友 200');
  s = await api('DELETE', '/api/friends/' + idB, null, T1);
  check(s.status === 404 && /不在你的好友列表/.test(s.json.message || ''), '重复移除 → 404');
  list = (await api('GET', '/api/friends', null, T1)).json;
  check((list.friends || []).length === 0, 'A 的好友列表已清空');

  await api('DELETE', '/api/trips/' + trip.id, T1);
  await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, T1);
  await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, T2);
  console.log('\n临时数据已清理（行程软删、临时用户已注销）');
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
