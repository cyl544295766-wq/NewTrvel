import fs from "node:fs";
import path from "node:path";
import {
  AlignmentType,
  Document,
  Footer,
  Header,
  HeadingLevel,
  ImportedXmlComponent,
  LevelFormat,
  Packer,
  PageBreak,
  PageNumber,
  Paragraph,
  ShadingType,
  Table,
  TableCell,
  TableRow,
  TextRun,
  WidthType,
  convertInchesToTwip,
} from "docx";

const outputPath = process.argv[2];
if (!outputPath) throw new Error("Usage: node create.js /absolute/path/output.docx");
fs.mkdirSync(path.dirname(outputPath), { recursive: true });

const T = String.raw;
const title = T`TravelOS 业务功能规划建议`;
const palette = {
  dark: "173F3D",
  primary: "176B66",
  accent: "C76B3F",
  text: "263635",
  muted: "647775",
  border: "D5E2DF",
  fill: "EDF5F3",
  warm: "FBF3EC",
  white: "FFFFFF",
};
const font = {
  ascii: "Aptos",
  hAnsi: "Aptos",
  cs: "Aptos",
  eastAsia: "Microsoft YaHei",
};
const bodyFont = {
  ascii: "Aptos",
  hAnsi: "Aptos",
  cs: "Aptos",
  eastAsia: "SimSun",
};

const run = (text, options = {}) => new TextRun({
  text,
  font: bodyFont,
  size: 21,
  color: palette.text,
  ...options,
});
const para = (children, options = {}) => new Paragraph({
  spacing: { after: 140, line: 310 },
  ...options,
  children: Array.isArray(children) ? children : [children],
});
const p = (text, options = {}) => para(run(text), {
  indent: { firstLine: convertInchesToTwip(0.29) },
  ...options,
});
const plain = (text, options = {}) => para(run(text), options);
const h1 = (text) => para(run(text, {
  bold: true,
  size: 30,
  font,
  color: palette.dark,
}), {
  heading: HeadingLevel.HEADING_1,
  spacing: { before: 360, after: 180 },
  keepNext: true,
  border: { bottom: { style: "single", color: palette.border, size: 8, space: 6 } },
});
const h2 = (text) => para(run(text, {
  bold: true,
  size: 25,
  font,
  color: palette.primary,
}), {
  heading: HeadingLevel.HEADING_2,
  spacing: { before: 280, after: 130 },
  keepNext: true,
});
const bullet = (text, reference = "bullets") => para(run(text), {
  numbering: { reference, level: 0 },
  indent: { left: 420, hanging: 240 },
  spacing: { after: 90, line: 290 },
});
const labelPara = (label, text) => para([
  run(label, { bold: true, font, color: palette.dark }),
  run(text),
], { spacing: { after: 110, line: 300 } });
const note = (text) => para(run(text, { size: 19, color: palette.muted, italics: true }), {
  shading: { type: ShadingType.CLEAR, fill: palette.fill },
  indent: { left: 180, right: 180 },
  spacing: { before: 80, after: 160, line: 280 },
});
const pageBreak = () => new Paragraph({ children: [new PageBreak()] });

const cellPara = (text, options = {}) => para(run(text, {
  size: options.size || 18,
  bold: options.bold || false,
  color: options.color || palette.text,
  font: options.bold ? font : bodyFont,
}), {
  alignment: options.alignment || AlignmentType.LEFT,
  spacing: { after: 30, line: 245 },
});
const cell = (text, width, options = {}) => new TableCell({
  children: [cellPara(text, options)],
  width: { size: width, type: WidthType.DXA },
  margins: { top: 100, bottom: 100, left: 110, right: 110 },
  shading: options.fill ? { type: ShadingType.CLEAR, fill: options.fill } : undefined,
  verticalAlign: "center",
});
const makeTable = (widths, headers, rows, options = {}) => new Table({
  width: { size: widths.reduce((a, b) => a + b, 0), type: WidthType.DXA },
  columnWidths: widths,
  rows: [
    new TableRow({
      tableHeader: true,
      children: headers.map((text, i) => cell(text, widths[i], {
        bold: true,
        color: palette.white,
        fill: palette.primary,
        alignment: AlignmentType.CENTER,
      })),
    }),
    ...rows.map((row, rowIndex) => new TableRow({
      cantSplit: true,
      children: row.map((text, i) => cell(String(text), widths[i], {
        fill: rowIndex % 2 === 1 ? "F6FAF9" : palette.white,
        bold: options.boldFirst && i === 0,
        alignment: options.centerColumns?.includes(i) ? AlignmentType.CENTER : AlignmentType.LEFT,
      })),
    })),
  ],
  borders: {
    top: { style: "single", size: 4, color: palette.border },
    bottom: { style: "single", size: 4, color: palette.border },
    left: { style: "single", size: 4, color: palette.border },
    right: { style: "single", size: 4, color: palette.border },
    insideHorizontal: { style: "single", size: 4, color: palette.border },
    insideVertical: { style: "single", size: 4, color: palette.border },
  },
});
const tableCaption = (text) => para(run(text, { size: 18, color: palette.muted }), {
  alignment: AlignmentType.CENTER,
  spacing: { before: 50, after: 180 },
});

