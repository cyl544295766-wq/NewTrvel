import { TripExpenseCategory } from '@prisma/client';
import {
  Content,
  TableCell,
  TDocumentDefinitions,
} from 'pdfmake/interfaces';
import { TripPdfData } from './trip-pdf.repository';

export type PreparedPdfPhoto = {
  id: string;
  image: string;
  caption: string | null;
  date: Date;
  placeName: string | null;
};

const categoryLabels: Record<TripExpenseCategory, string> = {
  transport: '交通',
  hotel: '住宿',
  food: '餐饮',
  ticket: '门票',
  parking: '停车',
  shopping: '购物',
  maintenance: '维修',
  activity: '活动',
  other: '其他',
};

export function buildTripPdfDocument(
  trip: TripPdfData,
  coverImage: string | null,
  photos: PreparedPdfPhoto[],
): TDocumentDefinitions {
  const content: Content[] = [
    ...buildCoverPage(trip, coverImage),
    ...trip.days.flatMap((day) => buildDayPage(day)),
    ...buildExpensePage(trip),
    ...buildPhotoPages(photos),
  ];

  return {
    pageSize: 'A4',
    pageMargins: [48, 54, 48, 54],
    defaultStyle: { font: 'NotoSansSC', fontSize: 10, color: '#263029', lineHeight: 1.35 },
    footer: (currentPage, pageCount) => ({
      columns: [
        { text: 'Travel OS', color: '#6c756d', fontSize: 8 },
        { text: `${currentPage} / ${pageCount}`, alignment: 'right', color: '#6c756d', fontSize: 8 },
      ],
      margin: [48, 16, 48, 0],
    }),
    content,
    styles: {
      pageEyebrow: { fontSize: 9, bold: true, color: '#356046', margin: [0, 0, 0, 8] },
      pageTitle: { fontSize: 25, bold: true, color: '#17251c', margin: [0, 0, 0, 10] },
      sectionTitle: { fontSize: 15, bold: true, color: '#17251c', margin: [0, 18, 0, 8] },
      muted: { color: '#687068', fontSize: 9 },
      tableHeader: { bold: true, color: '#ffffff', fillColor: '#315f45', margin: [4, 5, 4, 5] },
      tableCell: { margin: [4, 5, 4, 5] },
    },
  };
}

function buildCoverPage(trip: TripPdfData, coverImage: string | null): Content[] {
  const page: Content[] = [
    { text: 'TRAVEL OS · 行程手册', style: 'pageEyebrow', margin: [0, 36, 0, 12] },
    { text: trip.title, fontSize: 34, bold: true, color: '#17251c', margin: [0, 0, 0, 12] },
    { text: trip.destination || '目的地未设置', fontSize: 17, color: '#45604e' },
    { text: formatDateRange(trip.startDate, trip.endDate), fontSize: 12, color: '#687068', margin: [0, 8, 0, 0] },
  ];

  if (coverImage) {
    page.push({ image: coverImage, fit: [499, 300], alignment: 'center', margin: [0, 38, 0, 0] });
  } else if (trip.description) {
    page.push({ text: trip.description, fontSize: 12, color: '#4f5b52', margin: [0, 48, 0, 0] });
  }

  page.push(
    {
      text: '由 Travel OS 生成',
      fontSize: 9,
      color: '#687068',
      absolutePosition: { x: 48, y: 770 },
    },
    { text: '', pageBreak: 'after' },
  );
  return page;
}

function buildDayPage(day: TripPdfData['days'][number]): Content[] {
  const places: Content[] = day.places.length
    ? day.places.map((place, index) => ({
        table: {
          widths: [76, '*'],
          body: [
            [
              {
                text: formatTimeRange(place.startTime, place.endTime) || `${index + 1}`.padStart(2, '0'),
                bold: true,
                color: '#315f45',
                margin: [6, 8, 6, 8],
              },
              {
                stack: [
                  { text: place.name, bold: true, fontSize: 12 },
                  ...(place.address ? [{ text: place.address, style: 'muted', margin: [0, 3, 0, 0] }] : []),
                  ...(place.notes ? [{ text: place.notes, margin: [0, 5, 0, 0] }] : []),
                ],
                margin: [6, 8, 6, 8],
              },
            ],
          ],
        },
        layout: {
          hLineColor: () => '#dce3dc',
          vLineColor: () => '#dce3dc',
          fillColor: () => (index % 2 === 0 ? '#f5f7f3' : '#ffffff'),
        },
        margin: [0, 0, 0, 10],
      }))
    : [{ text: '当天暂未安排地点。', color: '#687068', margin: [0, 20, 0, 0] }];

  return [
    { text: `第 ${day.dayIndex} 天`, style: 'pageEyebrow' },
    { text: day.title || formatDate(day.date), style: 'pageTitle' },
    { text: formatDate(day.date), color: '#687068', margin: [0, 0, 0, 12] },
    ...(day.summary ? [{ text: day.summary, margin: [0, 0, 0, 18] } as Content] : []),
    ...places,
    { text: '', pageBreak: 'after' },
  ];
}

