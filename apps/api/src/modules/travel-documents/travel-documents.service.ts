import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { TravelDocument } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreateTravelDocumentDto } from './dto/create-travel-document.dto';
import { UpdateTravelDocumentDto } from './dto/update-travel-document.dto';
import { TravelDocumentsRepository } from './travel-documents.repository';

const maxDocumentBytes = 10 * 1024 * 1024;
const maxDocumentsPerTrip = 100;
const allowedDataUrlPattern =
  /^data:(application\/pdf|image\/(?:jpeg|jpg|png|webp));base64,([a-zA-Z0-9+/=\r\n]+)$/;

@Injectable()
export class TravelDocumentsService {
  constructor(
    private readonly travelDocumentsRepository: TravelDocumentsRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async findAll(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const documents = await this.travelDocumentsRepository.findDocumentsByTrip(tripId);
    return { documents: documents.map((document) => this.toDocumentResponse(document)) };
  }

  async findOne(tripId: string, documentId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const document = await this.requireDocument(tripId, documentId);
    return { document: this.toDocumentResponse(document) };
  }

  async create(tripId: string, userId: string, dto: CreateTravelDocumentDto) {
    await this.requireTripMember(tripId, userId);
    this.validateDocumentUrl(dto.url);
    const count = await this.travelDocumentsRepository.countDocumentsByTrip(tripId);
    if (count >= maxDocumentsPerTrip) {
      throw new BadRequestException('每个旅行最多上传 100 份文档');
    }
    await this.validateRelations(tripId, dto.tripDayId, dto.tripPlaceId);

    const document = await this.travelDocumentsRepository.createDocument({
      trip: { connect: { id: tripId } },
      tripDay: dto.tripDayId ? { connect: { id: dto.tripDayId } } : undefined,
      tripPlace: dto.tripPlaceId ? { connect: { id: dto.tripPlaceId } } : undefined,
      type: dto.type,
      title: this.normalizeTitle(dto.title),
      url: dto.url,
      notes: this.toNullable(dto.notes),
      expiredAt: dto.expiredAt ? new Date(dto.expiredAt) : null,
      isReminder: dto.isReminder ?? false,
    });

    return { document: this.toDocumentResponse(document) };
  }

  async update(tripId: string, documentId: string, userId: string, dto: UpdateTravelDocumentDto) {
    await this.requireTripMember(tripId, userId);
    await this.requireDocument(tripId, documentId);
    if (dto.url !== undefined) {
      this.validateDocumentUrl(dto.url);
    }
    await this.validateRelations(
      tripId,
      dto.tripDayId === null ? undefined : dto.tripDayId,
      dto.tripPlaceId === null ? undefined : dto.tripPlaceId,
    );

    const document = await this.travelDocumentsRepository.updateDocument(documentId, {
      type: dto.type,
      title: dto.title === undefined ? undefined : this.normalizeTitle(dto.title),
      url: dto.url,
      notes: dto.notes === undefined ? undefined : this.toNullable(dto.notes),
      expiredAt:
        dto.expiredAt === undefined
          ? undefined
          : dto.expiredAt === null
            ? null
            : new Date(dto.expiredAt),
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
      isReminder: dto.isReminder,
    });

    return { document: this.toDocumentResponse(document) };
  }

  async delete(tripId: string, documentId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    await this.requireDocument(tripId, documentId);
    await this.travelDocumentsRepository.deleteDocument(documentId);
    return { success: true };
  }

  private async requireTripMember(tripId: string, userId: string) {
    try {
      return await this.tripMembersService.requireTripMember(tripId, userId);
    } catch (error) {
      if (error instanceof NotFoundException) {
        throw new ForbiddenException('没有权限访问该旅行的文档');
      }
      throw error;
    }
  }

  private async requireDocument(tripId: string, documentId: string) {
    const document = await this.travelDocumentsRepository.findDocumentById(documentId);
    if (!document || document.tripId !== tripId) {
      throw new NotFoundException('文档不存在');
    }
    return document;
  }

  private async validateRelations(tripId: string, tripDayId?: string, tripPlaceId?: string) {
    if (tripDayId) {
      const day = await this.travelDocumentsRepository.findTripDay(tripDayId);
      if (!day || day.tripId !== tripId) {
        throw new BadRequestException('关联日期不属于当前旅行');
      }
    }

    if (tripPlaceId) {
      const place = await this.travelDocumentsRepository.findTripPlace(tripPlaceId);
      if (!place || place.tripId !== tripId) {
        throw new BadRequestException('关联地点不属于当前旅行');
      }
    }
  }

  private validateDocumentUrl(url: string) {
    const match = allowedDataUrlPattern.exec(url);
    if (!match) {
      throw new BadRequestException('仅支持 PDF、jpg、png、webp 文件');
    }
    const bytes = Buffer.byteLength(match[2], 'base64');
    if (bytes > maxDocumentBytes) {
      throw new BadRequestException('单个文档不能超过 10MB');
    }
  }

  private toDocumentResponse(
    document: TravelDocument & {
      tripDay?: { id: string; dayIndex: number; date: Date } | null;
      tripPlace?: { id: string; name: string } | null;
    },
  ) {
    return {
      id: document.id,
      tripId: document.tripId,
      tripDayId: document.tripDayId,
      tripPlaceId: document.tripPlaceId,
      type: document.type,
      title: document.title,
      url: document.url,
      notes: document.notes,
      expiredAt: document.expiredAt,
      isReminder: document.isReminder,
      createdAt: document.createdAt,
      updatedAt: document.updatedAt,
      tripDay: document.tripDay
        ? {
            id: document.tripDay.id,
            dayIndex: document.tripDay.dayIndex,
            date: document.tripDay.date,
          }
        : null,
      tripPlace: document.tripPlace
        ? { id: document.tripPlace.id, name: document.tripPlace.name }
        : null,
    };
  }

  private toNullable(value?: string | null) {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }

  private normalizeTitle(value: string) {
    const title = value.trim();
    if (!title || title.length > 100) {
      throw new BadRequestException('文档标题长度必须为 1-100 个字符');
    }
    return title;
  }
}
