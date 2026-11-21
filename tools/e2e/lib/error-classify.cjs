/* Travel OS · e2e 错误分类器（唯一实现）
 *
 * verify-home.cjs / verify-map.cjs 运行时用它分类 pageerror/console 消息；
 * selftest-map-classify.cjs 的分类回归直接 require 本模块调用同一份逻辑（不复制实现）。
 *
 * 分类优先级（自上而下，先命中先归类）：
 *   0. favicon 忽略。
 *   1. 显式网络错误码 net::ERR_*                        → 环境（唯一允许凭消息判网络失败的依据）。
 *   2. SDK 全局缺失「AMap is not defined」              → 环境（SDK 加载失败证据；
 *      即使以 ReferenceError 形态出现也归环境，故置于异常名判断之前）。
 *   3. 页面运行时异常 TypeError/ReferenceError/...      → 业务（按首行异常名识别；
 *      消息含 网络/域名/加载失败/exceeded/amap 等普通字样【不】改变分类——字样本身不是证据）。
 *   4. 窄化 SDK/Key/限流特征                            → 环境（见下）。
 *   5. 其余（普通 Error、console 报错等）               → 业务。
 *
 * 窄化特征清单——都是「确定来源」的完整字符串/服务端错误码，不是普通关键词：
 *   - 高德地图网络连接失败 / 高德地图脚本加载异常 / 高德插件加载超时 / 未配置高德地图 JS API Key
 *     （只会由 assets/js/amap.js 的 SDK 加载路径发出 → SDK 加载失败/Key 配置证据）
 *   - INVALID_USER* / *USERKEY* / CUQPS / 10021（高德服务端 Key 错误与限流错误码）
 *
 * 业务与环境分别进入 pageErrors / envErrors，永不互相覆盖；
 * 同时存在时由调用方（里程碑分类）分别保留，汇总层业务失败优先定 FAILED 标签。
 *
 * 调用方约定：pageerror 请传 e.stack（首行含「TypeError: xxx」等异常名，供步骤 3 识别）；
 * console.error 传 m.text()。
 */
'use strict';

/* JS 运行时异常名（首行前缀）：命中即业务，普通环境关键词不覆盖 */
const JS_EXC_NAME_RE = /^(TypeError|ReferenceError|SyntaxError|RangeError|EvalError|URIError|AggregateError|DOMException|QuotaExceededError)\b/;
/* 窄化环境特征（完整来源字符串 + 服务端错误码），不含 网络/域名/加载失败/exceeded/amap 等普通字样 */
const NARROW_ENV_PAT = /AMap\s+is\s+not\s+defined|高德地图网络连接失败|高德地图脚本加载异常|高德插件加载超时|未配置高德地图 JS API Key|INVALID_USER|USERKEY|CUQPS|\b10021\b/i;
/* 显式网络错误码（Chromium/Playwright 请求失败的 errorText 与控制台资源报错均带此前缀） */
const NET_CODE_PAT = /net::ERR_[A-Z_]+/;

function createErrorCollector() {
  const pageErrors = [];   /* 业务：页面脚本运行时异常、其余未识别来源的报错 */
  const envErrors = [];    /* 环境：网络错误码 / SDK 加载失败 / Key 错误 / 高德限流 */
  const classify = (kind, text) => {
    const t = String(text == null ? '' : text);
    if (/favicon/i.test(t)) return;
    if (NET_CODE_PAT.test(t)) { envErrors.push(kind + ': ' + t.slice(0, 200)); return; }
    if (/AMap\s+is\s+not\s+defined/i.test(t)) { envErrors.push(kind + ': ' + t.slice(0, 200)); return; }
    const firstLine = (t.split('\n')[0] || '').trim();
    if (kind === 'pageerror' && JS_EXC_NAME_RE.test(firstLine)) {
      pageErrors.push(kind + ': ' + t.slice(0, 200)); return;
    }
    if (NARROW_ENV_PAT.test(t)) { envErrors.push(kind + ': ' + t.slice(0, 200)); return; }
    pageErrors.push(kind + ': ' + t.slice(0, 200));
  };
  return { classify, pageErrors, envErrors };
}

module.exports = { createErrorCollector, JS_EXC_NAME_RE, NARROW_ENV_PAT, NET_CODE_PAT };