function buildExpensePage(trip: TripPdfData): Content[] {
  const totals = sumByCurrency(trip.expenses);
  const categories = new Map<string, number>();
  for (const expense of trip.expenses) {
    const key = `${categoryLabels[expense.category]}|${expense.currency}`;
    categories.set(key, (categories.get(key) ?? 0) + Number(expense.amount));
  }

  const body: TableCell[][] = [
    [
      { text: '分类', style: 'tableHeader' },
      { text: '币种', style: 'tableHeader' },
      { text: '金额', style: 'tableHeader', alignment: 'right' },
    ],
    ...Array.from(categories.entries()).map(([key, amount]) => {
      const [category, currency] = key.split('|');
      return [
        { text: category, style: 'tableCell' },
        { text: currency, style: 'tableCell' },
        { text: amount.toFixed(2), style: 'tableCell', alignment: 'right' as const },
      ];
    }),
  ];

  if (body.length === 1) {
    body.push([{ text: '暂无费用记录', colSpan: 3, alignment: 'center', margin: [0, 18, 0, 18] }, {}, {}]);
  }

  return [
    { text: '费用汇总', style: 'pageEyebrow' },
    { text: '旅行花费', style: 'pageTitle' },
    { text: '总花费', style: 'sectionTitle' },
    { text: totals || '暂无费用记录', fontSize: 22, bold: true, color: '#315f45', margin: [0, 0, 0, 22] },
    { text: '分类统计', style: 'sectionTitle' },
    { table: { headerRows: 1, widths: ['*', 80, 100], body }, layout: 'lightHorizontalLines' },
    { text: '', pageBreak: 'after' },
  ];
}

function buildPhotoPages(photos: PreparedPdfPhoto[]): Content[] {
  if (photos.length === 0) {
    return [
      { text: '照片精选', style: 'pageEyebrow' },
      { text: '旅行影像', style: 'pageTitle' },
      { text: '暂无照片。', color: '#687068', margin: [0, 20, 0, 0] },
    ];
  }

  const groups = new Map<string, PreparedPdfPhoto[]>();
  for (const photo of photos) {
    const key = formatDate(photo.date);
    groups.set(key, [...(groups.get(key) ?? []), photo]);
  }

  return Array.from(groups.entries()).flatMap(([date, group], groupIndex, allGroups) => {
    const rows: TableCell[][] = [];
    for (let index = 0; index < group.length; index += 3) {
      const cells = group.slice(index, index + 3).map((photo) => ({
        stack: [
          { image: photo.image, fit: [150, 105], alignment: 'center' as const },
          {
            text: photo.caption || photo.placeName || '旅行照片',
            fontSize: 8,
            color: '#687068',
            margin: [0, 5, 0, 0],
          },
        ],
        margin: [4, 4, 4, 10],
      }));
      while (cells.length < 3) cells.push({ text: '' } as never);
      rows.push(cells);
    }

    return [
      { text: '照片精选', style: 'pageEyebrow' } as Content,
      { text: date, style: 'pageTitle' } as Content,
      { table: { widths: ['*', '*', '*'], body: rows }, layout: 'noBorders' } as Content,
      ...(groupIndex < allGroups.length - 1 ? [{ text: '', pageBreak: 'after' } as Content] : []),
    ];
  });
}

function sumByCurrency(expenses: TripPdfData['expenses']) {
  const totals = new Map<string, number>();
  for (const expense of expenses) {
    totals.set(expense.currency, (totals.get(expense.currency) ?? 0) + Number(expense.amount));
  }
  return Array.from(totals.entries())
    .map(([currency, amount]) => `${currency} ${amount.toFixed(2)}`)
    .join(' / ');
}

function formatDateRange(startDate: Date | null, endDate: Date | null) {
  if (!startDate && !endDate) return '日期未设置';
  return [startDate ? formatDate(startDate) : null, endDate ? formatDate(endDate) : null]
    .filter(Boolean)
    .join(' 至 ');
}

function formatDate(value: Date) {
  return value.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
}

function formatTimeRange(startTime: Date | null, endTime: Date | null) {
  return [formatTime(startTime), formatTime(endTime)].filter(Boolean).join(' - ');
}

function formatTime(value: Date | null) {
  return value?.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
}