const xmlEscape = (value) => String(value)
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;");
const toc = (entries) => {
  const cached = entries.map(({ title: entryTitle, level, page }) => {
    const indent = Math.max(0, level - 1) * 360;
    return `<w:p><w:pPr><w:pStyle w:val="TOC${level}"/><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="8800"/></w:tabs><w:ind w:left="${indent}"/></w:pPr><w:r><w:t>${xmlEscape(entryTitle)}</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>${page}</w:t></w:r></w:p>`;
  }).join("");
  return ImportedXmlComponent.fromXmlString(`<w:sdt xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:sdtPr><w:alias w:val="目录"/></w:sdtPr><w:sdtContent><w:p><w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/><w:instrText xml:space="preserve"> TOC \\o &quot;1-2&quot; \\h \\z \\u </w:instrText><w:fldChar w:fldCharType="separate"/></w:r></w:p>${cached}<w:p><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p></w:sdtContent></w:sdt>`).root[0];
};

const features = [
  {
    id: "F01",
    name: "今日模式与离线出行包",
    priority: "P0",
    horizon: "0—3 个月",
    goal: "提升行中使用频率",
    effort: "中",
    dependency: "现有行程、预订、凭证、天气与地图数据",
    problem: "现有模块覆盖完整，但用户在旅途现场仍需要在多个页面之间切换。行中真正高频的是“现在去哪、下一项是什么、凭证在哪、如何导航、花了多少钱”，而不是完整的后台式管理界面。",
    mvp: [
      "按当天时间轴聚合下一行程点、预订凭证、天气、导航和快速记账，提供一键开启的“今日模式”。",
      "生成可提前下载的离线出行包，至少保留当天安排、订单号、确认码、证件摘要和紧急联系人；敏感信息默认脱敏。",
      "支持“完成当前地点→查看下一站→唤起导航→记一笔”的短链路，并保留进入完整模块的入口。",
    ],
    value: "把 TravelOS 从“出发前规划工具”延伸为“旅途中随身助手”，形成最高频的使用入口，也是后续订阅权益的自然组成。",
    metric: "今日模式打开率、行中行程周活跃率、凭证与导航调用次数、离线包下载及成功读取率。",
    base: "可直接复用 TripDay、TripPlace、Booking、Voucher、Weather、Expense 与地图能力，新增工作主要在聚合体验和本地缓存。",
  },
  {
    id: "F02",
    name: "订单与票据智能导入",
    priority: "P0",
    horizon: "0—3 个月",
    goal: "降低资料录入成本",
    effort: "中—大",
    dependency: "OCR 或邮件解析服务、AI 字段抽取与人工确认",
    problem: "当前预订、费用和附件已能联动，但初始信息仍主要依赖手工填写。录入成本会直接影响新行程是否达到“可用状态”。",
    mvp: [
      "先支持上传机票、酒店、火车和门票截图或 PDF，自动识别订单号、确认码、时间、地点、金额和乘客信息。",
      "识别结果进入待确认草稿，用户确认后写入预订；可选择同步关联行程天、生成凭证或转为费用，避免默认重复入账。",
      "第二阶段再增加授权邮箱或转发专属邮箱导入，不建议首版即读取用户整个邮箱。",
    ],
    value: "显著缩短从创建行程到资料完备的时间，提高新用户激活率，并为 AI 规划提供更可信的事实数据。",
    metric: "导入成功率、字段一次识别准确率、人工修改率、预订创建时长、导入后行程资料完备率。",
    base: "可沿用现有 AI 草稿确认机制、Booking 字段模型、附件存储和预订转费用逻辑；需增加解析服务及敏感信息保护。",
  },
  {
    id: "F03",
    name: "多人决策与任务中心",
    priority: "P0",
    horizon: "0—3 个月",
    goal: "强化多人协作闭环",
    effort: "中",
    dependency: "现有成员角色、通知、好友和编辑锁能力",
    problem: "多人行程已有角色、共同编辑、分摊和私信，但“去哪、住哪、谁负责什么”的决策过程仍可能散落在站外群聊中。",
    mvp: [
      "在行程内增加讨论、评论与 @成员，讨论对象可关联某天、地点、预订或费用。",
      "提供轻量投票：日期、酒店、餐厅和行程方案二选一；投票结束后可一键把结果写回行程草稿。",
      "增加任务分派与截止时间，例如“张三负责订酒店、李四确认签证”，与打包、预订和证件模块互相链接。",
    ],
    value: "让协作从“共同查看和记账”升级为“共同决策和执行”，提高邀请成员后的真实参与度与行程完成率。",
    metric: "多人行程占比、受邀成员周活跃率、投票完成率、任务按期完成率、讨论结果写回行程的比例。",
    base: "可复用 TripMember、Notification、AuditLog 与权限体系；需新增讨论、投票和任务实体，首版不做复杂即时群聊。",
  },
  {
    id: "F04",
    name: "多币种费用与汇率快照",
    priority: "P0",
    horizon: "0—3 个月",
    goal: "补齐出境游核心能力",
    effort: "中",
    dependency: "汇率数据源、历史口径与报表口径统一",
    problem: "当前费用支持币种字段，但统计主口径以 CNY 为主，外币多按原币单独展示，尚不能形成统一的跨币种预算、分摊与年度统计。",
    mvp: [
      "记账时选择币种并按消费日汇率自动折算，同时保留原币金额、汇率、折算金额和汇率来源时间。",
      "允许用户手工锁定汇率；历史报表始终使用交易快照，避免汇率刷新后旧账变化。",
      "统一预算、分类报表、成员分摊、结算与年度统计的币种口径，并提供原币与折算币切换。",
    ],
    value: "消除出境旅行的核心财务盲区，扩大产品适用场景，也是提高数据可信度和专业度的基础能力。",
    metric: "外币行程覆盖率、自动折算成功率、手工改汇率比例、跨币种报表使用率、币种口径相关纠错量。",
    base: "现有 currency 字段、预算和报表模块可复用；需要新增汇率快照并明确结算时采用交易日还是结算日口径。",
  },
  {
    id: "F05",
    name: "结算回款确认与催收",
    priority: "P0",
    horizon: "0—3 个月",
    goal: "完成多人财务闭环",
    effort: "中",
    dependency: "现有结算快照、成员通知与好友关系",
    problem: "现有系统能计算最少转账方案并确认结算快照，但“转账建议”不等于“实际已回款”，缺少付款方和收款方的状态确认。",
    mvp: [
      "为每笔建议转账增加待支付、已标记付款、已确认收款、争议四种状态，并保留双方操作记录。",
      "支持上传回款备注或凭证、一键复制收款信息、到期前温和提醒；首版不接真实支付通道。",
      "行程结束后生成结算进度，全部收款确认后才显示“账目已清”，支持成员对争议账目添加说明。",
    ],
    value: "把财务功能从计算工具变成可完成的事务流程，减少成员间反复对账，增强多人旅行的信任与留存。",
    metric: "结算方案确认率、平均回款确认周期、双方确认完成率、提醒后完成率、争议账目占比。",
    base: "可扩展 TripSettlement 快照和通知能力；需明确结算后新增费用时重新开账或生成补充批次的规则。",
  },
  {
    id: "F06",
    name: "动态行程优化与异常改排",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "提升 AI 的实际决策价值",
    effort: "大",
    dependency: "路线耗时、营业时间、天气与交通状态等外部数据",
    problem: "现有 AI 已能检索景点、餐厅和酒店并生成变更草稿，天气也能主动提醒；下一步应从“能生成”升级为“能发现冲突并给出可执行改排”。",
    mvp: [
      "自动检测时间重叠、跨点通勤不足、营业时间冲突、预算超限和单天安排过满。",
      "针对天气、景点关闭或航班变化提供一至三个改排方案，说明被移动的项目、额外耗时和费用影响。",
      "所有变更继续沿用用户确认后写入的机制，保留改排前后对比和一键撤销。",
    ],
    value: "形成区别于普通聊天助手的业务壁垒：AI 不只给建议，而是基于完整行程数据解决具体问题。",
    metric: "冲突检测命中率、改排方案采纳率、采纳后撤销率、异常提醒到行程修改的转化率。",
    base: "复用 AiToolRegistry、ItineraryDraftEngine、路线和天气提醒；外部数据不足时应明确“估算”而非保证实时准确。",
  },
  {
    id: "F07",
    name: "场景化模板与智能清单",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "提高新行程激活效率",
    effort: "中",
    dependency: "模板结构、用户偏好与历史行程数据",
    problem: "当前可复制作文行程和打包清单，也有固定打包模板，但尚未把一次成功旅行沉淀为可跨场景复用的完整“旅行方案”。",
    mvp: [
      "提供亲子、自驾、周末短途、出境、徒步等场景模板，一键生成行程天、打包清单、预算类别和待办任务。",
      "允许用户把已完成行程保存为私人模板，并选择是否排除订单、成员、证件等敏感内容。",
      "根据目的地、季节、同行人、天气和用户偏好调整清单，解释新增物品原因并允许批量取消。",
    ],
    value: "降低重复规划成本，提升第二次及后续旅行的启动速度，让历史数据成为留存资产。",
    metric: "模板创建使用率、新行程首个有效计划完成时间、模板复用次数、智能清单保留率。",
    base: "可复用行程复制、攻略复制、打包模板与 AI 偏好；需建立模板版本及脱敏规则。",
  },
  {
    id: "F08",
    name: "攻略互动与目的地专题",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "增强内容留存与回访",
    effort: "中—大",
    dependency: "内容审核、举报、反垃圾和可见性治理",
    problem: "攻略广场已有公开/好友可见、浏览、热度和一键复制，但互动仍以查看和复制为主，用户缺少收藏、讨论和持续关注作者的理由。",
    mvp: [
      "先增加攻略收藏、评论、点赞和作者主页，再按数据决定是否引入关注 feed。",
      "建设目的地专题页，聚合可复制的行程、游记、常见预算、季节建议和热门地点。",
      "公开评论上线前必须同时具备举报、屏蔽、删除、频率限制和管理员处置流程。",
    ],
    value: "把一次性攻略浏览转为可积累的内容关系，增加回访入口，并通过专题页提高攻略到实际建行程的转化。",
    metric: "攻略收藏率、评论互动率、专题页到复制行程转化率、公开内容发布率、举报与违规处置量。",
    base: "可扩展现有 Square、Journal、User 和 Admin 能力；不建议首版建设泛社交信息流。",
  },
  {
    id: "F09",
    name: "共享回忆与年度旅行报告",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "促进行后回访与自然传播",
    effort: "中",
    dependency: "照片聚合、隐私授权、分享页和模板生成",
    problem: "当前已有照片、AI 行程回顾和年度统计，但行后资产还可以进一步转化为成员共同维护和愿意分享的内容。",
    mvp: [
      "多人可向同一行程回忆册贡献照片与文字，按时间、地点或成员筛选，并支持共同挑选封面。",
      "自动生成年度旅行报告：目的地、足迹、同行人、花费区间、代表性照片和游记摘要，提供私密分享链接与有效期。",
      "支持生成适合保存或分享的长图/PDF，公开展示前由用户逐项确认敏感信息。",
    ],
    value: "增强旅行结束后的情感价值和成员回流，让分享成为低打扰的自然获客渠道，同时不破坏私人产品定位。",
    metric: "行后回忆册创建率、多人贡献率、年度报告诉达与分享率、分享页带来的有效注册或攻略复制。",
    base: "复用 Photo、TripRecap、TravelStats 和 PDF 导出；需新增细粒度分享授权与撤销机制。",
  },
  {
    id: "F10",
    name: "日历同步与多渠道触达",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "提高关键提醒到达率",
    effort: "中",
    dependency: "日历协议、推送渠道授权与频率治理",
    problem: "系统通知、SSE、预订和天气提醒已经形成基础，但用户离开网站后，关键提醒仍依赖主动回到通知中心。",
    mvp: [
      "支持把行程天、航班、酒店入住和免费取消截止导出或订阅到系统日历，变更后同步更新。",
      "在获得明确授权后增加浏览器推送或一种主流触达渠道，按紧急程度区分即时推送与每日摘要。",
      "提供按行程、类型和时段配置的免打扰规则，防止多渠道重复提醒。",
    ],
    value: "把高价值提醒嵌入用户已有日程和触达习惯，提高行前准备完成率及行中事项履约率。",
    metric: "日历订阅率、提醒到达率、通知点击率、免打扰与退订率、重复提醒投诉量。",
    base: "可复用 BookingReminderScheduler、WeatherAlertScheduler、Notification 和用户偏好；需先做统一去重与优先级。",
  },
  {
    id: "F11",
    name: "出境安全与应急助手",
    priority: "P1",
    horizon: "3—6 个月",
    goal: "建立高信任场景能力",
    effort: "中—大",
    dependency: "权威内容维护、紧急联系人与离线访问",
    problem: "证件到期和保单归档已具备基础，但出境用户还关心签证材料、入境要求、使领馆、医疗、失窃和证件丢失等低频高压力事项。",
    mvp: [
      "生成按目的地和出行日期整理的行前要求清单，所有规则注明来源、更新时间和需自行复核提示。",
      "提供私密应急卡：紧急联系人、保单号、过敏信息、使领馆与当地紧急电话，可纳入离线出行包。",
      "提供证件丢失、行程延误等场景化处置清单和官方求助入口，不做未经核验的法律或医疗结论。",
    ],
    value: "在高焦虑场景中提升产品不可替代性与信任，可与离线包、会员权益和出境模板形成组合。",
    metric: "出境行程应急卡启用率、行前清单完成率、过期资料补救率、应急内容查看后的有效反馈。",
    base: "复用 TravelDocument、Notification、离线和模板能力；最大成本在权威内容维护与责任边界，而非页面开发。",
  },
  {
    id: "F12",
    name: "下一程灵感与个性化推荐",
    priority: "P2",
    horizon: "6—12 个月",
    goal: "提升跨旅行周期留存",
    effort: "大",
    dependency: "足够的历史行为、偏好和内容供给",
    problem: "旅行是低频需求，行程结束后用户可能长期不回访。现有偏好和历史统计已具备数据基础，可在内容供给成熟后承接下一次旅行。",
    mvp: [
      "根据历史目的地、预算、出行月份、同行人和偏好推荐“下一程”主题，而非泛化热门榜单。",
      "推荐理由需可解释，例如“你曾在春季选择短途自然目的地”，并允许关闭某类推荐或清除偏好。",
      "推荐结果可直接生成私人行程草稿，先规划后决定是否公开。",
    ],
    value: "连接行后与下一次行前，改善旅行产品的周期性流失，并提高攻略内容到新行程的转化。",
    metric: "推荐点击率、推荐到新建行程转化率、推荐关闭率、下一程创建留存率。",
    base: "复用 AI 偏好、历史行程、攻略专题和行程草稿；应先验证推荐质量，不宜过早引入复杂算法。",
  },
  {
    id: "F13",
    name: "预订比价与导流变现",
    priority: "P2",
    horizon: "6—12 个月",
    goal: "验证交易相关收入",
    effort: "大",
    dependency: "供应商合作、价格数据、合规披露与售后边界",
    problem: "平台已经沉淀明确的目的地、日期、人数、预算和偏好，具备高意图场景，但当前预订模块是登记工具，不产生交易收入。",
    mvp: [
      "从酒店、门票或当地玩乐选择一个品类试点，在现有行程点旁提供比价或跳转合作方的入口。",
      "明确标注价格更新时间、供应商、佣金关系和退改责任，成交与售后由合作方完成。",
      "先验证外链导流，不在首版自建支付、订单履约、客服和退款体系。",
    ],
    value: "以较轻模式验证佣金收入，避免过早变成重运营 OTA；成功后再评估更深 API 集成。",
    metric: "比价入口点击率、有效导流率、合作方归因成交率、每活跃行程佣金、退改相关投诉量。",
    base: "复用行程上下文、POI 检索和 Booking 登记；业务合作与数据授权是前置条件，不能用不可靠价格诱导点击。",
  },
  {
    id: "F14",
    name: "会员订阅与家庭/团队空间",
    priority: "P2",
    horizon: "6—12 个月",
    goal: "形成可持续订阅收入",
    effort: "中—大",
    dependency: "付费权益验证、计费、配额和退款规则",
    problem: "当核心使用频率和协作价值被验证后，可将高成本或高价值能力产品化，而不是简单对基础行程和记账功能收费。",
    mvp: [
      "免费版保留完整核心闭环；会员重点提供更高 AI 额度、更大存储、离线包、年度报告和高级模板。",
      "家庭或小团队空间提供共享偏好、成员管理、共同模板和统一结算视图，权限仍围绕 Trip 隔离。",
      "先以小范围邀请制验证付费意愿，再接入自动续费、发票、退款与配额提醒。",
    ],
    value: "让收入与用户获得的高级价值匹配，降低广告对私人旅行空间的干扰，并覆盖 AI、存储和触达成本。",
    metric: "权益试用率、试用转付费率、续费率、退款率、AI 与存储成本覆盖率、家庭空间活跃成员数。",
    base: "可基于现有账户、成员、偏好和系统设置扩展；不建议在核心价值尚未验证前设置过多付费墙。",
  },
];

