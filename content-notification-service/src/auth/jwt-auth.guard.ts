import { Injectable, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { AuthGuard } from '@nestjs/passport';
import type { AuthenticatedUser } from './jwt.strategy.js';

@Injectable()
export class JwtAuthGuard extends AuthGuard('jwt') {
  override canActivate(context: ExecutionContext) {
    return super.canActivate(context);
  }

  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters
  override handleRequest<TUser = AuthenticatedUser>(
    err: Error | null,
    user: AuthenticatedUser | false,
    _info: unknown,
    _context: ExecutionContext,
    _status?: unknown,
  ): TUser {
    if (err ?? !user) {
      throw err ?? new UnauthorizedException('Unauthorized');
    }
    return user as TUser;
  }
}
