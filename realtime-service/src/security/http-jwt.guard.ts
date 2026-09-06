import {
  type CanActivate,
  type ExecutionContext,
  Injectable,
  UnauthorizedException,
} from '@nestjs/common';

import type { AuthenticatedRequest } from './principal.js';
import { IdentityJwtVerifier } from './identity-jwt-verifier.js';

@Injectable()
export class HttpJwtGuard implements CanActivate {
  constructor(private readonly verifier: IdentityJwtVerifier) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const authorization = request.headers.authorization;
    if (typeof authorization !== 'string') {
      throw new UnauthorizedException('Bearer token is required');
    }
    const match = /^Bearer ([^\s]+)$/.exec(authorization);
    if (!match?.[1]) {
      throw new UnauthorizedException('Bearer token is required');
    }
    try {
      request.principal = await this.verifier.verify(match[1]);
      return true;
    } catch {
      throw new UnauthorizedException('Bearer token is invalid');
    }
  }
}
