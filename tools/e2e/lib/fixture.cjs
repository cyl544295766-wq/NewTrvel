/* Travel OS · E2E 共享夹具与清理工具（供各 verify-* 脚本复用，不改变各自断言）
 *
 * 约定：
 * - 只允许本地回环目标（创建/删除数据的脚本统一守卫，防误打生产）；
 * - 注册成功即记录准确 ID；清理按外键安全顺序逐 ID 删除（无前缀/模糊/全表删除）；
 *   磁盘文件仅删除 /files/<32hex>.<ext> 形态（范围守卫）；
 * - 清理失败或残留核验非 0 → 抛错（调用方以此非零退出）；
 * - 不清理其他批次或个人数据。
 */
'use strict';

const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const PG = 'docker exec travel-os-postgres psql -U travel_os -d travel_os -t -A -c';
const LOOPBACK = ['127.0.0.1', 'localhost', '::1'];

function assertLocalOrigin(origin) {
  let host = '';
  try {
    host = new URL(origin).hostname;
  } catch (e) {
    console.error('[REFUSE] 非法 origin: ' + origin);
    process.exit(1);
  }
  if (!LOOPBACK.includes(host)) {
    console.error('[REFUSE] 本测试会创建/删除数据，仅允许本地回环目标：' + origin);
    process.exit(1);
  }
}

function psql(sql) {
  return execSync(`${PG} "${sql.replace(/"/g, '\\"')}"`, { encoding: 'utf8' }).trim();
}

const _tables = new Map();
function tableExists(name) {
  if (!_tables.has(name)) {
    _tables.set(name, psql(
      `SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${name}'`) === '1');
  }
  return _tables.get(name);
}

/** 注册独立测试账号（密码仅驻内存不打印）；返回 { id, token, username, password }。 */
async function registerUser(origin, tag) {
  const username = 'e2e' + tag + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
  const password = 'E2' + tag + Date.now().toString(36) + 'z9';
  const res = await fetch(origin + '/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const body = await res.json().catch(() => null);
  if (res.status === 429) {
    const retryAfter = res.headers.get('Retry-After') || '';
    const err = new Error('注册被限流（429）' + (retryAfter ? '，Retry-After=' + retryAfter + 's' : ''));
    err.rateLimited = true;
    err.retryAfter = Number(retryAfter) || 0;
    throw err;
  }
  if (res.status !== 200 || !body || !body.token || !body.user) {
    const e = new Error('注册失败: ' + res.status + ' ' + JSON.stringify(body || {}).slice(0, 160));
    e.registerFailed = true;
    throw e;
  }
  return {
    id: body.user.id,
    token: body.token,
    username,
    password,
    displayName: body.user.displayName || ''
  };
}

/** 构造 API 助手：默认抛错；allowFailure 时返回 { ok, status, body }（用于断言权限拒绝等）。 */
function api(origin, token, allowFailure) {
  async function call(method, p, bodyObj) {
    const res = await fetch(origin + p, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + token
      },
      body: bodyObj === undefined ? undefined : JSON.stringify(bodyObj)
    });
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch (e) { json = text; }
    if (allowFailure) {
      return { ok: res.ok, status: res.status, body: json };
    }
    if (!res.ok) {
      throw new Error(method + ' ' + p + ' -> ' + res.status + ' ' + text.slice(0, 160));
    }
    return json;
  }
  return {
    get: (p) => call('GET', p),
    post: (p, b) => call('POST', p, b),
    put: (p, b) => call('PUT', p, b),
    patch: (p, b) => call('PATCH', p, b === undefined ? undefined : b),
    del: (p) => call('DELETE', p)
  };
}

/** 有界条件等待（替代固定 sleep）：条件成立返回值，超时抛错。 */
async function waitFor(desc, fn, timeout = 15000, interval = 200) {
  const t0 = Date.now();
  for (;;) {
    let v = null;
    try { v = await fn(); } catch (e) { v = null; }
    if (v) return v;
    if (Date.now() - t0 > timeout) {
      throw new Error('等待超时(' + timeout + 'ms): ' + desc);
    }
    await new Promise((r) => setTimeout(r, interval));
  }
}

/** 生成纯色 PNG 文件（上传夹具用；不依赖外部素材）。 */
function writePngFile(filePath, w, h, rgb) {
  const zlib = require('zlib');
  const crc32 = (buf) => {
    let crc = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) {
      crc ^= buf[i];
      for (let k = 0; k < 8; k++) crc = (crc >>> 1) ^ (0xEDB88320 & -(crc & 1));
    }
    return (crc ^ 0xFFFFFFFF) >>> 0;
  };
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    const off = y * (w * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < w; x++) {
      raw[off + 1 + x * 3] = rgb[0];
      raw[off + 2 + x * 3] = rgb[1];
      raw[off + 3 + x * 3] = rgb[2];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const t = Buffer.from(type, 'ascii');
    const c = Buffer.alloc(4);
    c.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
    return Buffer.concat([len, t, data, c]);
  };
  fs.writeFileSync(filePath,
    Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
}

