// Verify journal(攻略) visibility: public/friends/private/specified
// actors: A=author, B=mutual friend, C=specified friend, D=stranger(non-friend)
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
  const stamp = Date.now().toString().slice(-8);
  const mk = async (name) => {
    const r = await api('POST', '/api/auth/register', { username: name, password: 'friend1234' });
    return { token: r.json.token, id: r.json.user.id, name };
  };
  const A = await mk('jv_a_' + stamp), B = await mk('jv_b_' + stamp),
        C = await mk('jv_c_' + stamp), D = await mk('jv_d_' + stamp);

  console.log('\n=== 前置：好友关系（A↔B、A↔C 互为好友；D 陌生） ===');
  await api('POST', '/api/friends', { username: B.name }, A.token);
  await api('POST', '/api/friends', { username: C.name }, A.token);
  check((await api('POST', '/api/friends', { userId: A.id }, B.token)).json.relation === 'mutual', 'A↔B 互为好友');
  check((await api('POST', '/api/friends', { userId: A.id }, C.token)).json.relation === 'mutual', 'A↔C 互为好友');

  console.log('\n=== 前置：A 建行程 + 4 篇不同可见性的攻略 ===');
  const trip = (await api('POST', '/api/trips', {
    title: '攻略可见性行程（临时）', destination: '拉萨', startDate: ymd(1), endDate: ymd(3)
  }, A.token)).json.trip;
  const mkJournal = async (title, visibility, extra) => {
    const body = Object.assign({ title, content: title + ' 的攻略正文', isDraft: false, visibility }, extra || {});
    const r = await api('POST', `/api/trips/${trip.id}/journals`, body, A.token);
    check(r.status === 200, '创建「' + title + '」（' + visibility + '）');
    return r.json.journal;
  };
  const jPublic = await mkJournal('公开攻略', 'public');
  const jFriends = await mkJournal('好友可见攻略', 'friends');
  const jSpec = await mkJournal('指定好友攻略', 'specified', { visibleFriendIds: [C.id] });
  const jPriv = await mkJournal('私有攻略', 'private');

  console.log('\n=== 1. 作者 A：全部可见 ===');
  let list = (await api('GET', `/api/trips/${trip.id}/journals`, null, A.token)).json.journals;
  check(list.length === 4, '行程内列表 4 篇全见');
  let feed = (await api('GET', '/api/journals/feed', null, A.token)).json.journals;
  check(feed.some(j => j.id === jPriv.id), 'feed 含自己的私有攻略');
  let one = await api('GET', '/api/journals/' + jPriv.id, null, A.token);
  check(one.status === 200, '作者看私有详情 200');

  console.log('\n=== 2. B（互为好友）：public + friends 可见，specified/private 不可见 ===');
  list = await api('GET', `/api/trips/${trip.id}/journals`, null, B.token);
  check(list.status === 403, 'B 非行程成员 → 行程内列表 403（好友经 feed 发现攻略）');
  feed = (await api('GET', '/api/journals/feed', null, B.token)).json.journals;
  const feedIds = feed.map(j => j.id);
  check(feedIds.includes(jPublic.id) && feedIds.includes(jFriends.id), 'feed 含公开+好友可见篇');
  check(!feedIds.includes(jPriv.id), 'feed 不含私有篇');
  check(!feedIds.includes(jSpec.id), 'feed 不含指定好友篇（B 不在名单）');
  check((feed.find(j => j.id === jPublic.id) || {}).tripTitle, 'feed 条目带行程名（跨行程发现）');
  one = await api('GET', '/api/journals/' + jFriends.id, null, B.token);
  check(one.status === 200, 'B 看好友可见详情 200');
  one = await api('GET', '/api/journals/' + jSpec.id, null, B.token);
  check(one.status === 403 && /没有权限/.test(one.message), 'B 看（不在名单的）指定好友篇 → 403');
  one = await api('GET', '/api/journals/' + jPriv.id, null, B.token);
  check(one.status === 403, 'B 看私有篇 → 403');

  console.log('\n=== 3. C（被指定好友）：public + specified 可见 ===');
  feed = (await api('GET', '/api/journals/feed', null, C.token)).json.journals;
  const cIds = feed.map(j => j.id);
  check(cIds.includes(jSpec.id), 'C 的 feed 含指定好友篇');
  check(!cIds.includes(jPriv.id), 'C 的 feed 不含私有篇');
  one = await api('GET', '/api/journals/' + jSpec.id, null, C.token);
  check(one.status === 200 && (one.json.journal.visibleFriendIds || []).includes(C.id),
    'C 看指定好友详情 200（含名单回显）');

  console.log('\n=== 4. D（陌生人非好友）：仅公开可见 ===');
  feed = (await api('GET', '/api/journals/feed', null, D.token)).json.journals;
  const dIds = feed.map(j => j.id);
  check(dIds.includes(jPublic.id), 'D 的 feed 含公开篇');
  check(!dIds.includes(jFriends.id) && !dIds.includes(jSpec.id) && !dIds.includes(jPriv.id),
    'D 的 feed 不含好友/指定/私有篇');
  one = await api('GET', '/api/journals/' + jPublic.id, null, D.token);
  check(one.status === 200, 'D 看公开详情 200');
  one = await api('GET', '/api/journals/' + jFriends.id, null, D.token);
  check(one.status === 403, 'D 看好友可见篇 → 403');
  one = await api('GET', '/api/journals/' + jPriv.id, null, D.token);
  check(one.status === 403, 'D 看私有篇 → 403');

  console.log('\n=== 5. 可见性修改与名单校验 ===');
  let r = await api('PATCH', `/api/trips/${trip.id}/journals/${jSpec.id}`,
    { visibility: 'specified', visibleFriendIds: [D.id] }, A.token);
  check(r.status === 400 && /不是你的好友/.test(r.message), '指定非好友 → 400「所选用户均不是你的好友」');
  r = await api('PATCH', `/api/trips/${trip.id}/journals/${jSpec.id}`,
    { visibility: 'specified', visibleFriendIds: [] }, A.token);
  check(r.status === 400 && /至少选择一位/.test(r.message), '指定但名单为空 → 400');
  r = await api('PATCH', `/api/trips/${trip.id}/journals/${jSpec.id}`,
    { visibility: 'friends' }, A.token);
  check(r.status === 200, '改为好友可见 200');
  one = await api('GET', '/api/journals/' + jSpec.id, null, B.token);
  check(one.status === 200, 'B（好友）随之可见');
  r = await api('PATCH', `/api/trips/${trip.id}/journals/${jPublic.id}`, { visibility: 'no_such' }, A.token);
  check(r.status === 400, '非法可见性值 → 400');

  console.log('\n=== 6. 清理 ===');
  await api('DELETE', `/api/trips/${trip.id}`, null, A.token);
  for (const u of [A, B, C, D]) await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, u.token);
  console.log('临时行程软删、4 个临时用户已注销');
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
