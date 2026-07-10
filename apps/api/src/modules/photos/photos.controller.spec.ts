import { ForbiddenException } from '@nestjs/common';
import { Test, TestingModule } from '@nestjs/testing';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { PhotosController } from './photos.controller';
import { PhotosService } from './photos.service';

describe('PhotosController', () => {
  let controller: PhotosController;
  let service: jest.Mocked<PhotosService>;

  beforeEach(async () => {
    service = {
      findAll: jest.fn(),
      delete: jest.fn(),
    } as unknown as jest.Mocked<PhotosService>;

    const module: TestingModule = await Test.createTestingModule({
      controllers: [PhotosController],
      providers: [{ provide: PhotosService, useValue: service }],
    })
      .overrideGuard(JwtAuthGuard)
      .useValue({ canActivate: () => true })
      .compile();

    controller = module.get(PhotosController);
  });

  it('passes the authenticated member to the photo list service', async () => {
    service.findAll.mockResolvedValue({ photos: [] });

    await expect(controller.findAll('trip-1', { id: 'user-1' } as never)).resolves.toEqual({
      photos: [],
    });
    expect(service.findAll).toHaveBeenCalledWith('trip-1', 'user-1');
  });

  it('propagates a forbidden delete for a non-member', async () => {
    service.delete.mockRejectedValue(new ForbiddenException());

    await expect(
      controller.delete('trip-1', 'photo-1', { id: 'user-2' } as never),
    ).rejects.toBeInstanceOf(ForbiddenException);
  });
});
