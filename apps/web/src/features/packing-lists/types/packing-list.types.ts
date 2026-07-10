export type PackingListCategory =
  'clothing' | 'toiletries' | 'electronics' | 'medicine' | 'documents' | 'other';

export const packingListCategoryLabels: Record<PackingListCategory, string> = {
  clothing: '衣物',
  toiletries: '洗漱',
  electronics: '电子',
  medicine: '药品',
  documents: '证件',
  other: '其他',
};

export const packingListCategories = Object.keys(
  packingListCategoryLabels,
) as PackingListCategory[];

export type PackingItem = {
  id: string;
  packingListId: string;
  name: string;
  quantity: number;
  isPacked: boolean;
  notes: string | null;
  orderIndex: number;
};

export type PackingList = {
  id: string;
  tripId: string;
  name: string;
  category: PackingListCategory;
  packedCount: number;
  itemCount: number;
  createdAt: string;
  updatedAt: string;
  items: PackingItem[];
};

export type PackingListInput = {
  name: string;
  category: PackingListCategory;
};

export type PackingItemInput = {
  name: string;
  quantity?: number;
  notes?: string;
  orderIndex?: number;
};

export type PackingItemUpdateInput = Partial<PackingItemInput> & {
  isPacked?: boolean;
  notes?: string | null;
};
