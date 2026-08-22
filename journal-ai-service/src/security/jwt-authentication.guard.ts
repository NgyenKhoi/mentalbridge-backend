import {
  type CanActivate,
  type ExecutionContext,
  Injectable,
  UnauthorizedException,
} from "@nestjs/common";
import { Reflector } from "@nestjs/core";

import type { AuthenticatedRequest } from "./authenticated-principal.js";
import { IdentityJwtVerifier } from "./identity-jwt-verifier.js";
import { PUBLIC_ROUTE_METADATA } from "./public.decorator.js";

@Injectable()
export class JwtAuthenticationGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly verifier: IdentityJwtVerifier,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const isPublic = this.reflector.getAllAndOverride<boolean>(
      PUBLIC_ROUTE_METADATA,
      [context.getHandler(), context.getClass()],
    );

    if (isPublic) {
      return true;
    }

    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const token = this.bearerToken(request.headers.authorization);

    try {
      request.principal = await this.verifier.verify(token);
      return true;
    } catch {
      throw new UnauthorizedException("Bearer token is invalid");
    }
  }

  private bearerToken(header: string | string[] | undefined): string {
    if (typeof header !== "string") {
      throw new UnauthorizedException("Bearer token is required");
    }

    const match = /^Bearer ([^\s]+)$/.exec(header);
    if (!match?.[1]) {
      throw new UnauthorizedException("Bearer token is required");
    }

    return match[1];
  }
}