/** 夹具清理器：按准确 ID 记录资源，FK 安全顺序删除 + 残留核验。 */
class Cleaner {
  constructor() {
    this.users = [];
    this.trips = [];
    this.members = [];
    this.expenses = [];
    this.places = [];
    this.journals = [];
    this.photos = [];      // { id, disk }  disk=/files/<32hex>.<ext>
    this.documents = [];   // { id, disk }
    this.bookings = [];
    this.packingLists = [];
    this.days = [];        // { tripId, dayId }
  }

  addUser(id) { if (id) this.users.push(id); return id; }
  addTrip(id) { if (id) this.trips.push(id); return id; }
  addDay(tripId, dayId) { if (dayId) this.days.push({ tripId, dayId }); return dayId; }
  addExpense(id) { if (id) this.expenses.push(id); return id; }
  addPlace(id) { if (id) this.places.push(id); return id; }
  addMember(id) { if (id) this.members.push(id); return id; }
  addJournal(id) { if (id) this.journals.push(id); return id; }
  addPhoto(id, disk) { if (id) this.photos.push({ id, disk }); return id; }
  addDocument(id, disk) { if (id) this.documents.push({ id, disk }); return id; }
  addBooking(id) { if (id) this.bookings.push(id); return id; }
  addPackingList(id) { if (id) this.packingLists.push(id); return id; }

