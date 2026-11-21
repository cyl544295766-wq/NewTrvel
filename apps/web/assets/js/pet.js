/* ============================================================
 * Travel OS · pet.js（兼容垫片）
 *
 * 原「悬浮宠物」已升级为统一组件 assets/js/ai-assistant.js。
 * 本文件保留原路径，供仍引用 pet.js 的页面无改动加载新组件：
 *   - 同步（解析期）注入 ai-assistant.js，保证后续脚本能看到 TravelOSAi / TravelOSPet；
 *   - 页面若已加载新组件则直接跳过（幂等）；
 *   - window.TravelOSPetAuto === false 的禁用开关仍由新组件读取。
 * ============================================================ */
(function (global) {
  'use strict';

  if (global.TravelOSAi && global.TravelOSAi.__loaded) return;

  var base = '../assets/js/';
  try {
    if (document.currentScript && document.currentScript.src) {
      base = document.currentScript.src.replace(/[^/]*$/, '');
    }
  } catch (e) { /* ignore */ }

  /* 先加载纯策略模块（幂等/回退判定），再加载组件本体，保证运行时可用 */
  if (document.readyState === 'loading') {
    /* 解析期同步注入：保持脚本顺序 */
    document.write('<script src="' + base + 'ai-send-policy.js"><\/script>');
    document.write('<script src="' + base + 'ai-assistant.js"><\/script>');
  } else {
    var policy = document.createElement('script');
    policy.src = base + 'ai-send-policy.js';
    policy.async = false;
    document.head.appendChild(policy);
    var s = document.createElement('script');
    s.src = base + 'ai-assistant.js';
    s.async = false; // 保证策略先于组件执行
    document.head.appendChild(s);
  }
})(window);
