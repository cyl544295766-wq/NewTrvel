import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreateTripJournalDto } from './dto/create-trip-journal.dto';
import { UpdateTripJournalDto } from './dto/update-trip-journal.dto';
import { TripJournalsRepository } from './trip-journals.repository';

const journalMoods = new Set(['happy', 'excited', 'tired', 'sad', 'relaxed']);

@Injectable()
export class TripJournalsService {
  constructor(
    private readonly tripJournalsRepository: TripJournalsRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async findAll(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const journals = await this.tripJournalsRepository.findJournalsByTrip(tripId);
    return { journals: journals.map((journal) => this.toJournalResponse(journal)) };
  }

  async findOne(tripId: string, journalId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const journal = await this.requireJournal(tripId, journalId);
    return { journal: this.toJournalResponse(journal) };
  }

  async create(tripId: string, userId: string, dto: CreateTripJournalDto) {
    await this.requireTripMember(tripId, userId);
    await this.validateRelations(tripId, dto.tripDayId, dto.tripPlaceId);
    const photoIds = await this.validatePhotos(tripId, dto.photoIds ?? []);
    const journal = await this.tripJournalsRepository.createJournal(
      {
        trip: { connect: { id: tripId } },
        tripDay: dto.tripDayId ? { connect: { id: dto.tripDayId } } : undefined,
        tripPlace: dto.tripPlaceId ? { connect: { id: dto.tripPlaceId } } : undefined,
        title: this.normalizeTitle(dto.title),
        content: this.validateContent(dto.content),
        mood: this.validateMood(dto.mood),
        isDraft: dto.isDraft ?? true,
      },
      photoIds,
    );
    return { journal: this.toJournalResponse(journal) };
  }

  async update(tripId: string, journalId: string, userId: string, dto: UpdateTripJournalDto) {
    await this.requireTripMember(tripId, userId);
    await this.requireJournal(tripId, journalId);
    await this.validateRelations(
      tripId,
      dto.tripDayId === null ? undefined : dto.tripDayId,
      dto.tripPlaceId === null ? undefined : dto.tripPlaceId,
    );
    const photoIds =
      dto.photoIds === undefined ? undefined : await this.validatePhotos(tripId, dto.photoIds);
    const journal = await this.tripJournalsRepository.updateJournal(
      journalId,
      {
        title: dto.title === undefined ? undefined : this.normalizeTitle(dto.title),
        content: dto.content === undefined ? undefined : this.validateContent(dto.content),
        mood: dto.mood === undefined ? undefined : this.validateMood(dto.mood),
        isDraft: dto.isDraft,
        tripDay:
          dto.tripDayId === undefined
            ? undefined
            : dto.tripDayId
              ? { connect: { id: dto.tripDayId } }
              : { disconnect: true },
        tripPlace:
          dto.tripPlaceId === undefined
            ? undefined
            : dto.tripPlaceId
              ? { connect: { id: dto.tripPlaceId } }
              : { disconnect: true },
      },
      photoIds,
    );
    return { journal: this.toJournalResponse(journal) };
  }

  async delete(tripId: string, journalId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    await this.requireJournal(tripId, journalId);
    await this.tripJournalsRepository.deleteJournal(journalId);
    return { success: true };
  }

  async reorderPhotos(tripId: string, journalId: string, userId: string, photoIds: string[]) {
    await this.requireTripMember(tripId, userId);
    const journal = await this.requireJournal(tripId, journalId);
    await this.validatePhotos(tripId, photoIds);
    const currentPhotoIds = journal.photos.map((link) => link.photoId);
    const requestedPhotoIds = new Set(photoIds);
    if (
      currentPhotoIds.length !== photoIds.length ||
      currentPhotoIds.some((photoId) => !requestedPhotoIds.has(photoId))
    ) {
      throw new BadRequestException('只能调整当前游记已关联照片的顺序');
    }
    const updated = await this.tripJournalsRepository.reorderPhotos(journalId, photoIds);
    return { journal: this.toJournalResponse(updated) };
  }

  private async requireTripMember(tripId: string, userId: string) {
    try {
      return await this.tripMembersService.requireTripMember(tripId, userId);
    } catch (error) {
      if (error instanceof NotFoundException) {
        throw new ForbiddenException('没有权限访问该旅行的游记');
      }
      throw error;
    }
  }

  private async requireJournal(tripId: string, journalId: string) {
    const journal = await this.tripJournalsRepository.findJournalById(journalId);
    if (!journal || journal.tripId !== tripId) {
      throw new NotFoundException('游记不存在');
    }
    return journal;
  }

  private async validateRelations(tripId: string, tripDayId?: string, tripPlaceId?: string) {
    if (tripDayId) {
      const day = await this.tripJournalsRepository.findTripDay(tripDayId);
      if (!day || day.tripId !== tripId) {
        throw new BadRequestException('关联日期不属于当前旅行');
      }
    }
    if (tripPlaceId) {
      const place = await this.tripJournalsRepository.findTripPlace(tripPlaceId);
      if (!place || place.tripId !== tripId) {
        throw new BadRequestException('关联地点不属于当前旅行');
      }
    }
  }

  private async validatePhotos(tripId: string, photoIds: string[]) {
    const uniquePhotoIds = [...new Set(photoIds)];
    if (uniquePhotoIds.length !== photoIds.length) {
      throw new BadRequestException('关联照片不能重复');
    }
    if (uniquePhotoIds.length === 0) return uniquePhotoIds;
    const photos = await this.tripJournalsRepository.findPhotosByIds(uniquePhotoIds);
    if (
      photos.length !== uniquePhotoIds.length ||
      photos.some((photo) => photo.tripId !== tripId)
    ) {
      throw new BadRequestException('关联照片必须属于当前旅行');
    }
    return uniquePhotoIds;
  }

  private normalizeTitle(value: string) {
    const title = value.trim();
    if (!title || title.length > 200) {
      throw new BadRequestException('游记标题长度必须为 1-200 个字符');
    }
    return title;
  }

  private validateContent(value: string) {
    if (value.length > 20000) {
      throw new BadRequestException('游记正文不能超过 20000 个字符');
    }
    return value;
  }

  private validateMood(value?: string | null) {
    if (value && !journalMoods.has(value)) {
      throw new BadRequestException('不支持的心情标签');
    }
    return value ?? null;
  }

  private toJournalResponse(
    journal: Awaited<ReturnType<TripJournalsRepository['findJournalById']>> & object,
  ) {
    return {
      id: journal.id,
      tripId: journal.tripId,
      tripDayId: journal.tripDayId,
      tripPlaceId: journal.tripPlaceId,
      title: journal.title,
      content: journal.content,
      isDraft: journal.isDraft,
      mood: journal.mood,
      createdAt: journal.createdAt,
      updatedAt: journal.updatedAt,
      tripDay: journal.tripDay
        ? { id: journal.tripDay.id, dayIndex: journal.tripDay.dayIndex, date: journal.tripDay.date }
        : null,
      tripPlace: journal.tripPlace
        ? { id: journal.tripPlace.id, name: journal.tripPlace.name }
        : null,
      photos: journal.photos.map((link) => ({
        id: link.photo.id,
        url: link.photo.url,
        caption: link.photo.caption,
        orderIndex: link.orderIndex,
      })),
    };
  }
}