const tocEntries = [
  { title: "一、执行摘要", level: 1, page: 3 },
  { title: "二、项目现状与业务判断", level: 1, page: 4 },
  { title: "三、功能组合与优先级", level: 1, page: 6 },
  { title: "四、功能建议详述", level: 1, page: 8 },
  ...features.map((f, index) => ({ title: `${f.id} ${f.name}`, level: 2, page: 8 + Math.floor(index / 2) })),
  { title: "五、分阶段实施路线", level: 1, page: 16 },
  { title: "六、指标体系与实验建议", level: 1, page: 17 },
  { title: "七、商业化路径与业务边界", level: 1, page: 18 },
  { title: "八、风险、依赖与最终建议", level: 1, page: 19 },
];

const children = [];
// Cover
children.push(para(run("TRAVEL OS  ·  BUSINESS ROADMAP", {
  bold: true,
  size: 19,
  font,
  color: palette.primary,
  characterSpacing: 120,
}), { spacing: { before: 1500, after: 260 } }));
children.push(para(run(title, { bold: true, size: 46, font, color: palette.dark }), {
  heading: HeadingLevel.TITLE,
  spacing: { after: 220, line: 560 },
}));
children.push(para(run("从完整旅行管理工具，走向高频率协作与可持续增长", {
  size: 25,
  font,
  color: palette.accent,
}), { spacing: { after: 500 } }));
children.push(para(run("基于现有代码、产品资料与业务流程的增量功能评估", {
  size: 22,
  color: palette.muted,
}), { spacing: { after: 1000 } }));
children.push(makeTable([2200, 6800], ["项目", "说明"], [
  ["文档版本", "V1.0"],
  ["评估日期", "2026 年 10 月 29 日"],
  ["评估范围", "行前规划、行中执行、多人协作、内容社区、数据资产与商业化"],
  ["优先级口径", "P0＝近期核心闭环；P1＝中期增长与差异化；P2＝远期推荐与变现"],
], { boldFirst: true }));
children.push(para(run("说明：本建议基于项目静态资料与代码审阅，不代表已验证的用户需求或收入预测。优先级应在补齐真实使用数据、用户访谈和合作资源评估后复核。", {
  size: 18,
  color: palette.muted,
  italics: true,
}), { spacing: { before: 450, after: 100, line: 270 } }));
children.push(pageBreak());

