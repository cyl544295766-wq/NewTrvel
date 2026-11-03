/* AI 今日简报 e2e（API 层）：覆盖 spec 测试要求 1-11、13-15（12 首页交互状态由浏览器验证覆盖）。
 * 运行前提：后端已在 127.0.0.1:3001 运行；node >= 18（内置 fetch）。
 * 场景：
 *   1  进行中行程识别今天与第几天          8  时间冲突/凭证缺失/预算超支/打包/证件规则触发
 *   2  即将开始行程 → 行前简报（不伪装）    9  无风险时不生成虚假提醒
 *   3  无行程 → 创建旅行空态              10  非成员访问返回标准权限错误
 *   4  多行程重叠不静默选错（安排多者胜）  11  观察者可读
 *   5  今日地点按时间排序 + 时间待定归组   13  跳转入口携带正确 tripId
 *   6  天气不可用仍返回其余内容            14  响应不含订单号/确认码原文
 *   7  摘要有值且来源合法（降级容错）      15  无 NaN/undefined/错误日期
 */
const BASE = 'http://127.0.0.1:3001';

async function req(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (_) { data = text; }
  return { status: res.status, data };
}

let passed = 0;
let failed = 0;
function check(name, cond, extra) {
  if (cond) {
    passed++;
    console.log('  \u2713 ' + name);
  } else {
    failed++;
    console.log('  \u2717 ' + name + (extra ? '  [' + extra + ']' : ''));
  }
}

/* 简报按用户时区（Asia/Shanghai）判断「今天」，这里固定 +8 换算保持口径一致 */
function dateKey(offsetDays) {
  const d = new Date(Date.now() + offsetDays * 86400000 + 8 * 3600000);
  return d.toISOString().slice(0, 10);
}
function at(offsetDays, hhmmss) {
  return dateKey(offsetDays) + ' ' + hhmmss;
}

async function register(name) {
  const r = await req('POST', '/api/auth/register', {
    body: { username: name, password: 'Brief@12345' },
  });
  let token = r.data && r.data.token;
  if (!token) {
    const login = await req('POST', '/api/auth/login', {
      body: { username: name, password: 'Brief@12345' },
    });
    token = login.data.token;
  }
  const me = await req('GET', '/api/auth/me', { token });
  const id = (me.data && ((me.data.user && me.data.user.id) || me.data.id)) || null;
  return { token, id, username: name };
}

/** 加好友（邀请行程成员的前置：互为好友） */
async function makeFriends(a, b) {
  await req('POST', '/api/friends', { token: a.token, body: { username: b.username } });
  if (a.id) {
    await req('POST', '/api/friends/' + a.id + '/accept', { token: b.token, body: {} });
  }
}

async function resetTrips(user) {
  const r = await req('GET', '/api/trips', { token: user.token });
  for (const t of (r.data && r.data.trips) || []) {
    await req('DELETE', '/api/trips/' + t.id, { token: user.token });
  }
}

async function createTrip(user, { title, destination, start, end, budget }) {
  const r = await req('POST', '/api/trips', {
    token: user.token,
    body: {
      title,
      ...(destination ? { destination } : {}),
      startDate: start,
      endDate: end,
      ...(budget ? { budget } : {}),
    },
  });
  const t = (r.data && (r.data.trip || r.data)) || {};
  if (!t.id) throw new Error('createTrip failed: ' + JSON.stringify(r.data).slice(0, 200));
  return t;
}

async function generateDays(user, tripId) {
  const r = await req('POST', '/api/trips/' + tripId + '/days/generate', {
    token: user.token,
    body: {},
  });
  return (r.data && r.data.days) || [];
}

function dayByDateKey(days, key) {
  return days.find((d) => String(d.date || '').slice(0, 10) === key);
}

