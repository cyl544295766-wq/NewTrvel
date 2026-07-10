import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { PackingItem, PackingList } from '@prisma/client';
import { TripMembersService } from '../trip-members/trip-members.service';
import { CreatePackingItemDto } from './dto/create-packing-item.dto';
import { CreatePackingListDto } from './dto/create-packing-list.dto';
import { UpdatePackingItemDto } from './dto/update-packing-item.dto';
import { UpdatePackingListDto } from './dto/update-packing-list.dto';
import { defaultPackingTemplates } from './packing-list-templates';
import { PackingListsRepository } from './packing-lists.repository';

type PackingListWithItems = PackingList & { items: PackingItem[] };

@Injectable()
export class PackingListsService {
  constructor(
    private readonly packingListsRepository: PackingListsRepository,
    private readonly tripMembersService: TripMembersService,
  ) {}

  async findAll(tripId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const lists = await this.packingListsRepository.findListsByTrip(tripId);
    return { packingLists: lists.map((list) => this.toListResponse(list)) };
  }

  async create(tripId: string, userId: string, dto: CreatePackingListDto) {
    await this.requireTripMember(tripId, userId);
    const templateItems = defaultPackingTemplates[dto.category];
    const list = await this.packingListsRepository.createList({
      trip: { connect: { id: tripId } },
      name: this.normalizeName(dto.name, 100, '清单名称'),
      category: dto.category,
      items: {
        create: templateItems.map((item, orderIndex) => ({ ...item, orderIndex })),
      },
    });
    return { packingList: this.toListResponse(list) };
  }

  async update(tripId: string, listId: string, userId: string, dto: UpdatePackingListDto) {
    await this.requireTripMember(tripId, userId);
    await this.requireList(tripId, listId);
    const list = await this.packingListsRepository.updateList(listId, {
      name: dto.name === undefined ? undefined : this.normalizeName(dto.name, 100, '清单名称'),
      category: dto.category,
    });
    return { packingList: this.toListResponse(list) };
  }

  async delete(tripId: string, listId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    await this.requireList(tripId, listId);
    await this.packingListsRepository.deleteList(listId);
    return { success: true };
  }

  async createItem(tripId: string, listId: string, userId: string, dto: CreatePackingItemDto) {
    await this.requireTripMember(tripId, userId);
    const list = await this.requireList(tripId, listId);
    if (dto.quantity !== undefined && dto.quantity < 1) {
      throw new BadRequestException('物品数量必须大于等于 1');
    }
    const item = await this.packingListsRepository.createItem(listId, {
      name: this.normalizeName(dto.name, 200, '物品名称'),
      quantity: dto.quantity ?? 1,
      notes: this.toNullable(dto.notes),
      orderIndex: dto.orderIndex ?? list.items.length,
    });
    return { item: this.toItemResponse(item) };
  }

  async updateItem(
    tripId: string,
    listId: string,
    itemId: string,
    userId: string,
    dto: UpdatePackingItemDto,
  ) {
    await this.requireTripMember(tripId, userId);
    await this.requireList(tripId, listId);
    await this.requireItem(listId, itemId);
    if (dto.quantity !== undefined && dto.quantity < 1) {
      throw new BadRequestException('物品数量必须大于等于 1');
    }
    const item = await this.packingListsRepository.updateItem(itemId, {
      name: dto.name === undefined ? undefined : this.normalizeName(dto.name, 200, '物品名称'),
      quantity: dto.quantity,
      isPacked: dto.isPacked,
      notes: dto.notes === undefined ? undefined : this.toNullable(dto.notes),
      orderIndex: dto.orderIndex,
    });
    return { item: this.toItemResponse(item) };
  }

  async deleteItem(tripId: string, listId: string, itemId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    await this.requireList(tripId, listId);
    await this.requireItem(listId, itemId);
    await this.packingListsRepository.deleteItem(itemId);
    return { success: true };
  }

  async duplicate(tripId: string, listId: string, userId: string) {
    await this.requireTripMember(tripId, userId);
    const source = await this.requireList(tripId, listId);
    const list = await this.packingListsRepository.duplicateList(
      tripId,
      source,
      this.buildDuplicateName(source.name),
    );
    return { packingList: this.toListResponse(list) };
  }

  private async requireTripMember(tripId: string, userId: string) {
    try {
      return await this.tripMembersService.requireTripMember(tripId, userId);
    } catch (error) {
      if (error instanceof NotFoundException) {
        throw new ForbiddenException('没有权限访问该旅行的打包清单');
      }
      throw error;
    }
  }

  private async requireList(tripId: string, listId: string) {
    const list = await this.packingListsRepository.findListById(listId);
    if (!list || list.tripId !== tripId) {
      throw new NotFoundException('打包清单不存在');
    }
    return list;
  }

  private async requireItem(listId: string, itemId: string) {
    const item = await this.packingListsRepository.findItemById(itemId);
    if (!item || item.packingListId !== listId) {
      throw new NotFoundException('打包物品不存在');
    }
    return item;
  }

  private toListResponse(list: PackingListWithItems) {
    const packedCount = list.items.filter((item) => item.isPacked).length;
    return {
      id: list.id,
      tripId: list.tripId,
      name: list.name,
      category: list.category,
      packedCount,
      itemCount: list.items.length,
      createdAt: list.createdAt,
      updatedAt: list.updatedAt,
      items: list.items.map((item) => this.toItemResponse(item)),
    };
  }

  private toItemResponse(item: PackingItem) {
    return {
      id: item.id,
      packingListId: item.packingListId,
      name: item.name,
      quantity: item.quantity,
      isPacked: item.isPacked,
      notes: item.notes,
      orderIndex: item.orderIndex,
    };
  }

  private normalizeName(value: string, maxLength: number, label: string) {
    const name = value.trim();
    if (!name || name.length > maxLength) {
      throw new BadRequestException(`${label}长度必须为 1-${maxLength} 个字符`);
    }
    return name;
  }

  private toNullable(value?: string | null) {
    const trimmed = value?.trim();
    return trimmed ? trimmed : null;
  }

  private buildDuplicateName(name: string) {
    const suffix = ' 副本';
    return `${name.slice(0, 100 - suffix.length)}${suffix}`;
  }
}
