import { Module } from '@nestjs/common';
import { PassportModule } from '@nestjs/passport';
import { JwtStrategy } from './jwt.strategy.js';
import { RolesGuard } from './roles.guard.js';

@Module({
  imports: [PassportModule],
  providers: [JwtStrategy, RolesGuard],
  exports: [JwtStrategy, RolesGuard],
})
export class AuthModule {}
