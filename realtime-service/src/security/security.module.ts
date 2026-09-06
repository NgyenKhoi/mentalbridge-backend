import { Module } from '@nestjs/common';

import { HttpJwtGuard } from './http-jwt.guard.js';
import { IdentityJwtVerifier } from './identity-jwt-verifier.js';

@Module({
  providers: [IdentityJwtVerifier, HttpJwtGuard],
  exports: [IdentityJwtVerifier, HttpJwtGuard],
})
export class SecurityModule {}
