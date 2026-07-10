import { IsNotEmpty, IsString, Matches, MinLength } from 'class-validator';

export class LoginDto {
  @IsString()
  @IsNotEmpty()
  @Matches(/^[a-zA-Z0-9_-]{3,32}$/)
  username!: string;

  @IsString()
  @MinLength(8)
  password!: string;
}