/** 写行程点前先获取当天编辑锁（与行程页交互一致），写完释放；失败抛出便于定位 */
async function addPlace(user, tripId, dayId, place) {
  await req('POST', '/api/trips/' + tripId + '/days/' + dayId + '/edit-lock', {
    token: user.token,
    body: {},
  });
  const r = await req('POST', '/api/trips/' + tripId + '/places', {
    token: user.token,
    body: { tripDayId: dayId, ...place },
  });
  await req('DELETE', '/api/trips/' + tripId + '/days/' + dayId + '/edit-lock', {
    token: user.token,
  });
  if (r.status !== 200 && r.status !== 201) {
    throw new Error('addPlace failed: ' + r.status + ' ' + JSON.stringify(r.data).slice(0, 200));
  }
  return r.data;
}

async function briefAuto(user, body) {
  const r = await req('POST', '/api/ai/today-brief', { token: user.token, body: body || {} });
  return r;
}

async function briefOfTrip(user, tripId, body) {
  const r = await req('POST', '/api/trips/' + tripId + '/ai/today-brief', {
    token: user.token,
    body: body || {},
  });
  return r;
}

async function main() {
  const stamp = String(Date.now()).slice(-8);
  console.log('== AI 今日简报 e2e ==');

  /* ---------- 用户与数据夹具 ---------- */
  const u1 = await register('tb_owner_' + stamp);
  await resetTrips(u1);
  const u2 = await register('tb_stranger_' + stamp);
  await resetTrips(u2);
  const u3 = await register('tb_viewer_' + stamp);
  await resetTrips(u3);

  /* T1：今天=第2天；冲突/无坐标/凭证缺失/预算超支/打包未完成/证件过期；无目的地（天气不可用） */
  const T1 = await createTrip(u1, {
    title: '简报主行程',
    start: at(-1, '00:00:00').slice(0, 10),
    end: at(3, '00:00:00').slice(0, 10),
    budget: '100',
  });
  const t1Days = await generateDays(u1, T1.id);
  const t1Today = dayByDateKey(t1Days, dateKey(0));
  check('夹具：T1 今日=第 2 天', t1Today && t1Today.dayIndex === 2,
    t1Today ? 'dayIndex=' + t1Today.dayIndex : '找不到今天');
  await addPlace(u1, T1.id, t1Today.id, {
    name: '甲景点', type: 'attraction',
    startTime: at(0, '09:00:00'), endTime: at(0, '11:00:00'),
    latitude: '39.9042', longitude: '116.4074',
  });
  await addPlace(u1, T1.id, t1Today.id, {
    name: '乙景点', type: 'attraction',
    startTime: at(0, '10:00:00'), endTime: at(0, '12:00:00'),
    latitude: '39.9142', longitude: '116.4174',
  });
  await addPlace(u1, T1.id, t1Today.id, { name: '丙地点', type: 'custom' }); /* 无时间无坐标 */
  await req('POST', '/api/trips/' + T1.id + '/bookings', {
    token: u1.token,
    body: {
      type: 'flight', title: '示例航班',
      startTime: at(0, '08:00:00'),
      orderNo: 'SECRET-ORDER-777', confirmCode: 'SECRET-CODE-888',
    },
  });
  await req('POST', '/api/trips/' + T1.id + '/bookings', {
    token: u1.token,
    body: { type: 'ticket', title: '示例门票', startTime: at(0, '13:00:00') }, /* 无确认信息 */
  });
  await req('POST', '/api/trips/' + T1.id + '/expenses', {
    token: u1.token,
    body: { amount: '500', category: 'food', title: '午餐', spentAt: dateKey(0) },
  });
  const packList = await req('POST', '/api/trips/' + T1.id + '/packing-lists', {
    token: u1.token,
    body: { category: 'other', name: '行前清单' },
  });
  const listId = packList.data && packList.data.packingList && packList.data.packingList.id;
  if (listId) {
    await req('POST', '/api/trips/' + T1.id + '/packing-lists/' + listId + '/items', {
      token: u1.token, body: { name: '雨伞', quantity: 1 },
    });
    await req('POST', '/api/trips/' + T1.id + '/packing-lists/' + listId + '/items', {
      token: u1.token, body: { name: '羽绒服', quantity: 1 },
    });
  }
  await req('POST', '/api/trips/' + T1.id + '/documents', {
    token: u1.token,
    body: {
      type: 'passport', title: '示例证件', expiredAt: dateKey(-1), isReminder: true,
      url: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
    },
  });

  /* T2：两个地点（1 个有时间有坐标、1 个无时间无坐标）→ 排序 + 路线不完整 */
  const T2 = await createTrip(u1, {
    title: '简报排序行程',
    start: at(-1, '00:00:00').slice(0, 10),
    end: at(1, '00:00:00').slice(0, 10),
  });
  const t2Days = await generateDays(u1, T2.id);
  const t2Today = dayByDateKey(t2Days, dateKey(0));
  await addPlace(u1, T2.id, t2Today.id, {
    name: '下午茶', type: 'restaurant',
    startTime: at(0, '14:00:00'), endTime: at(0, '15:00:00'),
    latitude: '31.2304', longitude: '121.4737',
  });
  await addPlace(u1, T2.id, t2Today.id, { name: '夜市', type: 'shopping' });

  /* T6：全部无时间 → 时间信息不完整 */
  const T6 = await createTrip(u1, {
    title: '简报缺时间行程',
    start: at(0, '00:00:00').slice(0, 10),
    end: at(1, '00:00:00').slice(0, 10),
  });
  const t6Days = await generateDays(u1, T6.id);
  const t6Today = dayByDateKey(t6Days, dateKey(0));
  await addPlace(u1, T6.id, t6Today.id, { name: '随便走走', type: 'custom' });

  /* T7：干净行程 → 无风险不生成虚假提醒 */
  const T7 = await createTrip(u1, {
    title: '简报干净行程',
    start: at(0, '00:00:00').slice(0, 10),
    end: at(0, '00:00:00').slice(0, 10),
  });
  const t7Days = await generateDays(u1, T7.id);
  const t7Today = dayByDateKey(t7Days, dateKey(0));
  await addPlace(u1, T7.id, t7Today.id, {
    name: '干净景点', type: 'attraction',
    startTime: at(0, '10:00:00'), endTime: at(0, '11:00:00'),
    latitude: '30.2741', longitude: '120.1551',
  });
  await req('POST', '/api/trips/' + T7.id + '/bookings', {
    token: u1.token,
    body: {
      type: 'train', title: '示例车次',
      startTime: at(0, '18:00:00'),
      orderNo: 'OK-1', confirmCode: 'OK-2',
    },
  });

  /* T3：仅未来行程（u2）→ 行前简报 */
  const T3 = await createTrip(u2, {
    title: '简报行前行程',
    start: at(3, '00:00:00').slice(0, 10),
    end: at(5, '00:00:00').slice(0, 10),
  });
  const t3Days = await generateDays(u2, T3.id);
  const t3First = t3Days[0];
  await addPlace(u2, T3.id, t3First.id, {
    name: '集合点', type: 'custom',
    startTime: at(3, '09:00:00'), endTime: at(3, '10:00:00'),
    latitude: '39.9', longitude: '116.4',
  });

  /* ---------- 1. 今天与第几天 ---------- */
  console.log('\n[1] 进行中行程识别今天和第几天');
  const r1 = await briefOfTrip(u1, T1.id, { date: dateKey(0), refresh: true });
  check('HTTP 200', r1.status === 200, 'status=' + r1.status);
  const b1 = r1.data || {};
  check('mode=today', b1.mode === 'today', 'mode=' + b1.mode);
  check('第 2／5 天', b1.day && b1.day.dayIndex === 2 && b1.day.totalDays === 5,
    JSON.stringify(b1.day));
  check('day.date 是今天', b1.day && String(b1.day.date).slice(0, 10) === dateKey(0));

  /* ---------- 2. 行前简报 ---------- */
  console.log('\n[2] 即将开始的行程 → 行前简报');
  const r2 = await briefAuto(u2, { refresh: true });
  const b2 = r2.data || {};
  check('mode=pre-trip', b2.mode === 'pre-trip', 'mode=' + b2.mode);
  check('trip.mode 标识行前', b2.trip && b2.trip.mode === 'pre-trip');
  check('不伪装今日：day 是第 1 天预览或为空',
    !b2.day || (b2.day.preview === true && b2.day.dayIndex === 1), JSON.stringify(b2.day));
  check('行前预览安排挂在第 1 天', (b2.schedule || []).length === 1
    && b2.schedule[0].name === '集合点', 'schedule=' + JSON.stringify((b2.schedule || []).map((p) => p.name)));

  /* ---------- 3. 无行程空态 ---------- */
  console.log('\n[3] 无行程 → 创建旅行空态');
  const r3 = await briefAuto(u3, { refresh: true });
  const b3 = r3.data || {};
  check('mode=none', b3.mode === 'none', 'mode=' + b3.mode);
  check('空态文案', b3.emptyState && b3.emptyState.text === '还没有可以生成简报的旅行');
  check('创建新旅行入口', (b3.actions || []).some((a) => a.url === '/trip-form/'));

  /* ---------- 4. 多行程重叠不静默选错 ---------- */
  console.log('\n[4] 多行程重叠 → 当天安排多者胜');
  const r4 = await briefAuto(u1, { refresh: true });
  const b4 = r4.data || {};
  check('自动选中安排最多的 T1', b4.trip && b4.trip.id === T1.id,
    'picked=' + (b4.trip && b4.trip.title));

  /* ---------- 5. 排序 + 时间待定归组 ---------- */
  console.log('\n[5] 今日地点按时间排序，无时间进「时间待定」');
  const r5 = await briefOfTrip(u1, T2.id, { refresh: true });
  const b5 = (r5.data || {}).schedule || [];
  check('时间序正确（下午茶 14:00 在前）', b5.length === 2 && b5[0].name === '下午茶',
    JSON.stringify(b5.map((p) => p.name)));
  check('无时间项排最后且 timed=false', b5[1] && b5[1].name === '夜市' && b5[1].timed === false);

  /* ---------- 6. 天气不可用仍返回其余内容 ---------- */
  console.log('\n[6] 天气不可用时仍返回其他简报内容');
  check('weather=null 且标记 unavailable',
    b1.weather === null && b1.dataStatus && b1.dataStatus.weather === 'unavailable');
  check('其余模块仍可用', b1.dataStatus.expenses === 'ok' && b1.dataStatus.packing === 'ok'
    && b1.dataStatus.schedule === 'ok');

  /* ---------- 7. 摘要有值且来源合法 ---------- */
  console.log('\n[7] 摘要有值且来源合法（LLM 失败自动降级模板）');
  check('summary 非空', typeof b1.summary === 'string' && b1.summary.length > 10);
  check('summarySource 合法', b1.summarySource === 'ai' || b1.summarySource === 'fallback',
    'source=' + b1.summarySource);

  /* ---------- 8. 规则触发 ---------- */
  console.log('\n[8] 提醒规则触发（时间冲突/凭证缺失/预算/打包/证件）');
  const types1 = (b1.alerts || []).map((a) => a.type);
  check('time_conflict 触发', types1.includes('time_conflict'), types1.join(','));
  check('voucher_missing 触发', types1.includes('voucher_missing'));
  check('budget_risk 触发且 urgent', (b1.alerts || []).some(
    (a) => a.type === 'budget_risk' && a.severity === 'urgent'));
  check('packing_incomplete 触发', types1.includes('packing_incomplete'));
  check('document_risk 触发且 urgent', (b1.alerts || []).some(
    (a) => a.type === 'document_risk' && a.severity === 'urgent'));
  check('优先级排序：urgent 在前',
    (b1.alerts || []).length > 1 && b1.alerts[0].severity === 'urgent',
    'first=' + ((b1.alerts[0] || {}).type));
  check('提醒结构完整（id/type/severity/title/detail/evidence/action）',
    (b1.alerts || []).every((a) => a.id && a.type && a.severity && a.title && a.detail
      && typeof a.evidence === 'string' && 'action' in a));
  const types6 = ((await briefOfTrip(u1, T6.id, { refresh: true })).data || {}).alerts || [];
  check('全部无时间 → time_missing', types6.some((a) => a.type === 'time_missing'),
    types6.map((a) => a.type).join(','));
  check('单地点不报 route_incomplete', !types6.some((a) => a.type === 'route_incomplete'));
  const types5 = (r5.data || {}).alerts || [];
  check('部分缺坐标 → route_incomplete', types5.some((a) => a.type === 'route_incomplete'));
  if (b1.weather && Number(b1.weather.rainChance) >= 50) {
    check('降雨概率高 → weather_rain', types1.includes('weather_rain'));
  } else {
    console.log('  -（跳过）weather_rain：当前天气数据不足降雨阈值，规则分支未触发');
  }

  /* ---------- 9. 无风险不生成虚假提醒 ---------- */
  console.log('\n[9] 无风险时不生成虚假提醒');
  const r9 = await briefOfTrip(u1, T7.id, { refresh: true });
  const b9 = r9.data || {};
  check('干净行程 alerts 为空', (b9.alerts || []).length === 0,
    (b9.alerts || []).map((a) => a.type).join(','));

  /* ---------- 10. 非成员权限 ---------- */
  console.log('\n[10] 非成员访问返回标准权限错误');
  const r10 = await briefOfTrip(u2, T1.id, {});
  check('非成员 404（项目标准：旅行不存在）', r10.status === 404, 'status=' + r10.status);

  /* ---------- 11. 观察者可读 ---------- */
  console.log('\n[11] 观察者可以查看');
  await makeFriends(u1, u3); /* 邀请成员的前置：互为好友 */
  const invite = await req('POST', '/api/trips/' + T1.id + '/members', {
    token: u1.token, body: { username: u3.username, role: 'viewer' },
  });
  check('邀请观察者成功', invite.status === 200, 'status=' + invite.status
    + ' ' + JSON.stringify(invite.data).slice(0, 120));
  const r11 = await briefOfTrip(u3, T1.id, { refresh: true });
  check('viewer 200 且拿到 T1', r11.status === 200 && r11.data && r11.data.trip
    && r11.data.trip.id === T1.id, 'status=' + r11.status);

  /* ---------- 13. 跳转入口携带正确 tripId ---------- */
  console.log('\n[13] 跳转入口携带正确 tripId');
  check('actions 全部含 T1 id', (b1.actions || []).length >= 2
    && (b1.actions || []).every((a) => String(a.url).includes(T1.id)),
    JSON.stringify((b1.actions || []).map((a) => a.url)));

  /* ---------- 14. 响应不含敏感原文 ---------- */
  console.log('\n[14] 响应不含订单号/确认码原文');
  const raw1 = JSON.stringify(b1);
  check('不含 SECRET-ORDER-777', !raw1.includes('SECRET-ORDER-777'));
  check('不含 SECRET-CODE-888', !raw1.includes('SECRET-CODE-888'));
  check('只回 hasOrderNo/hasConfirmCode', (b1.bookings || []).some((bk) => bk.hasOrderNo === true)
    && (b1.bookings || []).every((bk) => !('orderNo' in bk) && !('confirmCode' in bk)));
  check('证件只回计数不含内容', b1.documents && !raw1.includes('示例证件'));

  /* ---------- 15. 无 NaN/undefined/错误日期 ---------- */
  console.log('\n[15] 无 NaN/undefined/错误日期');
  check('无 NaN/undefined', !raw1.includes('NaN') && !raw1.includes('undefined'));
  check('generatedAt 可解析', !isNaN(Date.parse(b1.generatedAt)), String(b1.generatedAt));
  check('错误日期参数 400',
    (await briefOfTrip(u1, T1.id, { date: 'not-a-date' })).status === 400);

  console.log('\n== 结果：' + passed + ' 通过 / ' + failed + ' 失败 ==');
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.error('e2e 运行异常：', err);
  process.exit(1);
});
