/* Travel OS · 3D 图标精灵（共享注入）
 * 每个页面在 <body> 顶部引一次：<script src="../assets/js/icons3d.js"></script>
 * 同步执行并插到脚本节点之前，保证页面里所有 <use href="#ic3d-*"> 在解析时图标已存在。
 *
 * 说明：图标字形保持原样，只调整精灵的“底座样式”。
 * 底座由平面圆角块升级为 3D 黏土块：
 *  - 底部接触阴影 + 右下侧壁（厚度）
 *  - 正面斜向渐变 + 顶部柔光 + 左上高光
 *  - 内描边亮边 + 侧壁外缘淡反光
 * 配色沿用首页 teal 主色与橙色强调（theme.css --brand / --stamp）。
 *
 * 2026-11-21 按 R4 决策裁剪：仅保留 home Hero 在用的 plane/globe/city/luggage/spark
 * 五枚符号与 teal/orange 配色族；其余 19 枚符号及 mint/cyan/sand 配色全站 0 引用（含
 * '#ic3d-' 动态拼接 0 处），已删除——如需恢复见 git 历史。ic-star 为独立通用符号，保留。
 */
(function () {
  'use strict';

  /* 图标 = [底座配色, 原字形 SVG（保持不变）] */
  var ICONS = {
    plane: ['teal',
      '<path d="M35 13 13.2 22.4l8.3 3.7 3.4 8.9 3.6-8.1z" fill="#fff"/>'
      + '<path d="M21.5 26.1 27.7 14.6" stroke="rgba(0,0,0,.16)" stroke-width="1.5" fill="none" stroke-linecap="round"/>'],
    globe: ['teal',
      '<g fill="none" stroke="#fff" stroke-linecap="round">'
      + '<circle cx="24" cy="24" r="12" stroke-width="2.4"/>'
      + '<ellipse cx="24" cy="24" rx="5.2" ry="12" stroke-width="2"/>'
      + '<path d="M12.6 19.6h22.8M12.6 28.4h22.8" stroke-width="1.9"/>'
      + '</g>'],
    luggage: ['orange',
      '<path d="M20.4 17.4v-2.6a2.2 2.2 0 0 1 2.2-2.2h2.8a2.2 2.2 0 0 1 2.2 2.2v2.6" fill="none" stroke="#fff" stroke-width="2.5"/>'
      + '<rect x="14.6" y="17.4" width="18.8" height="15.6" rx="3.2" fill="#fff"/>'
      + '<rect x="20.4" y="17.4" width="2.1" height="15.6" fill="rgba(0,0,0,.15)"/>'
      + '<rect x="25.5" y="17.4" width="2.1" height="15.6" fill="rgba(0,0,0,.15)"/>'
      + '<circle cx="19" cy="34.6" r="1.6" fill="#fff"/><circle cx="29" cy="34.6" r="1.6" fill="#fff"/>'],
    city: ['teal',
      '<rect x="13.8" y="21" width="5.6" height="13.4" fill="#fff"/>'
      + '<rect x="21.4" y="15.6" width="6.2" height="18.8" fill="#fff"/>'
      + '<rect x="29.6" y="23" width="5.2" height="11.4" fill="#fff"/>'
      + '<g fill="rgba(0,0,0,.22)"><rect x="23" y="18.6" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="18.6" width="1.5" height="2.6" rx=".4"/><rect x="23" y="22.4" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="22.4" width="1.5" height="2.6" rx=".4"/><rect x="23" y="26.2" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="26.2" width="1.5" height="2.6" rx=".4"/><rect x="15.6" y="24" width="1.5" height="2.6" rx=".4"/><rect x="15.6" y="28" width="1.5" height="2.6" rx=".4"/><rect x="31.2" y="25.6" width="1.5" height="2.6" rx=".4"/><rect x="31.2" y="29.4" width="1.5" height="2.6" rx=".4"/></g>'],
  };

  /* 底座正面渐变（首页 teal 主色 / 橙色强调） */
  var FACE_GRADIENTS = {
    teal:   ['#83E5DB', '#34BFB5', '#0C8B8A', '#075A5F'],
    orange: ['#FFD0AC', '#FF9A5F', '#E3651F', '#A6400F'],
  };
  var SIDE_COLORS = {
    teal:   ['#0A6468', '#03393E'],
    orange: ['#A3400E', '#692706'],
  };

  function faceGradient(key) {
    var c = FACE_GRADIENTS[key];
    return '<linearGradient id="tl-face-' + key + '" x1=".06" y1="0" x2=".94" y2="1">'
      + '<stop offset="0" stop-color="' + c[0] + '"/>'
      + '<stop offset=".36" stop-color="' + c[1] + '"/>'
      + '<stop offset=".74" stop-color="' + c[2] + '"/>'
      + '<stop offset="1" stop-color="' + c[3] + '"/>'
      + '</linearGradient>';
  }

  function sideGradient(key) {
    var c = SIDE_COLORS[key];
    return '<linearGradient id="tl-side-' + key + '" x1="0" y1="0" x2=".35" y2="1">'
      + '<stop offset="0" stop-color="' + c[0] + '"/>'
      + '<stop offset="1" stop-color="' + c[1] + '"/>'
      + '</linearGradient>';
  }

  function tile(id, spec) {
    var face = spec[0];
    var glyph = spec[1];
    return '<symbol id="ic3d-' + id + '" viewBox="0 0 48 48">'
      /* 接触阴影 */
      + '<ellipse cx="24.5" cy="45.6" rx="15.4" ry="2.3" fill="#022326" opacity=".26"/>'
      /* 侧壁：右下露出厚度（外缘一圈极淡反光） */
      + '<rect x="4.6" y="6.5" width="40" height="38.2" rx="12.5" fill="url(#tl-side-' + face + ')" stroke="#fff" stroke-opacity=".12" stroke-width="1.2"/>'
      /* 正面 */
      + '<rect x="4" y="2.5" width="40" height="38" rx="12.5" fill="url(#tl-face-' + face + ')"/>'
      /* 正面光影（裁切在圆角内） */
      + '<g clip-path="url(#tl-clip)">'
      +   '<rect x="4" y="2.5" width="40" height="20" fill="url(#tl-top)"/>'
      +   '<rect x="4" y="24" width="40" height="16.5" fill="url(#tl-shade)"/>'
      +   '<ellipse cx="15.4" cy="13.4" rx="11.2" ry="6.4" fill="url(#tl-spec)"/>'
      + '</g>'
      /* 内描边亮边 */
      + '<rect x="4.75" y="3.2" width="38.5" height="36.6" rx="11.85" fill="none" stroke="url(#tl-rim)" stroke-width="1.3"/>'
      /* 原字形：保持不变，不加任何滤镜 */
      + glyph
      + '</symbol>';
  }

  var symbols = '';
  Object.keys(ICONS).forEach(function (id) {
    symbols += tile(id, ICONS[id]);
  });

  var SPRITE = `<svg width="0" height="0" style="position:absolute" aria-hidden="true" focusable="false">
  <defs>
    ${Object.keys(FACE_GRADIENTS).map(faceGradient).join('')}
    ${Object.keys(SIDE_COLORS).map(sideGradient).join('')}
    <clipPath id="tl-clip"><rect x="4" y="2.5" width="40" height="38" rx="12.5"/></clipPath>
    <linearGradient id="tl-top" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".34"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
    <linearGradient id="tl-shade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#00353A" stop-opacity="0"/><stop offset="1" stop-color="#00353A" stop-opacity=".3"/></linearGradient>
    <radialGradient id="tl-spec" cx=".4" cy=".38" r=".62"><stop offset="0" stop-color="#fff" stop-opacity=".55"/><stop offset=".6" stop-color="#fff" stop-opacity=".16"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
    <linearGradient id="tl-rim" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".64"/><stop offset=".42" stop-color="#fff" stop-opacity=".16"/><stop offset="1" stop-color="#fff" stop-opacity=".05"/></linearGradient>
  </defs>
  ${symbols}
  <symbol id="ic3d-spark" viewBox="0 0 48 48">
    <path d="M22 9.5l2.8 7.2 7.2 2.8-7.2 2.8L22 29.5l-2.8-7.2-7.2-2.8 7.2-2.8z" fill="#FFA45C"/>
    <path d="M34.5 29l1.5 3.8 3.8 1.5-3.8 1.5-1.5 3.8-1.5-3.8-3.8-1.5 3.8-1.5z" fill="#FFC77E"/>
  </symbol>
  <symbol id="ic-star" viewBox="0 0 24 24">
    <path d="M12 3.4l2.7 5.5 6.1.9-4.4 4.3 1 6.1L12 17.2l-5.4 3 1-6.1-4.4-4.3 6.1-.9z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round" fill="none"/>
  </symbol>
</svg>`;

  var cur = document.currentScript;
  if (cur && cur.parentNode) cur.insertAdjacentHTML('beforebegin', SPRITE);
})();