// TOC
children.push(para(run("目录", { bold: true, size: 32, font, color: palette.dark }), {
  spacing: { before: 120, after: 180 },
}));
children.push(plain("目录页码可在 Word/WPS 中右键“更新域”刷新。", {
  alignment: AlignmentType.LEFT,
}));
children.push(toc(tocEntries));
children.push(pageBreak());

// 1 Executive summary
children.push(h1("一、执行摘要"));
children.push(p("TravelOS 已具备较完整的旅行生命周期能力：行程、地图、费用、分摊结算、打包、预订、凭证、证件、照片、游记、统计、通知、AI 助手、好友私信和攻略复制均有实际模块支撑。当前最值得做的不是继续横向增加孤立页面，而是把现有数据串成更高频、更少手工录入、更能完成多人协作的业务闭环。"));
children.push(p("建议保持“私人、长期、多人协作的旅行管理平台”定位。近期优先强化行中入口、订单导入、多人决策、多币种和真实回款；中期通过动态改排、模板、攻略互动、共享回忆与日历触达提高差异化；后期再验证个性化推荐、预订导流和会员订阅。"));
children.push(makeTable([1300, 3000, 4700], ["优先动作", "建议", "业务理由"], [
  ["第一优先", "今日模式与离线出行包", "把使用场景从规划期延伸到旅行现场，建立高频入口。"],
  ["第二优先", "订单与票据智能导入", "减少手工录入，缩短新行程达到可用状态的时间。"],
  ["第三优先", "多人决策与任务中心", "把站外群聊中的决策和执行拉回行程，提升协作价值。"],
  ["基础补齐", "多币种与结算回款闭环", "覆盖出境游并完成多人财务的最后一公里。"],
  ["后置投入", "重交易 OTA、复杂会员体系", "先验证使用频率和付费意愿，避免高成本过早投入。"],
], { boldFirst: true }));
children.push(note("核心建议：先用 F01＋F02 打造“行中高频＋低录入成本”的双入口，再以 F03—F05 补齐多人与出境闭环；增长和商业化功能必须以真实使用数据为启动条件。"));

