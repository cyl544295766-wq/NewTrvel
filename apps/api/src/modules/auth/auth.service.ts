import { ForbiddenException, Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import { UserStatus } from '@prisma/client';
import bcrypt from 'bcrypt';
import type { Response } from 'express';
import { UsersService } from '../users/users.service';
import { PublicUser } from '../users/types/public-user.type';
import { LoginDto } from './dto/login.dto';
import { AuthenticatedUser, AuthTokenPayload } from './types/authenticated-user.type';

@Injectable()
export class AuthService {
  constructor(
    private readonly configService: ConfigService,
    private readonly jwtService: JwtService,
    private readonly usersService: UsersService,
  ) {}

  async login(loginDto: LoginDto, response: Response): Promise<{ user: PublicUser }> {
    const user = await this.usersService.findByUsername(loginDto.username);

    if (!user) {
      throw new UnauthorizedException('Invalid username or password');
    }

    if (user.status === UserStatus.disabled) {
      throw new ForbiddenException('User is disabled');
    }

    const isPasswordValid = await bcrypt.compare(loginDto.password, user.passwordHash);

    if (!isPasswordValid) {
      throw new UnauthorizedException('Invalid username or password');
    }

    await this.usersService.updateLastLoginAt(user.id);
    await this.setAuthCookie(response, { sub: user.id, username: user.username });

    return { user: this.usersService.toPublicUser(user) };
  }

  logout(response: Response): { success: true } {
    const cookieName = this.configService.get<string>('AUTH_COOKIE_NAME', 'access_token');

    response.clearCookie(cookieName, {
      httpOnly: true,
      sameSite: 'lax',
      secure: this.configService.get<string>('NODE_ENV') === 'production',
      path: '/',
    });

    return { success: true };
  }

  async getMe(currentUser: AuthenticatedUser): Promise<{ user: PublicUser }> {
    const user = await this.usersService.findById(currentUser.id);

    if (!user) {
      throw new UnauthorizedException('Unauthorized');
    }

    if (user.status === UserStatus.disabled) {
      throw new ForbiddenException('User is disabled');
    }

    return { user: this.usersService.toPublicUser(user) };
  }

  private async setAuthCookie(response: Response, payload: AuthTokenPayload): Promise<void> {
    const cookieName = this.configService.get<string>('AUTH_COOKIE_NAME', 'access_token');
    const expiresIn = '7d' as const;
    const token = await this.jwtService.signAsync(payload, {
      secret: this.configService.getOrThrow<string>('JWT_SECRET'),
      expiresIn,
    });

    response.cookie(cookieName, token, {
      httpOnly: true,
      sameSite: 'lax',
      secure: this.configService.get<string>('NODE_ENV') === 'production',
      maxAge: 7 * 24 * 60 * 60 * 1000,
      path: '/',
    });
  }
}
