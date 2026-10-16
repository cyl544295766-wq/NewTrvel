// Verify super admin: role separation (admin=super_admin, others=user), /api/admin/** guarded (normal user 403),
// overview counts, CI runs integration (server /ci/ data), user management (search/disable/enable/promote guard).
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
  return { status: r.status, json, message: (json && (json.message || json.error)) || '' };
}

async function main() {
  console.log('\n=== 1. 角色基础 ===');
  const adm = await api('POST', '/api/auth/login', { username: 'admin', password: 'admin123' });
  check(adm.status === 200 && adm.json.user.role === 'super_admin', 'admin 角色为 super_admin');
  const T0 = adm.json.token;
  const me = await api('GET', '/api/auth/me', null, T0);
  check((me.json.user || {}).role === 'super_admin', '/auth/me 返回角色');
  const stamp = Date.now().toString().slice(-8);
  const reg = await api('POST', '/api/auth/register', { username: 'norm_' + stamp, password: 'friend1234' });
  const T1 = reg.json.token, idN = reg.json.user.id;
  check(reg.json.user.role === 'user', '新注册用户角色为 user');

  console.log('\n=== 2. 权限分离：普通用户访问 /api/admin/** ===');
  for (const p of ['/api/admin/overview', '/api/admin/users', '/api/admin/ci/runs']) {
    const r = await api('GET', p, null, T1);
    check(r.status === 403 && /超级管理员/.test(r.message), 'GET ' + p + ' → 403「需要超级管理员权限」');
  }
  const w = await api('PATCH', '/api/admin/users/' + idN + '/status', { status: 'disabled' }, T1);
  check(w.status === 403, '普通用户改状态 → 403');

  console.log('\n=== 3. 超管：概览 + CI 数据集成 ===');
  const ov = await api('GET', '/api/admin/overview', null, T0);
  check(ov.status === 200 && ov.json.users >= 1 && ov.json.trips != null, '概览 200（用户 ' + ov.json.users + ' / 行程 ' + ov.json.trips + ' / 攻略 ' + ov.json.journals + '）');
  check(ov.json.ciTotalRuns >= 1, 'CI 运行次数 ' + ov.json.ciTotalRuns + '（数据来自 /ci/）');
  const ci = await api('GET', '/api/admin/ci/runs', null, T0);
  check(ci.status === 200 && (ci.json.runs || []).length >= 1, 'CI runs 200（共 ' + ci.json.total + ' 次）');
  const last = (ci.json.runs || [])[0] || {};
  check(last.run_number && last.url && last.url.includes('github.com'), '最近部署 #' + last.run_number + ' 结论=' + last.conclusion + '，含 Actions 链接');
  check(ci.json.successRate != null, '成功率统计：' + ci.json.successRate + '%');

  console.log('\n=== 4. 用户管理 ===');
  const users = (await api('GET', '/api/admin/users?keyword=norm_' + stamp, null, T0)).json.users;
  console.log('  info  搜索命中:', users.map(u => u.username + '/' + u.status).join(', ') || '无');
  check(users.length === 1 && users[0].id === idN, '按用户名搜索到普通用户');
  const dis = await api('PATCH', '/api/admin/users/' + idN + '/status', { status: 'disabled' }, T0);
  check(dis.status === 200 && /已禁用/.test(dis.message), '禁用账户');
  const relogin = await api('POST', '/api/auth/login', { username: 'norm_' + stamp, password: 'friend1234' });
  console.log('  info  禁用后登录响应:', relogin.status, relogin.message.slice(0, 40), '| body:', JSON.stringify(relogin.json).slice(0, 80));
  check(relogin.status === 403 && /禁用/.test(relogin.message), '禁用后登录被拒（立即生效）');
  const en = await api('PATCH', '/api/admin/users/' + idN + '/status', { status: 'active' }, T0);
  check(en.status === 200, '重新启用');
  const self = await api('PATCH', '/api/admin/users/' + adm.json.user.id + '/status', { status: 'disabled' }, T0);
  check(self.status === 400 && /不能修改自己/.test(self.message), '不能修改自己 → 400');
  const promoSelf = await api('PATCH', '/api/admin/users/' + adm.json.user.id + '/role', { role: 'user' }, T0);
  check(promoSelf.status === 404, '降级接口已移除（无法自降/制造角色变更）→ 404');
  // 超级管理员只能是 admin 账号：角色授予接口已整体移除（PATCH /role → 404）
  const promo = await api('PATCH', '/api/admin/users/' + idN + '/role', { role: 'super_admin' }, T0);
  check(promo.status === 404, '角色授予接口已移除（PATCH /role → 404，无法制造第二个超管）');
  const normRole = users[0].role;
  check(normRole === 'user', '普通用户角色恒为 user，不存在第二个超管');

  console.log('\n=== 5. 清理 ===');
  // 临时普通用户：由超管禁用后无法登录；直接由其注销需要登录 —— 改为 DB 外清理：注销接口需登录，
  // 该用户角色已是 user 且被超管禁用，用超管先启用再由本人注销
  await api('PATCH', '/api/admin/users/' + idN + '/status', { status: 'active' }, T0).catch(() => {});
  const re2 = await api('POST', '/api/auth/login', { username: 'norm_' + stamp, password: 'friend1234' });
  if (re2.status === 200) {
    await api('POST', '/api/auth/deactivate', { password: 'friend1234' }, re2.json.token);
    console.log('  ok  临时用户已注销');
  }
  console.log(failures ? `\n${failures} 项失败` : '\n=== ALL PASS ===');
  process.exit(failures ? 1 : 0);
}
main().catch(e => { console.error('\n' + (e && e.message)); process.exit(1); });