// 2 Current state
children.push(h1("二、项目现状与业务判断"));
children.push(h2("2.1 已有业务资产"));
children.push(makeTable([1800, 4500, 2700], ["业务域", "已具备能力", "可复用价值"], [
  ["行程与地图", "行程状态、逐日地点、排序、坐标、真实驾车路线、天气", "支撑行中聚合与动态改排"],
  ["协作与财务", "四种角色、共同编辑、费用分摊、最少转账方案、结算快照", "支撑任务协作与回款闭环"],
  ["预订与资料", "五类预订、提醒、凭证、证件、附件、预订转费用", "支撑订单智能导入与离线包"],
  ["内容与回忆", "照片、游记、AI 行程回顾、年度统计、足迹", "支撑共享回忆与个性化推荐"],
  ["社交与增长", "好友、私信、公开/好友可见、攻略广场、一键复制", "支撑互动、专题和自然传播"],
  ["AI 与通知", "多轮工具调用、持久偏好、变更草稿、SSE、预订/天气提醒", "支撑可解释的自动化与主动服务"],
], { boldFirst: true }));
children.push(h2("2.2 主要业务缺口"));
children.push(bullet("行中场景缺少统一入口：功能完整但分散，现场使用链路仍偏长。"));
children.push(bullet("资料录入成本偏高：预订和票据数据已有结构，但输入仍主要依赖人工。"));
children.push(bullet("多人协作尚未覆盖决策与执行：讨论、投票、任务和实际回款仍可进一步完善。"));
children.push(bullet("出境财务口径不完整：外币已能记录，但统一折算、预算和结算仍需汇率快照。"));
children.push(bullet("内容与行后资产可继续转化：攻略互动、目的地专题和共享回忆仍有增长空间。"));
children.push(bullet("商业化尚未形成：应在核心留存验证后，再选择轻导流或订阅，而不是同时铺开。"));
children.push(h2("2.3 战略取舍"));
children.push(p("建议采用“核心闭环优先、内容增长其次、交易变现最后”的顺序。TravelOS 的优势在于同一 Trip 下沉淀了规划、成员、订单、费用、照片和内容，不需要与综合 OTA 正面比较票源和交易规模。真正的差异化是让这些私有数据在旅途中持续产生价值，并在用户授权后转化为模板、回忆和下一程灵感。"));
children.push(note("优先级说明：P0/P1/P2 是业务先后关系，不等于开发工期承诺；“工作量”仅为相对量级。由于未获得真实用户量、留存、收入与成本数据，本文不编造 ROI 或增长率目标。"));

