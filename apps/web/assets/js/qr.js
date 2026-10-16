/* ============================================================
 * Travel OS · QR 生成器（独立模块）
 *
 * TravelOSQR.svg(text, size, color) → 可扫描二维码的 SVG 字符串。
 *
 * 编码内核使用 vendor 的 qrcode-generator 1.4.4（MIT License，
 * 原始仓库 https://github.com/kazuhikoarase/qrcode-generator），
 * 本文件只做三件事：开启 UTF-8 多字节支持、封装 SVG 输出、
 * 内建 4 模块静区 —— 保持全站零构建、离线可用。
 * ============================================================ */
(function (global) {
  'use strict';

  /* 默认 stringToBytes 只支持 Latin1；切到 UTF-8 以编码中文祝福语 */
  if (global.qrcode && global.qrcode.stringToBytesFuncs && global.qrcode.stringToBytesFuncs['UTF-8']) {
    global.qrcode.stringToBytes = global.qrcode.stringToBytesFuncs['UTF-8'];
  }

  /**
   * 生成二维码 SVG。
   * @param {string} text   编码内容（UTF-8，中文可直接传入）
   * @param {number} size   输出像素边长（正方形）
   * @param {string} [color] 前景色，默认与凭证页一致的墨青 #0F2E30
   * @returns {string} SVG 字符串（typeNumber 自动按内容长度选择）
   */
  function svg(text, size, color) {
    if (typeof global.qrcode !== 'function') {
      throw new Error('QR 库未加载（vendor/qrcode-generator.min.js）');
    }
    var qr = global.qrcode(0, 'M');   /* typeNumber 0 = 按内容自动选版本 */
    qr.addData(String(text), 'Byte');
    qr.make();

    var n = qr.getModuleCount();
    var quiet = 4;                    /* 规范建议的 4 模块静区 */
    var total = n + quiet * 2;
    var cell = size / total;
    var rects = '';
    for (var r = 0; r < n; r++) {
      for (var c = 0; c < n; c++) {
        if (qr.isDark(r, c)) {
          rects += '<rect x="' + ((c + quiet) * cell).toFixed(2) + '" y="' + ((r + quiet) * cell).toFixed(2)
            + '" width="' + cell.toFixed(2) + '" height="' + cell.toFixed(2) + '"/>';
        }
      }
    }
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size
      + '" fill="' + (color || '#0F2E30') + '" role="img" aria-label="二维码：' + String(text).replace(/"/g, '&quot;') + '">'
      + '<rect width="' + size + '" height="' + size + '" fill="#fff"/>'
      + rects + '</svg>';
  }

  global.TravelOSQR = { svg: svg };
})(window);
