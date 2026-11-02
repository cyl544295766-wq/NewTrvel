/* Travel OS · 暗色线条 SVG 图标集（共享注入）
 * 每个页面在 <body> 顶部引一次：<script src="../assets/js/icons-line.js"></script>
 * 与 icons3d.js（3D 黏土块精灵）互不影响；本套为暗色主题设计：
 *  - 24×24 线条图标，stroke="currentColor"，线宽由各页面 CSS 控制
 *  - 双色制：常规 = 浅青 (#8FE0DB)，高亮/行动 = 浅橙 (#FFC08F)，由容器 color 决定
 *  - 星光/警示为填充电流色（fill="currentColor"）
 * 用法：<svg class="ln" viewBox="0 0 24 24"><use href="#ln-plane"></use></svg>
 */
(function () {
  'use strict';

  var S = {
    home: '<path d="M4 10.5 12 4l8 6.5V20a1.5 1.5 0 0 1-1.5 1.5h-4.5v-6h-4v6h-4.5A1.5 1.5 0 0 1 4 20z"/>',
    plane: '<path d="M21 4 3 11l7.2 2.8L13 21z"/><path d="m10.2 13.8 3.6-3.4"/>',
    globe: '<circle cx="12" cy="12" r="8.4"/><path d="M3.6 12h16.8"/>'
      + '<path d="M12 3.6c2.4 2.5 3.6 5.3 3.6 8.4S14.4 18 12 20.4C9.6 18 8.4 15.1 8.4 12S9.6 6.1 12 3.6z"/>',
    friends: '<circle cx="9.2" cy="8.2" r="3.3"/><path d="M3.6 19.8c0-3.3 2.5-5.4 5.6-5.4s5.6 2.1 5.6 5.4"/>'
      + '<circle cx="17.2" cy="9.6" r="2.5"/><path d="M16.4 14.6c2.7.1 4.3 1.9 4.3 4.6"/>',
    stats: '<path d="M5.5 20v-5.5M12 20V8.5M18.5 20V5"/><path d="M3.5 20.5h17"/>',
    bell: '<path d="M18 15.5V11a6 6 0 1 0-12 0v4.5L4.2 18.2h15.6z"/><path d="M10 18.5a2 2 0 0 0 4 0"/>',
    pin: '<path d="M12 21s6.8-6.1 6.8-10.8a6.8 6.8 0 1 0-13.6 0C5.2 14.9 12 21 12 21z"/><circle cx="12" cy="10.2" r="2.5"/>',
    account: '<circle cx="12" cy="8.2" r="3.6"/><path d="M5 20c1.2-3.6 3.8-5.4 7-5.4s5.8 1.8 7 5.4"/>',
    admin: '<path d="M12 3.5 20 6.4v5.2c0 4.9-3.4 8.2-8 9.4-4.6-1.2-8-4.5-8-9.4V6.4z"/><path d="m8.6 12 2.5 2.5 4.7-4.9"/>',
    luggage: '<rect x="5" y="8" width="14" height="12" rx="2.4"/><path d="M9 8V6.2A2.2 2.2 0 0 1 11.2 4h1.6A2.2 2.2 0 0 1 15 6.2V8"/>'
      + '<path d="M8.5 20v1.6M15.5 20v1.6M5 13h14"/>',
    money: '<rect x="3" y="7" width="18" height="11" rx="2.4"/><circle cx="12" cy="12.5" r="2.6"/>'
      + '<circle cx="6.2" cy="10.3" r=".9" fill="currentColor" stroke="none"/><circle cx="17.8" cy="14.7" r=".9" fill="currentColor" stroke="none"/>',
    calendar: '<rect x="4" y="6" width="16" height="15" rx="2.4"/><path d="M4 10.2h16M8.5 3.6V7M15.5 3.6V7"/>'
      + '<g fill="currentColor" stroke="none"><circle cx="9" cy="14.4" r="1"/><circle cx="12" cy="14.4" r="1"/><circle cx="15" cy="14.4" r="1"/><circle cx="9" cy="17.6" r="1"/><circle cx="12" cy="17.6" r="1"/></g>',
    pack: '<path d="M8 9V7a4 4 0 0 1 8 0v2"/><rect x="5" y="9" width="14" height="11.6" rx="3"/><path d="M8.6 13.5v2.4M15.4 13.5v2.4"/>',
    mountain: '<path d="m3 19 6.8-11.6 3.6 6 2.4-3.3L21 19z"/>',
    beach: '<circle cx="8" cy="7" r="2.8"/><path d="M3 14.2c2-1.9 4-1.9 6 0s4 1.9 6 0 4-1.9 6 0"/>'
      + '<path d="M3 18.4c2-1.9 4-1.9 6 0s4 1.9 6 0 4-1.9 6 0"/>',
    book: '<path d="M12 6.6C10.6 5.1 8.6 4.5 4 4.5v13c4.6 0 6.6.6 8 2 1.4-1.4 3.4-2 8-2v-13c-4.6 0-6.6.6-8 2z"/><path d="M12 6.6v13"/>',
    lock: '<rect x="5" y="10.6" width="14" height="9.4" rx="2.4"/><path d="M8 10.6V7.9a4 4 0 0 1 8 0v2.7"/><path d="M12 14v2.6"/>',
    alert: '<path d="M12 4 21 19.5H3z"/><path d="M12 10.2v4.4"/><circle cx="12" cy="17.1" r=".8" fill="currentColor" stroke="none"/>',
    search: '<circle cx="11" cy="11" r="6.8"/><path d="m20.5 20.5-4.6-4.6"/>',
    spark: '<path d="M11 3.5 13 9l5.5 2L13 13l-2 5.5L9 13l-5.5-2L9 9z" fill="currentColor" stroke="none"/>'
      + '<path d="M18 15.5l1 2.5 2.5 1-2.5 1-1 2.5-1-2.5-2.5-1 2.5-1z" fill="currentColor" stroke="none"/>',
    eye: '<path d="M2.5 12C5.2 7.6 8.3 5.5 12 5.5S18.8 7.6 21.5 12 18.8 18.5 12 18.5 5.2 16.4 2.5 12z"/><circle cx="12" cy="12" r="3.1"/>',
    star: '<path d="m12 3.6 2.7 5.5 6.1.9-4.4 4.3 1 6.1-5.4-2.9-5.4 2.9 1-6.1-4.4-4.3 6.1-.9z"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    arrow: '<path d="M7 17 17 7M9.5 7H17v7.5"/>',
    'eye-off': '<path d="M2.5 12C5.2 7.6 8.3 5.5 12 5.5S18.8 7.6 21.5 12 18.8 18.5 12 18.5 5.2 16.4 2.5 12z" opacity=".45"/>'
      + '<circle cx="12" cy="12" r="3.1" opacity=".45"/><path d="M4 20 20 4"/>'
  };

  var symbols = Object.keys(S).map(function (id) {
    return '<symbol id="ln-' + id + '" viewBox="0 0 24 24">' + S[id] + '</symbol>';
  }).join('');

  var SPRITE = '<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false">'
    + '<defs></defs>' + symbols + '</svg>';

  var cur = document.currentScript;
  if (cur && cur.parentNode) cur.insertAdjacentHTML('beforebegin', SPRITE);
})();
