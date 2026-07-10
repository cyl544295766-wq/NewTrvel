export type JournalMood = 'happy' | 'excited' | 'tired' | 'sad' | 'relaxed';

export const journalMoodLabels: Record<JournalMood, string> = {
  happy: '开心',
  excited: '兴奋',
  tired: '疲惫',
  sad: '低落',
  relaxed: '放松',
};

export const journalMoods = Object.keys(journalMoodLabels) as JournalMood[];

export type JournalPhoto = {
  id: string;
  url: string;
  caption: string | null;
  orderIndex: number;
};

export type TripJournal = {
  id: string;
  tripId: string;
  tripDayId: string | null;
  tripPlaceId: string | null;
  title: string;
  content: string;
  isDraft: boolean;
  mood: JournalMood | null;
  createdAt: string;
  updatedAt: string;
  tripDay: { id: string; dayIndex: number; date: string } | null;
  tripPlace: { id: string; name: string } | null;
  photos: JournalPhoto[];
};

export type TripJournalInput = {
  title: string;
  content: string;
  tripDayId?: string;
  tripPlaceId?: string;
  mood?: JournalMood;
  isDraft?: boolean;
  photoIds?: string[];
};

export type TripJournalUpdateInput = {
  title?: string;
  content?: string;
  tripDayId?: string | null;
  tripPlaceId?: string | null;
  mood?: JournalMood | null;
  isDraft?: boolean;
  photoIds?: string[];
};
