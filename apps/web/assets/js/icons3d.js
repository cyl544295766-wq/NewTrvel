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
 */
(function () {
  'use strict';

  /* 图标 = [底座配色, 原字形 SVG（保持不变）] */
  var ICONS = {
    home: ['teal',
      '<path d="M24 11.5 36.5 21.8V34a2.4 2.4 0 0 1-2.4 2.4h-5.6V28.4h-7v8h-5.6A2.4 2.4 0 0 1 11.5 34V21.8z" fill="#fff"/>'],
    plane: ['teal',
      '<path d="M35 13 13.2 22.4l8.3 3.7 3.4 8.9 3.6-8.1z" fill="#fff"/>'
      + '<path d="M21.5 26.1 27.7 14.6" stroke="rgba(0,0,0,.16)" stroke-width="1.5" fill="none" stroke-linecap="round"/>'],
    globe: ['teal',
      '<g fill="none" stroke="#fff" stroke-linecap="round">'
      + '<circle cx="24" cy="24" r="12" stroke-width="2.4"/>'
      + '<ellipse cx="24" cy="24" rx="5.2" ry="12" stroke-width="2"/>'
      + '<path d="M12.6 19.6h22.8M12.6 28.4h22.8" stroke-width="1.9"/>'
      + '</g>'],
    friends: ['teal',
      '<circle cx="19" cy="19.4" r="4.7" fill="#fff"/>'
      + '<path d="M11 34.5c0-4.6 3.6-7.5 8-7.5s8 2.9 8 7.5z" fill="#fff"/>'
      + '<circle cx="30.6" cy="20.4" r="3.7" fill="#fff" opacity=".82"/>'
      + '<path d="M26.6 34.5c.2-3.8 2-6 4.4-6s4.5 2.2 4.7 6z" fill="#fff" opacity=".82"/>'],
    stats: ['teal',
      '<rect x="13" y="24" width="4.8" height="10.5" rx="1.8" fill="#fff"/>'
      + '<rect x="21.6" y="18" width="4.8" height="16.5" rx="1.8" fill="#fff"/>'
      + '<rect x="30.2" y="12.8" width="4.8" height="21.7" rx="1.8" fill="#fff"/>'],
    bell: ['teal',
      '<path d="M24 11.5a7.6 7.6 0 0 0-7.6 7.6v6.3l-2.7 4.6h20.6l-2.7-4.6v-6.3A7.6 7.6 0 0 0 24 11.5z" fill="#fff"/>'
      + '<path d="M20.9 32.2a3.3 3.3 0 0 0 6.2 0z" fill="#fff"/>'],
    pin: ['teal',
      '<path d="M24 10.8a9.6 9.6 0 0 0-9.6 9.6c0 7.1 9.6 16.8 9.6 16.8s9.6-9.7 9.6-16.8A9.6 9.6 0 0 0 24 10.8z" fill="#fff"/>'
      + '<circle cx="24" cy="20.4" r="3.9" fill="rgba(0,0,0,.26)"/>'],
    account: ['teal',
      '<circle cx="24" cy="18.6" r="5.6" fill="#fff"/>'
      + '<path d="M13.4 35.4c0-5.7 4.8-9.1 10.6-9.1s10.6 3.4 10.6 9.1z" fill="#fff"/>'],
    admin: ['teal',
      '<path d="M24 11.2 35.2 15.4v8.9c0 6.7-4.6 10.8-11.2 12.9-6.6-2.1-11.2-6.2-11.2-12.9v-8.9z" fill="#fff"/>'
      + '<path d="M19.2 24.3 22.8 28l6.2-6.8" stroke="rgba(0,0,0,.3)" stroke-width="2.6" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'],
    luggage: ['orange',
      '<path d="M20.4 17.4v-2.6a2.2 2.2 0 0 1 2.2-2.2h2.8a2.2 2.2 0 0 1 2.2 2.2v2.6" fill="none" stroke="#fff" stroke-width="2.5"/>'
      + '<rect x="14.6" y="17.4" width="18.8" height="15.6" rx="3.2" fill="#fff"/>'
      + '<rect x="20.4" y="17.4" width="2.1" height="15.6" fill="rgba(0,0,0,.15)"/>'
      + '<rect x="25.5" y="17.4" width="2.1" height="15.6" fill="rgba(0,0,0,.15)"/>'
      + '<circle cx="19" cy="34.6" r="1.6" fill="#fff"/><circle cx="29" cy="34.6" r="1.6" fill="#fff"/>'],
    money: ['mint',
      '<rect x="11.8" y="17.2" width="24.4" height="14.4" rx="2.8" fill="#fff"/>'
      + '<path d="M20.4 21 24 25.4l3.6-4.4M24 25.4V30M21.3 26.7h5.4M21.3 28.8h5.4" stroke="rgba(0,0,0,.3)" stroke-width="1.7" fill="none" stroke-linecap="round" stroke-linejoin="round"/>'
      + '<circle cx="15.4" cy="20.6" r="1.3" fill="rgba(0,0,0,.18)"/><circle cx="32.6" cy="28.2" r="1.3" fill="rgba(0,0,0,.18)"/>'],
    calendar: ['cyan',
      '<rect x="13" y="15.4" width="22" height="19.2" rx="3" fill="#fff"/>'
      + '<path d="M13 18.4a3 3 0 0 1 3-3h16a3 3 0 0 1 3 3v2.6H13z" fill="rgba(0,0,0,.2)"/>'
      + '<rect x="17.3" y="12.2" width="2.7" height="5.8" rx="1.35" fill="#fff"/>'
      + '<rect x="28" y="12.2" width="2.7" height="5.8" rx="1.35" fill="#fff"/>'
      + '<g fill="rgba(0,0,0,.24)"><circle cx="18.5" cy="25.8" r="1.7"/><circle cx="24" cy="25.8" r="1.7"/><circle cx="29.5" cy="25.8" r="1.7"/><circle cx="18.5" cy="30.4" r="1.7"/><circle cx="24" cy="30.4" r="1.7"/></g>'],
    pack: ['teal',
      '<path d="M18.8 21.4v-2.8A2.6 2.6 0 0 1 21.4 16h5.2a2.6 2.6 0 0 1 2.6 2.6v2.8" fill="none" stroke="#fff" stroke-width="2.6"/>'
      + '<rect x="11.8" y="21.4" width="24.4" height="11.6" rx="5.8" fill="#fff"/>'
      + '<path d="M18.6 21.6v11.2M29.4 21.6v11.2" stroke="rgba(0,0,0,.16)" stroke-width="1.8"/>'],
    desert: ['sand',
      '<circle cx="30.8" cy="16.4" r="4.2" fill="rgba(0,0,0,.2)"/>'
      + '<path d="M11 31.8c2.6-4.6 5.6-4.6 8.2 0 2.3 4 5.2 3.7 7.9-.4 2.2-3.3 4.9-2.9 7.4.4V35H11z" fill="#fff"/>'],
    mountain: ['mint',
      '<circle cx="31.4" cy="15.4" r="3.8" fill="rgba(0,0,0,.2)"/>'
      + '<path d="M11.4 33.6 20 18.4l4.8 8.2 3.7-5.2 7.1 12.2z" fill="#fff"/>'],
    beach: ['cyan',
      '<circle cx="16.6" cy="16.4" r="4.1" fill="#fff"/>'
      + '<path d="M11 29c2-2.6 4-2.6 6 0s4 2.6 6 0 4-2.6 6 0 4 2.6 6 0v6H11z" fill="#fff"/>'
      + '<path d="M11 32.4c2-2.6 4-2.6 6 0s4 2.6 6 0 4-2.6 6 0 4 2.6 6 0" stroke="rgba(0,0,0,.16)" stroke-width="2" fill="none"/>'],
    lake: ['cyan',
      '<path d="M11.8 27.2 17.6 18l3.5 5.4 2.9-4 5.6 7.8z" fill="#fff"/>'
      + '<path d="M11 29.6c2-2.4 4-2.4 6 0s4 2.4 6 0 4-2.4 6 0 4 2.4 6 0V35H11z" fill="#fff"/>'
      + '<path d="M11 32.8c2-2.4 4-2.4 6 0s4 2.4 6 0 4-2.4 6 0 4 2.4 6 0" stroke="rgba(0,0,0,.16)" stroke-width="2" fill="none"/>'],
    city: ['teal',
      '<rect x="13.8" y="21" width="5.6" height="13.4" fill="#fff"/>'
      + '<rect x="21.4" y="15.6" width="6.2" height="18.8" fill="#fff"/>'
      + '<rect x="29.6" y="23" width="5.2" height="11.4" fill="#fff"/>'
      + '<g fill="rgba(0,0,0,.22)"><rect x="23" y="18.6" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="18.6" width="1.5" height="2.6" rx=".4"/><rect x="23" y="22.4" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="22.4" width="1.5" height="2.6" rx=".4"/><rect x="23" y="26.2" width="1.5" height="2.6" rx=".4"/><rect x="24.5" y="26.2" width="1.5" height="2.6" rx=".4"/><rect x="15.6" y="24" width="1.5" height="2.6" rx=".4"/><rect x="15.6" y="28" width="1.5" height="2.6" rx=".4"/><rect x="31.2" y="25.6" width="1.5" height="2.6" rx=".4"/><rect x="31.2" y="29.4" width="1.5" height="2.6" rx=".4"/></g>'],
    book: ['teal',
      '<path d="M13.4 14.4h8.1A2.5 2.5 0 0 1 24 16.9V33.6a2.5 2.5 0 0 0-2.5-2.5h-8.1z" fill="#fff"/>'
      + '<path d="M34.6 14.4h-8.1A2.5 2.5 0 0 0 24 16.9V33.6a2.5 2.5 0 0 1 2.5-2.5h8.1z" fill="#fff" opacity=".85"/>'
      + '<rect x="23.3" y="16.2" width="1.4" height="16.6" rx=".7" fill="rgba(0,0,0,.15)"/>'],
    lock: ['teal',
      '<path d="M17.2 22.4v-4.2a6.8 6.8 0 0 1 13.6 0v4.2" fill="none" stroke="#fff" stroke-width="3.2" stroke-linecap="round"/>'
      + '<rect x="13.4" y="22.4" width="21.2" height="14.2" rx="3.2" fill="#fff"/>'
      + '<circle cx="24" cy="27.8" r="2.3" fill="rgba(0,0,0,.3)"/>'
      + '<rect x="23" y="28.6" width="2" height="4.4" rx="1" fill="rgba(0,0,0,.3)"/>'],
    alert: ['orange',
      '<path d="M24 12.5 37.5 34.5h-27z" fill="#fff" stroke="#fff" stroke-width="2.6" stroke-linejoin="round"/>'
      + '<rect x="22.5" y="21" width="3" height="8" rx="1.5" fill="rgba(0,0,0,.32)"/>'
      + '<circle cx="24" cy="31.8" r="1.9" fill="rgba(0,0,0,.32)"/>'],
    search: ['teal',
      '<g fill="none" stroke="#fff" stroke-width="3.4" stroke-linecap="round">'
      + '<circle cx="22" cy="22" r="8.5"/>'
      + '<path d="M28.6 28.6 35.6 35.6"/>'
      + '</g>'],
    eye: ['teal',
      '<path d="M8 24c3.4-5.6 8.3-8.2 16-8.2s12.6 2.6 16 8.2c-3.4 5.6-8.3 8.2-16 8.2S11.4 29.6 8 24z" fill="#fff"/>'
      + '<circle cx="24" cy="24" r="4.8" fill="#0A5F5A"/>'],
    'eye-off': ['teal',
      '<path d="M8 24c3.4-5.6 8.3-8.2 16-8.2s12.6 2.6 16 8.2c-3.4 5.6-8.3 8.2-16 8.2S11.4 29.6 8 24z" fill="#fff" opacity=".45"/>'
      + '<circle cx="24" cy="24" r="4.8" fill="#0A5F5A" opacity=".45"/>'
      + '<path d="M12.5 34.5 35.5 13.5" stroke="#fff" stroke-width="3.4" stroke-linecap="round"/>']
  };

  /* 底座正面渐变（首页 teal 主色 / 橙色强调） */
  var FACE_GRADIENTS = {
    teal:   ['#83E5DB', '#34BFB5', '#0C8B8A', '#075A5F'],
    mint:   ['#ADF0D6', '#4CC99E', '#128B6B', '#075C4E'],
    cyan:   ['#9FE9F1', '#47BCCC', '#0F7F91', '#075465'],
    orange: ['#FFD0AC', '#FF9A5F', '#E3651F', '#A6400F'],
    sand:   ['#FFE7C0', '#F5BB67', '#CB8626', '#8E570F']
  };
  var SIDE_COLORS = {
    teal:   ['#0A6468', '#03393E'],
    mint:   ['#0A6B56', '#033C35'],
    cyan:   ['#096576', '#033B45'],
    orange: ['#A3400E', '#692706'],
    sand:   ['#8B540F', '#593306']
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