// 3 Matrix
children.push(h1("三、功能组合与优先级"));
children.push(makeTable([650, 2350, 750, 1350, 900, 3000], ["编号", "功能", "优先级", "建议窗口", "工作量", "关键依赖"], features.map((f) => [
  f.id, f.name, f.priority, f.horizon, f.effort, f.dependency,
]), { boldFirst: true, centerColumns: [0, 2, 3, 4] }));
children.push(tableCaption("表 1：建议功能组合。窗口用于排序，需按团队容量和验证结果滚动调整。"));
children.push(h2("3.1 优先级判定逻辑"));
children.push(makeTable([2100, 3900, 3000], ["维度", "判断问题", "对排序的影响"], [
  ["核心闭环", "是否减少旅行前中后的关键断点？", "断点越明确，越优先。"],
  ["使用频率", "是否能带来行中或行后再次使用？", "高频入口优先于低频装饰功能。"],
  ["数据复用", "是否复用现有 Trip 与成员数据？", "复用越高，越能形成差异化。"],
  ["验证成本", "能否以小范围 MVP 验证价值？", "可先轻量验证的重投入应后置。"],
  ["外部依赖", "是否依赖供应商、内容维护或合规？", "依赖越重，越需设置启动条件。"],
], { boldFirst: true }));

// 4 Detailed features
children.push(h1("四、功能建议详述"));
for (const f of features) {
  children.push(h2(`${f.id} ${f.name}（${f.priority}）`));
  children.push(labelPara("业务问题：", f.problem));
  children.push(labelPara("建议 MVP：", ""));
  for (const item of f.mvp) children.push(bullet(item, `list-${f.id}`));
  children.push(labelPara("业务价值：", f.value));
  children.push(labelPara("验证指标：", f.metric));
  children.push(labelPara("现有基础与边界：", f.base));
}

