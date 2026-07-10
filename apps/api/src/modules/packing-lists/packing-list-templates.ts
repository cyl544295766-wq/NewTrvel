import { PackingListCategory } from '@prisma/client';

type TemplateItem = { name: string; quantity: number; notes?: string };

export const defaultPackingTemplates: Record<PackingListCategory, TemplateItem[]> = {
  clothing: [
    { name: '上衣', quantity: 3 },
    { name: '裤子', quantity: 2 },
    { name: '内衣', quantity: 4 },
    { name: '袜子', quantity: 4 },
  ],
  toiletries: [
    { name: '牙刷', quantity: 1 },
    { name: '牙膏', quantity: 1 },
    { name: '洗面奶', quantity: 1 },
    { name: '毛巾', quantity: 1 },
  ],
  electronics: [
    { name: '手机充电器', quantity: 1 },
    { name: '充电宝', quantity: 1 },
    { name: '转换插头', quantity: 1 },
  ],
  medicine: [
    { name: '常用药', quantity: 1 },
    { name: '创可贴', quantity: 5 },
  ],
  documents: [
    { name: '身份证件', quantity: 1 },
    { name: '行程单', quantity: 1 },
  ],
  other: [
    { name: '雨伞', quantity: 1 },
    { name: '水杯', quantity: 1 },
  ],
};
