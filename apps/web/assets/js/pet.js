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

  var src = '../assets/js/ai-assistant.js';
  try {
    if (document.currentScript && document.currentScript.src) {
      src = document.currentScript.src.replace(/[^/]*$/, 'ai-assistant.js');
    }
  } catch (e) { /* ignore */ }

  if (document.readyState === 'loading') {
    /* 解析期同步注入：保持脚本顺序，TravelOSPet 别名随即可用 */
    document.write('<script src="' + src + '"><\/script>');
  } else {
    var s = document.createElement('script');
    s.src = src;
    document.head.appendChild(s);
  }
})(window);
