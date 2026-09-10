import { Injectable, CanActivate, ExecutionContext, ForbiddenException } from '@nestjs/common';
import { Reflector } from '@nestjs/core';
import type { Request } from 'express';
import type { AuthenticatedUser } from './jwt.strategy.js';
import { IS_PUBLIC_KEY } from './public.decorator.js';

export const ROLES_KEY = 'roles';

@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const isPublic =
      this.reflector.getAllAndOverride<boolean | undefined>(IS_PUBLIC_KEY, [
        context.getHandler(),
        context.getClass(),
      ]) ?? false;
    if (isPublic) {
      return true;
    }

    const requiredRoles =
      this.reflector.getAllAndOverride<string[] | undefined>(ROLES_KEY, [
        context.getHandler(),
        context.getClass(),
      ]) ?? [];

    if (requiredRoles.length === 0) {
      return true;
    }

    const request = context.switchToHttp().getRequest<Request & { user?: AuthenticatedUser }>();
    const user = request.user;

    if (!user?.roles) {
      throw new ForbiddenException('Forbidden resource');
    }

    if (!requiredRoles.some((role) => user.roles.includes(role))) {
      throw new ForbiddenException('Forbidden resource');
    }

    return true;
  }
}
