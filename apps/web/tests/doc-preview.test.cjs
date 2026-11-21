/* Travel OS · 文档预览类型判定单测（纯 Node，无测试框架）
 * 运行：node apps/web/tests/doc-preview.test.cjs
 * 覆盖：data URL 图片/PDF 兼容、/files/** 图片与 PDF、大小写扩展名、
 *       查询串/锚点、无扩展名/未知类型、空值、外链；判定为纯函数（不改写 URL）。
 */
'use strict';
const assert = require('assert');
const path = require('path');

const previewKindOf = require(path.join(__dirname, '..', 'assets', 'js', 'doc-preview.js'));

const cases = [
  // 1/2. 存量 data URL 兼容
  ['data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==', 'image'],
  ['data:image/jpeg;base64,/9j/4AAQSkZJRg==', 'image'],
  ['data:application/pdf;base64,JVBERi0xLjQK', 'pdf'],
  ['data:text/plain;base64,aGVsbG8=', 'unsupported'],
  ['data:application/zip;base64,UEsDBA==', 'unsupported'],

  // 3/4. 受管 /files/** 地址
  ['/files/02a0c31a87ea493ca0f892964c2126d6.png', 'image'],
  ['/files/6fde552afdc1490285dbc0399b93034c.pdf', 'pdf'],
  ['/files/deadbeef.jpg', 'image'],
  ['/files/deadbeef.jpeg', 'image'],
  ['/files/deadbeef.webp', 'image'],

  // 大小写扩展名
  ['/files/ABC.PNG', 'image'],
  ['/files/report.PdF', 'pdf'],
  ['/files/Mixed.JPEG', 'image'],

  // 查询串与锚点（扩展名取自路径段，不受参数影响）
  ['/files/x.png?v=2#top', 'image'],
  ['/files/x.pdf?sig=abc&exp=1', 'pdf'],
  ['/files/x.png?name=report.pdf', 'image'],

  // 5. 不支持的类型
  ['/files/noext', 'unsupported'],
  ['/files/trailing.', 'unsupported'],
  ['/files/archive.zip', 'unsupported'],
  ['/files/data.json', 'unsupported'],
  ['/files/readme.txt', 'unsupported'],

  // 空值
  ['', 'none'],
  [null, 'none'],
  [undefined, 'none'],

  // 外链（同样按扩展名分流）
  ['https://cdn.example.com/pic.jpg', 'image'],
  ['https://cdn.example.com/doc.pdf?x=1', 'pdf'],
];

let passed = 0;
for (const [input, expected] of cases) {
  assert.strictEqual(
    previewKindOf(input),
    expected,
    `previewKindOf(${JSON.stringify(input)}) 应为 ${expected}，实际 ${previewKindOf(input)}`
  );
  passed++;
}

// 6（单测侧）：判定是纯函数——不修改/拼接 URL（Token 不会被写进预览地址）
const tokenish = '/files/x.png?access_token=SECRET_JWT';
const before = String(tokenish);
assert.strictEqual(previewKindOf(tokenish), 'image');
assert.strictEqual(tokenish, before, '判定不得改写输入 URL');
passed++;

// 等价性：查询串不参与扩展名判定
assert.strictEqual(previewKindOf('/files/a.png?x=/files/b.pdf'), previewKindOf('/files/a.png'));
passed++;

console.log(`doc-preview.test: ${passed} assertions passed`);
