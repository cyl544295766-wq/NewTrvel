/* AI 发送策略（幂等 / 回退 / 停止语义）自动化测试
 * 运行：node --test apps/web/tests/ai-send-policy.test.cjs */
const test = require('node:test');
const assert = require('node:assert');
const policy = require('../assets/js/ai-send-policy.js');

test('每次发送生成稳定且互不相同的 clientRequestId（停止后的新发送 = 新 ID）', () => {
  const a = policy.newRequestId();
  const b = policy.newRequestId();
  assert.ok(a && typeof a === 'string' && a.length >= 8, 'ID 需为可幂等使用的字符串');
  assert.notStrictEqual(a, b, '两次发送必须是不同 ID（停止后不被旧幂等记录阻挡）');
  // 格式与服务端白名单 [A-Za-z0-9_-]{8,64} 兼容
  assert.match(a, /^[A-Za-z0-9_-]+$/);
});

test('只有可确认“未被服务端接受”时才允许回退普通接口', () => {
  // 不支持流式 / 无 Token → 请求根本没发出 → 允许 fallback
  assert.strictEqual(
    policy.decideStreamFallback({ streamSupported: false, hasToken: true }).action, 'fallback');
  assert.strictEqual(
    policy.decideStreamFallback({ streamSupported: true, hasToken: false }).action, 'fallback');
  // 401/403 → 服务端未执行 → 允许 fallback
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 401, bodyReadable: true }).action, 'fallback');
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 403, bodyReadable: true }).action, 'fallback');
});

test('请求可能已被接受时只对齐，不盲目重发', () => {
  // 网络中断（请求可能已到达服务端）
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, fetchFailed: true }).action, 'align');
  // 5xx（服务端已接收处理但出错）
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 503, bodyReadable: true }).action, 'align');
  // 响应体不可读
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 200, bodyReadable: false }).action, 'align');
  // 缺状态（异常路径）
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true }).action, 'align');
});

test('服务端明确拒绝（429/4xx）时中止而非重发；正常响应才读流', () => {
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 429, bodyReadable: true }).action, 'abort');
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 400, bodyReadable: true }).action, 'abort');
  assert.strictEqual(policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, status: 200, bodyReadable: true }).action, 'read');
});

test('权威消息列表按内容识别已落库的用户消息', () => {
  const messages = [
    { role: 'assistant', content: '你好' },
    { role: 'user', content: ' 帮我排一下行程 ' },
    { role: 'assistant', content: '好的' },
  ];
  assert.strictEqual(policy.hasUserMessageWithText(messages, '帮我排一下行程'), true, 'trim 后匹配');
  assert.strictEqual(policy.hasUserMessageWithText(messages, '帮我排一下行程  '), true);
  assert.strictEqual(policy.hasUserMessageWithText(messages, '另一条消息'), false);
  assert.strictEqual(policy.hasUserMessageWithText([], 'x'), false);
  assert.strictEqual(policy.hasUserMessageWithText(null, 'x'), false);
  assert.strictEqual(policy.hasUserMessageWithText([{ role: 'assistant', content: 'x' }], 'x'),
    false, 'assistant 同文本不算用户消息');
  assert.strictEqual(policy.hasUserMessageWithText([{ role: 'user', content: '' }], ''), false);
});

test('对齐后：消息已存在则绝不重发；未落库才允许带同一 ID 回退', () => {
  const text = '记一笔 240';
  const accepted = [{ role: 'user', content: text }];
  assert.strictEqual(policy.shouldFallbackAfterAlign(accepted, text), false,
    '服务端已接受 → 禁止再次发送');
  assert.strictEqual(policy.shouldFallbackAfterAlign(
    [{ role: 'user', content: '别的' }], text), true,
    '确认未落库 → 允许用同一 clientRequestId 回退');
  // 无法确认（列表为空/不可用）时：空列表视为未落库（回退由同一幂等键兜底，不会重复执行）
  assert.strictEqual(policy.shouldFallbackAfterAlign([], text), true);
});

test('组合场景：流式中途失败且消息已落库 → 不得再次调用普通发送', () => {
  // 前端读流中断（已收到部分内容 → 服务端已入库）
  const decision = policy.decideStreamFallback(
    { streamSupported: true, hasToken: true, fetchFailed: true });
  assert.strictEqual(decision.action, 'align');
  const serverMessages = [
    { role: 'user', content: '北京三日游怎么排' },
    { role: 'assistant', content: '第一天……' },
  ];
  assert.strictEqual(
    policy.shouldFallbackAfterAlign(serverMessages, '北京三日游怎么排'), false,
    '已入库 → 对齐渲染，禁止第二次生成');
});

test('组合场景：停止后的再次发送使用新 ID（不受旧幂等记录阻挡）', () => {
  const first = policy.newRequestId();
  // 用户停止 → 任务被取消；再次发送生成新 ID（服务端 cancelled 键不会挡新键）
  const second = policy.newRequestId();
  assert.notStrictEqual(first, second);
  // 服务端对 cancelled 的语义由后端保证：新 ID 不等于旧键 → 不会被回放
  assert.match(second, /^[A-Za-z0-9_-]+$/);
});
