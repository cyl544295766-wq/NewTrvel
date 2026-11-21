/* Travel OS · verify-map 失败分类自检（分类回归 + 可控故障注入）
 *
 * Part 1 分类回归：直接 require scripts 实际使用的 tools/e2e/lib/error-classify.cjs
 *   （verify-home / verify-map 运行时用的同一份实现），逐条断言分类结果——不复制另一套实现自证。
 * Part 2 故障注入：以 E2E_FAULT 各模式跑一遍 tools/e2e/verify-map.cjs（仅作用于测试上下文、
 *   不改生产逻辑），核对：失败分类标签、退出码非零、不打印 ALL PASS、夹具清理成功。
 *
 *   sdk-abort          : 高德/autonavi 请求全部 abort（SDK 请求失败证据）   → ENV FAILURE + 业务失败 0
 *   route-fail         : 驾车 JSONP 注入 10021 限流应答（页面降级证据）     → ENV FAILURE + 业务失败 0
 *   route-hang         : /route API 挂起、无任何错误事件（无证据超时）      → UNDETERMINED + 业务/环境均 0
 *   biz-throw          : 注入含「网络」+amap.js 路径的页面 TypeError        → FAILED + 业务失败 ≥1
 *   biz-throw,sdk-abort: 业务异常与 SDK 请求失败同时存在                    → FAILED + 业务/环境证据均保留
 *
 * 运行：NODE_PATH=<managed node_modules> node tools/e2e/selftest-map-classify.cjs
 *       （--classify-only：只跑 Part 1 纯分类回归后退出，零浏览器/账号/数据库/网络依赖）
 * 前置同 verify-map（本地 3001 + PG 容器）；Part 2 各模式注册 1 个账号并自清理。
 */
const { spawnSync } = require('child_process');
const path = require('path');
const { createErrorCollector } = require('./lib/error-classify.cjs');

/* --classify-only：只跑 Part 1 分类回归后退出——不启动浏览器、不注册账号、
 * 不连接数据库、不访问地图服务（CI 用；完整自检不带参数照常运行 Part 1+2） */
const CLASSIFY_ONLY = process.argv.includes('--classify-only');

/* ================= Part 1：分类回归（真实分类模块） ================= */
console.log('=== Part 1：分类回归（调用 lib/error-classify.cjs 实际实现）===');
let unitFail = 0;
function unitCase(desc, fn) {
  try { fn(); console.log('  ok  ' + desc); }
  catch (e) { unitFail++; console.error('  !!  ' + desc + '\n       ' + (e && e.message || e)); }
}
function expectCounts(c, expBiz, expEnv) {
  const biz = c.pageErrors.length, env = c.envErrors.length;
  if (biz !== expBiz || env !== expEnv) {
    throw new Error('期望 biz=' + expBiz + ' env=' + expEnv + '，实为 biz=' + biz + ' env=' + env
      + ' | page=[' + c.pageErrors.join(' ; ') + '] env=[' + c.envErrors.join(' ; ') + ']');
  }
}

unitCase('任务复现案例：业务 TypeError 含「网络」→ 业务，不归环境', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'TypeError: 网络状态组件读取 undefined');
  expectCounts(c, 1, 0);
});
unitCase('业务异常含「域名」「加载失败」→ 业务', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'Error: 域名白名单组件加载失败');
  expectCounts(c, 1, 0);
});
unitCase('本地存储 QuotaExceededError（含 exceeded）≠ 地图限流 → 业务', () => {
  const c = createErrorCollector();
  c.classify('pageerror',
    "QuotaExceededError: Failed to execute 'setItem' on 'Storage': Setting the value of 'travelos.route.v1.x' exceeded the quota.");
  expectCounts(c, 1, 0);
});
unitCase('业务异常含 amap / 本地 assets/js/amap.js 路径 → 业务', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'TypeError: cannot read routeCache of undefined\n    at assets/js/amap.js:1:1');
  expectCounts(c, 1, 0);
});
unitCase('SDK 全局缺失（ReferenceError: AMap is not defined）→ 环境（SDK 加载失败证据优先于异常名）', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'ReferenceError: AMap is not defined');
  expectCounts(c, 0, 1);
});
unitCase('SDK 加载失败（amap.js 加载路径的确定字符串）→ 环境', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'Error: 高德地图网络连接失败，请检查网络');
  expectCounts(c, 0, 1);
});
unitCase('显式网络错误码 net::ERR_FAILED → 环境', () => {
  const c = createErrorCollector();
  c.classify('console', 'Failed to load resource: net::ERR_FAILED');
  expectCounts(c, 0, 1);
});
unitCase('Key 无效 INVALID_USER_SCODE → 环境', () => {
  const c = createErrorCollector();
  c.classify('console', 'JSAPI error: INVALID_USER_SCODE');
  expectCounts(c, 0, 1);
});
unitCase('高德限流 CUQPS / infocode 10021 → 环境', () => {
  const c = createErrorCollector();
  c.classify('console', 'CUQPS_HAS_EXCEEDED_THE_LIMIT (infocode 10021)');
  expectCounts(c, 0, 1);
});
unitCase('业务与环境证据同时存在 → 分别保留，互不覆盖', () => {
  const c = createErrorCollector();
  c.classify('pageerror', 'TypeError: 业务组件读取 undefined');
  c.classify('console', 'Failed to load resource: net::ERR_CONNECTION_REFUSED');
  expectCounts(c, 1, 1);
});
if (unitFail) {
  console.error('\n=== CLASSIFY REGRESSION FAILED（' + unitFail + ' 例不符合预期）===');
  process.exit(1);
}
console.log('=== CLASSIFY REGRESSION ALL PASS ===\n');

