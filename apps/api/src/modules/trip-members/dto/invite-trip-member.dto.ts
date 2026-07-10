import { TripMemberRole } from '@prisma/client';
import { IsEnum, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';

export class InviteTripMemberDto {
  @IsString()
  @MinLength(1)
  @MaxLength(80)
  username!: string;

  @IsOptional()
  @IsEnum(TripMemberRole)
  role?: TripMemberRole;
}
