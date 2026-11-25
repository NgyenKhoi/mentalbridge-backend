import { Controller, Post, Req, UseGuards } from '@nestjs/common';

import { HttpJwtGuard } from './http-jwt.guard.js';
import type { AuthenticatedRequest } from './principal.js';
import { SocketCredentialService } from './socket-credential.service.js';

@Controller('internal/v1/socket-credentials')
@UseGuards(HttpJwtGuard)
export class SocketCredentialController {
  constructor(private readonly credentials: SocketCredentialService) {}

  @Post()
  issue(@Req() request: AuthenticatedRequest) {
    if (!request.principal || !request.bearerToken)
      throw new Error('Authenticated actor is missing');
    return this.credentials.issue(request.principal, request.bearerToken);
  }
}
