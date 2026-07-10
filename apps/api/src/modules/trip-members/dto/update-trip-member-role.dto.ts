import { TripMemberRole } from '@prisma/client';
import { IsEnum } from 'class-validator';

export class UpdateTripMemberRoleDto {
  @IsEnum(TripMemberRole)
  role!: TripMemberRole;
}