  run() {
    const errors = [];
    const skipped = [];
    const del = (tbl, sql) => {
      if (!tableExists(tbl)) { skipped.push(tbl); return; }
      try { psql(sql); } catch (e) {
        errors.push(tbl + ': ' + (e && e.message ? e.message.split('\n')[0] : e));
      }
    };
    const q = (id) => "'" + String(id).replace(/'/g, "''") + "'";
    const idList = (items) => items.length
      ? '(' + items.map(q).join(',') + ')' : null;

    // ---- 磁盘文件（仅 /files/<32hex>.<ext>，范围守卫） ----
    const diskNames = [];
    for (const p of this.photos) if (p.disk) diskNames.push(p.disk);
    for (const d of this.documents) if (d.disk) diskNames.push(d.disk);
    const fileDirs = [path.resolve(process.cwd(), 'data/files'),
      path.resolve(process.cwd(), 'apps/server/data/files')];
    for (const name of diskNames) {
      if (!/^\/files\/[a-f0-9]{32}\.[a-z0-9]+$/i.test(name)) {
        errors.push('磁盘文件名不合规（拒绝删除）: ' + name);
        continue;
      }
      const rel = name.replace(/^\/files\//, '');
      let removed = false;
      for (const dir of fileDirs) {
        const fp = path.join(dir, rel);
        try {
          if (fs.existsSync(fp)) { fs.unlinkSync(fp); removed = true; break; }
        } catch (e) { errors.push('磁盘文件删除失败 ' + rel + ': ' + e.message); }
      }
      if (!removed) {
        // 文件已不存在 = 目标已达成（页面删除等），不算错误
        console.log('  (info) 磁盘文件已不存在，跳过: ' + rel);
      }
    }

    // ---- 行程子表（按本次行程 ID 集合） ----
    const tripIds = this.trips;
    const tl = idList(tripIds);
    if (tl) {
      const dayIds = idList(this.days.filter((d) => tripIds.includes(d.tripId)).map((d) => d.dayId));
      if (dayIds) del('edit_locks', `DELETE FROM edit_locks WHERE scope_id IN ${dayIds};`);
      const expenseIds = idList(this.expenses);
      if (expenseIds) del('trip_expense_shares', `DELETE FROM trip_expense_shares WHERE expense_id IN ${expenseIds};`);
      const journalIds = idList(this.journals);
      if (journalIds) {
        del('trip_journal_photos', `DELETE FROM trip_journal_photos WHERE journal_id IN ${journalIds};`);
        del('trip_journal_audience', `DELETE FROM trip_journal_audience WHERE journal_id IN ${journalIds};`);
      }
      const packingIds = idList(this.packingLists);
      if (packingIds) del('packing_items', `DELETE FROM packing_items WHERE packing_list_id IN ${packingIds};`);
      del('journal_edit_locks', `DELETE FROM journal_edit_locks WHERE journal_id IN (SELECT id FROM trip_journals WHERE trip_id IN ${tl});`);
      // 兜底：页面创建但未登记 ID 的子资源也按本次行程 ID 清扫（仍限定本项目范围）
      const bookingIds = idList(this.bookings);
      if (bookingIds) del('bookings', `DELETE FROM bookings WHERE id IN ${bookingIds};`);
      del('bookings', `DELETE FROM bookings WHERE trip_id IN ${tl};`);
      const documentIds = idList(this.documents.map((d) => d.id));
      if (documentIds) del('travel_documents', `DELETE FROM travel_documents WHERE id IN ${documentIds};`);
      del('travel_documents', `DELETE FROM travel_documents WHERE trip_id IN ${tl};`);
      const photoIds = idList(this.photos.map((p) => p.id));
      if (photoIds) del('photos', `DELETE FROM photos WHERE id IN ${photoIds};`);
      del('photos', `DELETE FROM photos WHERE trip_id IN ${tl};`);
      if (journalIds) del('trip_journals', `DELETE FROM trip_journals WHERE id IN ${journalIds};`);
      del('trip_journals', `DELETE FROM trip_journals WHERE trip_id IN ${tl};`);
      del('trip_budgets', `DELETE FROM trip_budgets WHERE trip_id IN ${tl};`);
      del('packing_lists', `DELETE FROM packing_lists WHERE trip_id IN ${tl};`);
      del('trip_places', `DELETE FROM trip_places WHERE trip_id IN ${tl};`);
      del('trip_days', `DELETE FROM trip_days WHERE trip_id IN ${tl};`);
      del('trip_expenses', `DELETE FROM trip_expenses WHERE trip_id IN ${tl};`);
      del('trip_routes', `DELETE FROM trip_routes WHERE trip_id IN ${tl};`);
      const memberIds = idList(this.members);
      if (memberIds) del('trip_members', `DELETE FROM trip_members WHERE id IN ${memberIds};`);
      del('trip_members', `DELETE FROM trip_members WHERE trip_id IN ${tl};`);
      del('trips', `DELETE FROM trips WHERE id IN ${tl};`);
    }
    // 独立记录的成员（非行程集合内）
    const looseMembers = idList(this.members.filter((m) => !tripIds.includes(m)));
    if (looseMembers) del('trip_members', `DELETE FROM trip_members WHERE id IN ${looseMembers};`);

    // ---- 用户侧 ----
    for (const uid of this.users) {
      const u = q(uid);
      del('user_friends', `DELETE FROM user_friends WHERE user_id=${u} OR friend_id=${u};`);
      del('audit_logs', `DELETE FROM audit_logs WHERE user_id=${u};`);
      del('notifications', `DELETE FROM notifications WHERE user_id=${u};`);
      del('user_preferences', `DELETE FROM user_preferences WHERE user_id=${u};`);
      del('ai_messages', `DELETE FROM ai_messages WHERE conversation_id IN (SELECT id FROM ai_conversations WHERE user_id=${u});`);
      del('ai_conversations', `DELETE FROM ai_conversations WHERE user_id=${u};`);
      del('file_metadata', `DELETE FROM file_metadata WHERE owner_user_id=${u};`);
      del('trips', `DELETE FROM trips WHERE owner_id=${u};`);
      del('trip_members', `DELETE FROM trip_members WHERE user_id=${u};`);
      del('users', `DELETE FROM users WHERE id=${u};`);
    }

    // ---- 残留核验（本次准确 ID 必须归零） ----
    const residue = [];
    const check = (label, sql) => {
      try {
        const n = Number(psql(sql));
        if (n > 0) residue.push(label + '=' + n);
      } catch (e) {
        errors.push('残留核验失败 ' + label + ': ' + (e && e.message ? e.message.split('\n')[0] : e));
      }
    };
    if (tl) {
      check('trips', `SELECT count(*) FROM trips WHERE id IN ${tl};`);
      check('trip_days', `SELECT count(*) FROM trip_days WHERE trip_id IN ${tl};`);
      check('trip_places', `SELECT count(*) FROM trip_places WHERE trip_id IN ${tl};`);
      const expenseIds2 = idList(this.expenses);
      if (expenseIds2) check('trip_expenses', `SELECT count(*) FROM trip_expenses WHERE id IN ${expenseIds2};`);
      const photoIds2 = idList(this.photos.map((p) => p.id));
      if (photoIds2) check('photos', `SELECT count(*) FROM photos WHERE id IN ${photoIds2};`);
      const journalIds2 = idList(this.journals);
      if (journalIds2) check('trip_journals', `SELECT count(*) FROM trip_journals WHERE id IN ${journalIds2};`);
      const documentIds2 = idList(this.documents.map((d) => d.id));
      if (documentIds2) check('travel_documents', `SELECT count(*) FROM travel_documents WHERE id IN ${documentIds2};`);
      const bookingIds2 = idList(this.bookings);
      if (bookingIds2) check('bookings', `SELECT count(*) FROM bookings WHERE id IN ${bookingIds2};`);
      const packingIds2 = idList(this.packingLists);
      if (packingIds2) check('packing_lists', `SELECT count(*) FROM packing_lists WHERE id IN ${packingIds2};`);
    }
    for (const uid of this.users) {
      const u = q(uid);
      check('users', `SELECT count(*) FROM users WHERE id=${u};`);
      check('trip_members(' + uid.slice(-6) + ')', `SELECT count(*) FROM trip_members WHERE user_id=${u};`);
    }

    const parts = [];
    if (errors.length) parts.push('错误: ' + errors.join(' | '));
    if (residue.length) parts.push('残留: ' + residue.join(', '));
    if (parts.length) {
      throw new Error('夹具清理失败: ' + parts.join('；')
        + (skipped.length ? '（跳过不存在表: ' + Array.from(new Set(skipped)).join(',') + '）' : ''));
    }
    return { skipped: Array.from(new Set(skipped)) };
  }
}

module.exports = { assertLocalOrigin, psql, tableExists, registerUser, api, waitFor, Cleaner, PG, writePngFile };
