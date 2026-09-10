import { Injectable, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import type { AuthenticatedUser } from './jwt.strategy.js';

@Injectable()
export class JwtAuthGuard extends AuthGuard('jwt') {
  override canActivate(context: ExecutionContext) {
    return super.canActivate(context);
  }

  override handleRequest(
    err: Error | null,
    user: AuthenticatedUser | false,
    _info: unknown,
  ): AuthenticatedUser {
    if (err ?? !user) {
      throw err ?? new UnauthorizedException('Unauthorized');
    }
    return user;
  }
}
