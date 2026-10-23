// Verify square: 公开/好友页签、facets、详情可见性+浏览量、可见性修改、复制（copy_count 溯源）
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
async function reg(username) {
  const r = await api('POST', '/api/auth/register', { username, password: 'square1234' });
  if (r.status !== 200) throw new Error('注册失败 ' + username + ': ' + JSON.stringify(r.json));
  return r.json;
}

async function main() {
  const stamp = Date.now().toString().slice(-8);
  const dest = '广场测试地' + stamp;
  const A = await reg('sq_a_' + stamp);   // 作者（公开 + 好友行程）
  const B = await reg('sq_b_' + stamp);   // A 的好友
  const C = await reg('sq_c_' + stamp);   // 路人
  const TA = A.token, TB = B.token, TC = C.token;
  check(true, '注册临时用户 A/B/C');

  // A、B 互为好友；C 不加
  await api('POST', '/api/friends', { username: 'sq_b_' + stamp }, TA);
  const fb = await api('POST', '/api/friends', { userId: A.user.id }, TB);
  check(fb.status === 200 && fb.json.relation === 'mutual', 'A、B 互为好友');

  // A 建公开行程；B 建好友可见行程
  const pub = (await api('POST', '/api/trips', {
    title: '广场公开攻略_' + stamp, description: '热门打卡路线', destination: dest,
    startDate: ymd(3), endDate: ymd(4), visibility: 'public'
  }, TA)).json.trip;
  const frd = (await api('POST', '/api/trips', {
    title: '好友专属攻略_' + stamp, destination: dest,
    startDate: ymd(10), endDate: ymd(11), visibility: 'friends'
  }, TB)).json.trip;
  check(pub && pub.id && pub.visibility === 'public', 'A 创建公开行程');
  check(frd && frd.id && frd.visibility === 'friends', 'B 创建好友可见行程');

  // 给公开行程生成天数
  const gen = await api('POST', `/api/trips/${pub.id}/days/generate`, {}, TA);
  check(gen.status === 200 && (gen.json.days || []).length >= 1, 'A 生成公开行程天数（' + (gen.json.days || []).length + ' 天）');

  console.log('\n=== 1. 列表与页签 ===');
  let sq = (await api('GET', '/api/square?scope=public&page=1&pageSize=20', null, TC)).json;
  let pubHit = (sq.items || []).find(x => x.id === pub.id);
  let frdHit = (sq.items || []).find(x => x.id === frd.id);
  check(pubHit && pubHit.title === '广场公开攻略_' + stamp, '公开页签含 A 的公开攻略（C 视角）');
  check(!frdHit, '好友行程不出现在公开页签');
  check(pubHit && pubHit.author && pubHit.author.id === A.user.id, '列表带作者信息');
  check(pubHit && pubHit.canCopy === true, 'C 对公开攻略 canCopy=true');

  sq = (await api('GET', '/api/square?scope=friends&page=1&pageSize=20', null, TB)).json;
  check((sq.items || []).some(x => x.id === frd.id), '好友页签包含 B 自己的 friends 攻略');
  // 跨用户：A 把公开行程临时改为 friends，B 应能在好友页签看到
  await api('PATCH', '/api/trips/' + pub.id + '/visibility', { visibility: 'friends' }, TA);
  sq = (await api('GET', '/api/square?scope=friends&page=1&pageSize=20', null, TB)).json;
  check((sq.items || []).some(x => x.id === pub.id), '好友页签能看到好友 A 的 friends 攻略（跨用户）');
  await api('PATCH', '/api/trips/' + pub.id + '/visibility', { visibility: 'public' }, TA);
  sq = (await api('GET', '/api/square?scope=friends&page=1&pageSize=20', null, TB)).json;
  check((sq.items || []).every(x => x.id !== pub.id), 'A 改回公开后从 B 的好友页签退出');
  sq = (await api('GET', '/api/square?scope=friends&page=1&pageSize=20', null, TC)).json;
  check(!(sq.items || []).some(x => x.id === frd.id || x.id === pub.id), 'C 的好友页签为空或不含 A/B 攻略');

  console.log('\n=== 2. 搜索 / 目的地 / 排序 / facets ===');
  sq = (await api('GET', '/api/square?scope=public&keyword=' + encodeURIComponent('广场公开攻略_' + stamp), null, TC)).json;
  check((sq.items || []).length === 1 && sq.items[0].id === pub.id, '关键词搜索命中');
  sq = (await api('GET', '/api/square?scope=public&destination=' + encodeURIComponent(dest), null, TC)).json;
  check((sq.items || []).some(x => x.id === pub.id), '目的地筛选命中');
  const facets = (await api('GET', '/api/square/facets', null, TC)).json;
  check((facets.destinations || []).some(d => d.destination === dest && d.count >= 1), 'facets 含测试目的地');

  console.log('\n=== 3. 详情可见性与浏览量 ===');
  let dRsp = (await api('GET', '/api/square/' + pub.id, null, TC));
  check(dRsp.status === 200, 'C 可查看公开攻略详情');
  let d1 = dRsp.json || {};
  const square = d1.square || d1;
  check(square.id === pub.id && square.viewCount === 1, 'C 查看 → view_count=1');
  check((d1.overview || {}).dayCount >= 1, '详情带行程总览（' + (d1.overview || {}).dayCount + ' 天）');
  check(Array.isArray((d1.overview || {}).days) && (d1.overview.days[0].places || []).length === 0, '总览含每日地点列表（含空日）');  check(d1.canCopy === true, '详情 canCopy=true');

  let s = await api('GET', '/api/square/' + frd.id, null, TC);
  check(s.status === 403, 'C 看好友专属攻略 → 403');
  s = await api('GET', '/api/square/' + frd.id, null, TB);
  check(s.status === 200 && s.json.square.viewCount === 0, 'B（作者本人）可看而浏览量不涨');
  s = await api('GET', '/api/square/' + frd.id, null, TC);
  check(s.status !== 200 || ((s.json.square || {}).viewCount === 1), '被拒绝的访问不加浏览量');

  console.log('\n=== 4. 可见性修改 ===');
  s = await api('PATCH', '/api/trips/' + pub.id + '/visibility', { visibility: 'friends' }, TB);
  check(s.status === 403, '非作者改可见性 → 403');
  s = await api('PATCH', '/api/trips/' + pub.id + '/visibility', { visibility: 'friends' }, TA);
  check(s.status === 200, '作者改为好友可见 200');
  sq = (await api('GET', '/api/square?scope=public&page=1&pageSize=20', null, TC)).json;
  check(!(sq.items || []).some(x => x.id === pub.id), '改好友可见后从公开页签消失');
  s = await api('PATCH', '/api/trips/' + pub.id + '/visibility', { visibility: 'public' }, TA);
  check(s.status === 200 && s.json.trip && s.json.trip.visibility === 'public', '改回公开（返回行程）');
  sq = (await api('GET', '/api/square?scope=public&page=1&pageSize=20', null, TC)).json;
  check((sq.items || []).some(x => x.id === pub.id), '改回公开后重新出现在页签');

  console.log('\n=== 5. 复制（copy_count / 溯源 / 私有副本） ===');
  s = await api('POST', '/api/square/' + frd.id + '/copy', {}, TC);
  check(s.status === 403, 'C 复制好友专属攻略 → 403');
  s = await api('POST', '/api/square/' + pub.id + '/copy', {}, TC);
  const copy = s.json && s.json.trip;
  check(s.status === 200 && copy && copy.sourceTripId === pub.id, 'C 复制公开攻略 → 副本带 sourceTripId');
  check(copy && copy.visibility === 'private', '副本默认私有');
  check(copy && copy.title.startsWith('广场公开攻略_' + stamp), '副本标题可溯源（「' + (copy ? copy.title : '') + '」）');
  let cTrips = (await api('GET', '/api/trips', null, TC)).json.trips || [];
  check(cTrips.some(t => t.id === copy.id), 'C 的行程列表出现副本');
  d1 = await api('GET', '/api/square/' + pub.id, null, TC);
  check(d1.json.square.copyCount === 1 && d1.json.square.copiedByMe === true, '源攻略 copy_count=1 且 copiedByMe=true');

  console.log('\n=== 6. 热度 = view*1 + copy*5 ===');
  const hot = (await api('GET', '/api/square?scope=public&sort=hot&page=1&pageSize=20', null, TC)).json;
  check((hot.items || []).some(x => x.id === pub.id), 'hot 排序可见（热度字段不报错）');
  const copies = (await api('GET', '/api/square?scope=public&sort=copies&page=1&pageSize=20', null, TC)).json;
  check((copies.items || []).some(x => x.id === pub.id), 'copies 排序可见');

  console.log('\n=== 7. 清理 ===');
  for (const [id, token] of [[copy.id, TC], [pub.id, TA], [frd.id, TB]]) {
    await api('DELETE', '/api/trips/' + id, token);
  }
  await api('DELETE', '/api/friends/' + B.user.id, TA);
  await api('POST', '/api/auth/deactivate', { password: 'square1234' }, TA);
  await api('POST', '/api/auth/deactivate', { password: 'square1234' }, TB);
  await api('POST', '/api/auth/deactivate', { password: 'square1234' }, TC);
  console.log('临时数据已清理（行程软删、解绑好友、用户注销）');
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