if (CLASSIFY_ONLY) {
  console.log('=== CLASSIFY-ONLY DONE（纯分类模式：未启动浏览器、未注册账号、未连接数据库、未访问地图服务）===');
  process.exit(0);
}

/* ================= Part 2：故障注入端到端验证 ================= */
const NODE = process.execPath;
const TARGET = path.join(__dirname, 'verify-map.cjs');
const TIMEOUT_MS = 300000;   /* 单模式上限（故障模式已缩短里程碑等待预算） */

const MODES = [
  {
    fault: 'sdk-abort',
    expect: { label: 'ENV FAILURE', biz: 0, envMin: 1, undet: 0 },
    why: 'SDK 请求失败 → 有明确环境证据'
  },
  {
    fault: 'route-fail',
    expect: { label: 'ENV FAILURE', biz: 0, envMin: 1, undet: 0 },
    why: '页面降级提示（未返回）→ 有明确环境证据'
  },
  {
    fault: 'route-hang',
    expect: { label: 'UNDETERMINED FAILURE', biz: 0, env: 0, undetMin: 1 },
    why: '挂起且无任何错误事件 → 原因未定的超时，不武断归因'
  },
  {
    fault: 'biz-throw',
    expect: { label: 'FAILED', bizMin: 1, env: 0 },
    why: '页面业务 TypeError（含 网络+amap.js 路径）→ 业务失败，不被关键词误分类'
  },
  {
    fault: 'biz-throw,sdk-abort',
    expect: { label: 'FAILED', bizMin: 1, envMin: 1 },
    why: '业务与环境证据同时存在 → 分别保留，业务定 FAILED 标签'
  }
];

let failed = 0;
for (const m of MODES) {
  console.log('\n=== fault=' + m.fault + '（' + m.why + '）===');
  const r = spawnSync(NODE, [TARGET], {
    encoding: 'utf8',
    timeout: TIMEOUT_MS,
    env: { ...process.env, E2E_FAULT: m.fault, E2E_SHOTS: 'D:/tmp/map-shots-fault-' + m.fault.replace(/[^a-z-]+/gi, '-') },
    maxBuffer: 32 * 1024 * 1024
  });
  const out = (r.stdout || '') + (r.stderr || '');
  const last = out.trim().split('\n').slice(-6).join('\n');
  console.log(last.length ? '  [输出尾部]\n' + last.split('\n').map((l) => '    ' + l).join('\n') : '  (无输出)');

  const errs = [];
  if (r.error && r.error.code === 'ETIMEDOUT') errs.push('运行超时（>' + TIMEOUT_MS / 1000 + 's）');
  if (r.status === 0) errs.push('退出码为 0（失败场景必须非零退出）');
  if (out.includes('=== ALL PASS ===')) errs.push('失败场景打印了 ALL PASS');
  if (!out.includes('=== ' + m.expect.label)) errs.push('缺少标签 === ' + m.expect.label + ' ===');
  const line = (out.match(/业务断言失败: (\d+) \| 环境失败[^:]*: (\d+) \| 原因未定的超时[^:]*: (\d+)/) || []);
  const counts = { biz: Number(line[1]), env: Number(line[2]), undet: Number(line[3]) };
  if (!Number.isFinite(counts.biz)) errs.push('未解析到结果计数行');
  if (m.expect.biz !== undefined && counts.biz !== m.expect.biz) errs.push('业务失败应为 ' + m.expect.biz + '，实为 ' + counts.biz);
  if (m.expect.bizMin !== undefined && counts.biz < m.expect.bizMin) errs.push('业务失败应 ≥' + m.expect.bizMin + '，实为 ' + counts.biz);
  if (m.expect.env !== undefined && counts.env !== m.expect.env) errs.push('环境失败应为 ' + m.expect.env + '，实为 ' + counts.env);
  if (m.expect.envMin !== undefined && counts.env < m.expect.envMin) errs.push('环境失败应 ≥' + m.expect.envMin + '，实为 ' + counts.env);
  if (m.expect.undet !== undefined && counts.undet !== m.expect.undet) errs.push('原因未定应为 ' + m.expect.undet + '，实为 ' + counts.undet);
  if (m.expect.undetMin !== undefined && counts.undet < m.expect.undetMin) errs.push('原因未定应 ≥' + m.expect.undetMin + '，实为 ' + counts.undet);
  if (!/清理: OK/.test(out)) errs.push('夹具清理未成功（失败也必须清理本次夹具）');
  if (!/残留为 0|夹具已清除并核验残留为 0/.test(out)) errs.push('未见残留核验通过标记');

  if (errs.length) { failed++; console.log('  !!  ' + errs.join('；')); }
  else console.log('  OK  分类/退出码/清理均符合预期（biz=' + counts.biz + ' env=' + counts.env + ' undet=' + counts.undet + '，exit=' + r.status + '）');
}

console.log('\n--- selftest 结果 ---');
if (failed === 0) { console.log('=== SELFTEST ALL PASS（分类回归 + ' + MODES.length + ' 种故障场景均符合预期）==='); process.exit(0); }
console.log('=== SELFTEST FAILED（' + failed + ' 种模式不符合预期）===');
process.exit(1);
