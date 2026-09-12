import { Module } from '@nestjs/common';
import { PassportModule } from '@nestjs/passport';
import { RolesGuard } from './roles.guard.js';

@Module({
  imports: [PassportModule],
  providers: [RolesGuard],
  exports: [RolesGuard],
})
export class AuthModule {}
