import { Module } from '@nestjs/common';

import { HttpJwtGuard } from './http-jwt.guard.js';
import { IdentityJwtVerifier } from './identity-jwt-verifier.js';
import { SocketCredentialController } from './socket-credential.controller.js';
import { SocketCredentialService } from './socket-credential.service.js';

@Module({
  controllers: [SocketCredentialController],
  providers: [IdentityJwtVerifier, HttpJwtGuard, SocketCredentialService],
  exports: [IdentityJwtVerifier, HttpJwtGuard, SocketCredentialService],
})
export class SecurityModule {}
