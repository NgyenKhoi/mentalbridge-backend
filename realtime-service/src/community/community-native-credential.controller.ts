import type { IncomingMessage, ServerResponse } from 'node:http';
import { Body, Controller, HttpCode, Inject, Post, Req, Res } from '@nestjs/common';
import { z } from 'zod';
import { ApplicationException } from '../http/application.exception.js';
import { IdentityJwtVerifier } from '../security/identity-jwt-verifier.js';
import type { AuthenticatedPrincipal } from '../security/principal.js';
import { CommunityCredentials } from './community-credentials.js';
import { CommunityError } from './community-schema.js';

const emptyRequest = z.object({}).strict();

@Controller('api/v1/community/socket-credentials')
export class CommunityNativeCredentialController {
  constructor(
    @Inject(IdentityJwtVerifier) private readonly verifier: IdentityJwtVerifier,
    @Inject(CommunityCredentials) private readonly credentials: CommunityCredentials,
  ) {}
  @Post()
  @HttpCode(200)
  async issue(
    @Req() request: IncomingMessage,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: ServerResponse,
  ) {
    response.setHeader('Cache-Control', 'no-store');
    const token = /^Bearer ([^\s]+)$/.exec(request.headers.authorization ?? '')?.[1];
    let principal: AuthenticatedPrincipal;
    try {
      if (!token) throw new Error('Missing credential');
      principal = await this.verifier.verify(token);
    } catch {
      throw new ApplicationException(401, 'UNAUTHENTICATED', 'Authentication is required');
    }
    if (
      (body === undefined &&
        (request.headers['transfer-encoding'] !== undefined ||
          Number(request.headers['content-length'] ?? 0) > 0)) ||
      !emptyRequest.safeParse(body === undefined ? {} : body).success
    )
      throw new ApplicationException(400, 'INVALID_REQUEST', 'An empty request is required');
    try {
      return await this.credentials.issueNative(principal);
    } catch (error) {
      if (error instanceof ApplicationException) throw error;
      if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE')
        throw new ApplicationException(404, 'TARGET_UNAVAILABLE', 'Target is unavailable');
      throw new ApplicationException(
        503,
        'DEPENDENCY_UNAVAILABLE',
        'Community credential is unavailable',
        true,
      );
    }
  }
}