// 5 Roadmap
children.push(h1("五、分阶段实施路线"));
children.push(makeTable([1400, 2500, 3100, 2000], ["阶段", "业务目标", "建议功能", "阶段出口条件"], [
  ["第一阶段\n0—3 个月", "提高激活与行中使用，补齐多人/出境闭环", "F01、F02、F03、F04、F05；同步建立事件埋点与基线", "核心链路可用，获得真实打开率、导入成功率、协作完成率和回款周期"],
  ["第二阶段\n3—6 个月", "形成差异化并增加回访理由", "F06、F07、F08、F09、F10；出境用户占比较高时加入 F11", "改排/模板被持续使用，内容与分享带来有效回访，提醒不产生明显打扰"],
  ["第三阶段\n6—12 个月", "验证跨周期留存与商业化", "F12、F13、F14；按数据选择先导流或先订阅", "推荐能转化为新行程，商业试验覆盖直接成本且不伤害核心体验"],
], { boldFirst: true, centerColumns: [0] }));
children.push(h2("5.1 第一阶段推荐顺序"));
children.push(bullet("第 1 个增量：F01 今日模式。先聚合现有数据，不重写各业务模块，最快验证行中入口价值。"));
children.push(bullet("第 2 个增量：F02 智能导入。与今日模式共享更完整的预订数据，形成“导入越容易—行中越好用”的正循环。"));
children.push(bullet("随后按用户结构选择：多人行程优先 F03/F05；出境行程优先 F04，避免所有功能同时铺开。"));
children.push(h2("5.2 每个阶段的评审门槛"));
children.push(p("每个功能进入下一阶段前，应至少回答三个问题：目标用户是否真实使用、是否改善既定业务指标、外部成本是否可持续。若仅有点击而无不重复的核心行为，不应继续扩大开发；若外部数据质量不稳定，应保留人工确认和降级路径。"));

// 6 Metrics
children.push(h1("六、指标体系与实验建议"));
children.push(h2("6.1 建议北极星指标"));
children.push(p("建议使用“月度有效活跃行程数”：当月内至少发生一次有效行为的去重 Trip 数。有效行为包括完成行程规划、确认订单导入、查看行中凭证、记账、完成协作任务、上传回忆或复制攻略等。相比单纯 MAU，该指标更贴合旅行的周期性，也能避免用户只打开首页却被计为活跃。"));
children.push(makeTable([1900, 3600, 3500], ["指标层", "建议指标", "用途"], [
  ["激活", "新行程 7 日内核心资料完备率、首次有效规划耗时", "判断录入与模板是否降低启动成本"],
  ["行中使用", "今日模式打开率、行中周活跃行程率、凭证/导航使用率", "判断产品是否进入旅行现场"],
  ["协作", "受邀成员活跃率、任务完成率、回款确认周期", "判断多人闭环是否真正完成"],
  ["内容增长", "公开内容发布率、攻略复制率、分享后有效访问/注册", "判断内容是否带来实际业务行为"],
  ["跨周期留存", "下一程创建率、历史模板复用率、年度报告回流率", "判断行后资产是否带来下一次旅行"],
  ["商业化", "导流成交归因、试用转付费、续费率、直接成本覆盖率", "判断收入是否可持续且不损害体验"],
], { boldFirst: true }));
children.push(h2("6.2 上线前必须补齐的测量"));
children.push(bullet("统一记录 Trip、成员、来源页面、功能版本和结果状态，避免只统计按钮点击。"));
children.push(bullet("为 AI 导入和动态改排记录“生成—确认—修改—撤销”完整链路，以采纳质量而非生成次数评估。"));
children.push(bullet("为攻略分享区分普通访问、有效注册、复制行程和后续活跃，避免把曝光当增长。"));
children.push(bullet("先采集 2—4 周基线，再设目标值；不同用户群按单人/多人、国内/出境、新/老用户分层。"));

// 7 Monetization
children.push(h1("七、商业化路径与业务边界"));
children.push(h2("7.1 推荐商业化顺序"));
children.push(makeTable([1700, 3900, 3400], ["路径", "适合售卖的价值", "启动前提"], [
  ["轻量导流", "基于明确行程意图的酒店、门票或当地玩乐跳转", "有稳定活跃行程和供应商归因，价格与售后披露清晰"],
  ["会员订阅", "AI 高额度、存储、离线包、高级模板、年度报告和团队管理", "高级能力被高频使用，免费核心闭环仍完整"],
  ["家庭/团队版", "共享偏好、模板、成员与统一协作管理", "多人协作留存成立，且有明确管理需求"],
], { boldFirst: true }));
children.push(p("不建议在私人旅行工作台中过早加入泛广告，也不建议同时建设支付、履约、退款、客服和供应商体系。F13 应以合作方完成交易的方式试点；F14 应优先包装已经验证的高价值能力，而非把基础记账、凭证或隐私控制设为付费墙。"));
children.push(h2("7.2 暂不建议优先投入的功能"));
children.push(bullet("全流程自建 OTA：供应链、支付、退改和客服成本远高于现阶段可验证收益。"));
children.push(bullet("泛社交信息流：容易偏离旅行主任务，并显著增加内容治理成本。"));
children.push(bullet("复杂积分、等级与打卡体系：在未证明核心留存前，容易制造无效活跃。"));
children.push(bullet("一开始就开发完整原生 App：可先用响应式页面、今日模式和离线包验证移动场景，再决定载体。"));

