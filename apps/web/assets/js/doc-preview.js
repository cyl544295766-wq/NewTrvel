/* Travel OS · 文档预览类型判定（纯函数，无 DOM 依赖）
 * documents 页的 openPreview 使用；同时被 apps/web/tests/doc-preview.test.cjs 单测引用。
 *
 * previewKindOf(url) → 'image' | 'pdf' | 'unsupported' | 'none'
 * 规则：
 *  - data: URL 按 MIME 分流：image/* → 图片；application/pdf → PDF；其余不支持
 *  - 其余地址（受管 /files/<uuid>.<ext>、同源或外链）取最后一个路径段的扩展名：
 *    忽略查询串与锚点（?v=1#x）、大小写不敏感（.PNG/.Pdf）；
 *    无扩展名（含仅点结尾、隐藏名）或未知扩展 → 不支持内嵌
 *  - 预览地址原样使用，绝不拼接 Token
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.previewKindOf = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  var IMAGE_EXT = {
    png: 1, jpg: 1, jpeg: 1, gif: 1, webp: 1, bmp: 1, avif: 1, svg: 1, ico: 1,
  };

  function previewKindOf(url) {
    if (url == null) return 'none';
    var s = String(url).trim();
    if (s === '') return 'none';
    if (/^data:/i.test(s)) {
      if (/^data:image\//i.test(s)) return 'image';
      if (/^data:application\/pdf/i.test(s)) return 'pdf';
      return 'unsupported';
    }
    // 去锚点、查询串后取最后一个路径段
    var path = s.split('#')[0].split('?')[0];
    var base = path.substring(path.lastIndexOf('/') + 1);
    var dot = base.lastIndexOf('.');
    // 无扩展名（dot<=0：整段无点或为隐藏名）、仅以点结尾
    if (dot <= 0 || dot === base.length - 1) return 'unsupported';
    var ext = base.substring(dot + 1).toLowerCase();
    if (IMAGE_EXT[ext]) return 'image';
    if (ext === 'pdf') return 'pdf';
    return 'unsupported';
  }

  return previewKindOf;
});
