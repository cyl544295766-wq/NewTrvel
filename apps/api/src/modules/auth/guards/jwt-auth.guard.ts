import { CanActivate, ExecutionContext, Injectable, UnauthorizedException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { JwtService } from '@nestjs/jwt';
import type { Request } from 'express';
import { AuthenticatedUser, AuthTokenPayload } from '../types/authenticated-user.type';

type CookieRequest = Request & {
  cookies?: Record<string, string | undefined>;
  user?: AuthenticatedUser;
};

@Injectable()
export class JwtAuthGuard implements CanActivate {
  constructor(
    private readonly configService: ConfigService,
    private readonly jwtService: JwtService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<CookieRequest>();
    const cookieName = this.configService.get<string>('AUTH_COOKIE_NAME', 'access_token');
    const token = request.cookies?.[cookieName];

    if (!token) {
      throw new UnauthorizedException('Unauthorized');
    }

    try {
      const payload = await this.jwtService.verifyAsync<AuthTokenPayload>(token, {
        secret: this.configService.getOrThrow<string>('JWT_SECRET'),
      });

      request.user = {
        id: payload.sub,
        username: payload.username,
      };

      return true;
    } catch {
      throw new UnauthorizedException('Unauthorized');
    }
  }
}
