import { Injectable } from '@nestjs/common';
import { User } from '@prisma/client';
import { UsersRepository } from './users.repository';
import { PublicUser } from './types/public-user.type';

@Injectable()
export class UsersService {
  constructor(private readonly usersRepository: UsersRepository) {}

  findByUsername(username: string): Promise<User | null> {
    return this.usersRepository.findByUsername(username);
  }

  findById(id: string): Promise<User | null> {
    return this.usersRepository.findById(id);
  }

  updateLastLoginAt(id: string): Promise<User> {
    return this.usersRepository.updateLastLoginAt(id);
  }

  toPublicUser(user: User): PublicUser {
    return {
      id: user.id,
      username: user.username,
      displayName: user.displayName,
      avatarUrl: user.avatarUrl,
      status: user.status,
      createdAt: user.createdAt,
    };
  }
}