// 8 Risks and conclusion
children.push(h1("八、风险、依赖与最终建议"));
children.push(makeTable([2000, 3900, 3100], ["风险/依赖", "可能影响", "控制建议"], [
  ["隐私与数据权限", "证件、订单、照片、位置和成员数据高度敏感", "最小化采集、脱敏展示、显式授权、可撤销分享、离线数据加密与自动过期"],
  ["AI 与外部数据准确性", "识别错误、价格过期、路线或规则变化会损害信任", "保留来源与更新时间、人工确认、置信度提示和手工修正，不作绝对保证"],
  ["通知打扰", "多渠道叠加可能导致退订和流失", "统一优先级、去重、免打扰与每日摘要，监控负面指标"],
  ["公开内容治理", "评论和作者主页扩大违规、垃圾与隐私风险", "互动上线前完成举报、审核、频率限制、屏蔽和后台处置"],
  ["商业合作与售后", "导流可能带来价格争议和信任损耗", "明确供应商责任、佣金关系、价格时效与客服入口"],
  ["成本失控", "AI、OCR、存储和推送随使用增长产生持续成本", "设置配额、缓存、分层权益与单位成本监控"],
], { boldFirst: true }));
children.push(h2("8.1 最终建议"));
children.push(p("TravelOS 下一阶段应从“功能数量”转向“核心行为完成率”。最具业务价值的组合是：以 F01 建立行中高频入口，以 F02 降低数据进入成本，以 F03/F05 完成多人协作事务，以 F04 扩展出境场景。待这些能力产生可观测的使用与留存改善后，再投入攻略互动、动态改排和个性化推荐，并以轻量导流或会员订阅验证商业化。"));
children.push(p("若只选择两个功能先行，建议选择“今日模式与离线出行包”和“订单与票据智能导入”：前者提升使用频率，后者提升数据完备度，二者共享现有 Trip 数据并能相互放大价值。若当前真实用户以多人或出境为主，则应分别把 F03/F05 或 F04 提前。"));
children.push(note("评审建议：先确认目标细分人群与北极星指标，再将 F01、F02 拆成可验收的 MVP；其余功能按真实数据滚动排序，而不是一次性纳入固定年度开发清单。"));

children.push(h2("附录：本次分析依据"));
children.push(plain("项目根 README、产品愿景与路线图、项目功能说明、接口接入清单，以及行程、攻略、费用、统计、预订、好友、AI、通知、打包和首页等相关服务端与前端实现。分析未包含真实生产数据、用户访谈、财务数据或第三方供应商可行性验证。"));

const doc = new Document({
  creator: "TravelOS 业务功能评估",
  title,
  description: "TravelOS 业务增量功能、优先级、实施路线与指标体系建议",
  features: { updateFields: true },
  numbering: {
    config: [
      {
        reference: "bullets",
        levels: [{
          level: 0,
          format: LevelFormat.BULLET,
          text: "•",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 420, hanging: 240 } } },
        }],
      },
      ...features.map((f) => ({
        reference: `list-${f.id}`,
        levels: [{
          level: 0,
          format: LevelFormat.BULLET,
          text: "•",
          alignment: AlignmentType.LEFT,
          style: { paragraph: { indent: { left: 420, hanging: 240 } } },
        }],
      })),
    ],
  },
  styles: {
    default: {
      document: { run: { font: bodyFont, size: 21, color: palette.text } },
    },
    paragraphStyles: [
      {
        id: "TOC1",
        name: "toc 1",
        basedOn: "Normal",
        next: "Normal",
        run: { font: bodyFont, size: 20, color: palette.text },
        paragraph: { spacing: { after: 90, line: 270 } },
      },
      {
        id: "TOC2",
        name: "toc 2",
        basedOn: "Normal",
        next: "Normal",
        run: { font: bodyFont, size: 18, color: palette.muted },
        paragraph: { spacing: { after: 60, line: 250 } },
      },
    ],
  },
  sections: [{
    properties: {
      titlePg: true,
      page: {
        margin: { top: 1200, bottom: 1200, left: 1450, right: 1450 },
      },
    },
    headers: {
      first: new Header({ children: [plain("")] }),
      default: new Header({
        children: [para(run(title, { bold: true, size: 17, font, color: palette.primary }), {
          alignment: AlignmentType.RIGHT,
          spacing: { after: 60 },
          border: { bottom: { style: "single", color: palette.border, size: 4, space: 4 } },
        })],
      }),
    },
    footers: {
      first: new Footer({ children: [plain("")] }),
      default: new Footer({
        children: [para(new TextRun({
          children: [PageNumber.CURRENT],
          font,
          size: 18,
          color: palette.muted,
        }), { alignment: AlignmentType.CENTER, spacing: { before: 60 } })],
      }),
    },
    children,
  }],
});

fs.writeFileSync(outputPath, await Packer.toBuffer(doc));
