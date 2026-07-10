import { Body, Controller, Delete, Get, Param, Patch, Post, UseGuards } from '@nestjs/common';
import { CurrentUser } from '../auth/decorators/current-user.decorator';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { AuthenticatedUser } from '../auth/types/authenticated-user.type';
import { CreatePackingItemDto } from './dto/create-packing-item.dto';
import { CreatePackingListDto } from './dto/create-packing-list.dto';
import { UpdatePackingItemDto } from './dto/update-packing-item.dto';
import { UpdatePackingListDto } from './dto/update-packing-list.dto';
import { PackingListsService } from './packing-lists.service';

@UseGuards(JwtAuthGuard)
@Controller('trips/:tripId/packing-lists')
export class PackingListsController {
  constructor(private readonly packingListsService: PackingListsService) {}

  @Get()
  findAll(@Param('tripId') tripId: string, @CurrentUser() user: AuthenticatedUser) {
    return this.packingListsService.findAll(tripId, user.id);
  }

  @Post()
  create(
    @Param('tripId') tripId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreatePackingListDto,
  ) {
    return this.packingListsService.create(tripId, user.id, dto);
  }

  @Patch(':listId')
  update(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdatePackingListDto,
  ) {
    return this.packingListsService.update(tripId, listId, user.id, dto);
  }

  @Delete(':listId')
  delete(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.packingListsService.delete(tripId, listId, user.id);
  }

  @Post(':listId/items')
  createItem(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: CreatePackingItemDto,
  ) {
    return this.packingListsService.createItem(tripId, listId, user.id, dto);
  }

  @Patch(':listId/items/:itemId')
  updateItem(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @Param('itemId') itemId: string,
    @CurrentUser() user: AuthenticatedUser,
    @Body() dto: UpdatePackingItemDto,
  ) {
    return this.packingListsService.updateItem(tripId, listId, itemId, user.id, dto);
  }

  @Delete(':listId/items/:itemId')
  deleteItem(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @Param('itemId') itemId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.packingListsService.deleteItem(tripId, listId, itemId, user.id);
  }

  @Post(':listId/duplicate')
  duplicate(
    @Param('tripId') tripId: string,
    @Param('listId') listId: string,
    @CurrentUser() user: AuthenticatedUser,
  ) {
    return this.packingListsService.duplicate(tripId, listId, user.id);
  }
}
