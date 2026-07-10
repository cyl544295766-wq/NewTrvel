import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { Photo } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreatePhotoDto } from './dto/create-photo.dto';
import { UpdatePhotoDto } from './dto/update-photo.dto';
import { PhotosRepository } from './photos.repository';

const maxPhotoBytes = 5 * 1024 * 1024;
const maxPhotosPerTrip = 500;
const allowedDataUrlPattern = /^data:image\/(jpeg|jpg|png|webp);base64,/;

@Injectable()
export class PhotosService {
  constructor(
    private readonly photosRepository: PhotosRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async findAll(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const photos = await this.photosRepository.findPhotosByTrip(tripId);
    return { photos: photos.map((photo) => this.toPhotoResponse(photo)) };
  }

  async findOne(tripId: string, photoId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const photo = await this.requirePhoto(tripId, photoId);
    return { photo: this.toPhotoResponse(photo) };
  }

  async create(tripId: string, userId: string, dto: CreatePhotoDto) {
    await this.requireTripMember(tripId, userId);
    this.validatePhotoUrl(dto.url);
    const count = await this.photosRepository.countPhotosByTrip(tripId);
    if (count >= maxPhotosPerTrip) {
      throw new BadRequestException('每个旅行最多上传 500 张照片');
    }
    await this.validateRelations(tripId, dto.tripDayId, dto.tripPlaceId);
    const isCover = dto.isCover ?? false;

    const photo = await this.photosRepository.createPhoto(
      tripId,
      {
        trip: { connect: { id: tripId } },
        tripDay: dto.tripDayId ? { connect: { id: dto.tripDayId } } : undefined,
        tripPlace: dto.tripPlaceId ? { connect: { id: dto.tripPlaceId } } : undefined,
        url: dto.url,
        caption: this.toNullable(dto.caption),
        shotAt: dto.shotAt ? new Date(dto.shotAt) : null,
        isCover,
      },
      isCover,
    );

    return { photo: this.toPhotoResponse(photo) };
  }

  async update(tripId: string, photoId: string, userId: string, dto: UpdatePhotoDto) {
    await this.requireTripMember(tripId, userId);
    await this.requirePhoto(tripId, photoId);
    await this.validateRelations(
      tripId,
      dto.tripDayId === null ? undefined : dto.tripDayId,
      dto.tripPlaceId === null ? undefined : dto.tripPlaceId,
    );
    const isCover = dto.isCover;

    const photo = await this.photosRepository.updatePhoto(
      photoId,
      {
        caption: dto.caption === undefined ? undefined : this.toNullable(dto.caption),
        shotAt:
          dto.shotAt === undefined ? undefined : dto.shotAt === null ? null : new Date(dto.shotAt),
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
        isCover,
      },
      tripId,
      isCover,
    );

    return { photo: this.toPhotoResponse(photo) };
  }

  async delete(tripId: string, photoId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    await this.requirePhoto(tripId, photoId);
    await this.photosRepository.deletePhoto(photoId);
    return { success: true };
  }

  private async requireTripMember(tripId: string, userId: string) {
    try {
      return await this.tripMembersService.requireTripMember(tripId, userId);
    } catch (error) {
      if (error instanceof NotFoundException) {
        throw new ForbiddenException('No permission to access photos for this trip');
      }

      throw error;
    }
  }

  private async requirePhoto(tripId: string, photoId: string) {
    const photo = await this.photosRepository.findPhotoById(photoId);
    if (!photo || photo.tripId !== tripId) {
      throw new NotFoundException('照片不存在');
    }

    return photo;
  }

  private async validateRelations(tripId: string, tripDayId?: string, tripPlaceId?: string) {
    if (tripDayId) {
      const day = await this.photosRepository.findTripDay(tripDayId);
      if (!day || day.tripId !== tripId) {
        throw new BadRequestException('关联日期不属于当前旅行');
      }
    }

    if (tripPlaceId) {
      const place = await this.photosRepository.findTripPlace(tripPlaceId);
      if (!place || place.tripId !== tripId) {
        throw new BadRequestException('关联地点不属于当前旅行');
      }
    }
  }

  private validatePhotoUrl(url: string) {
    if (!allowedDataUrlPattern.test(url)) {
      throw new BadRequestException('仅支持 jpg、png、webp 图片');
    }

    const base64 = url.split(',')[1] ?? '';
    const bytes = Math.floor((base64.length * 3) / 4);
    if (bytes > maxPhotoBytes) {
      throw new BadRequestException('单张图片不能超过 5MB');
    }
  }

  private toPhotoResponse(
    photo: Photo & {
      tripDay?: { id: string; dayIndex: number; date: Date } | null;
      tripPlace?: { id: string; name: string } | null;
    },
  ) {
    return {
      id: photo.id,
      tripId: photo.tripId,
      tripDayId: photo.tripDayId,
      tripPlaceId: photo.tripPlaceId,
      url: photo.url,
      caption: photo.caption,
      shotAt: photo.shotAt,
      isCover: photo.isCover,
      createdAt: photo.createdAt,
      updatedAt: photo.updatedAt,
      tripDay: photo.tripDay
        ? { id: photo.tripDay.id, dayIndex: photo.tripDay.dayIndex, date: photo.tripDay.date }
        : null,
      tripPlace: photo.tripPlace ? { id: photo.tripPlace.id, name: photo.tripPlace.name } : null,
    };
  }

  private toNullable(value?: string) {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }
}
