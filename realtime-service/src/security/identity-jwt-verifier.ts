import { Inject, Injectable, type OnModuleInit } from '@nestjs/common';
import { importSPKI, jwtVerify, type CryptoKey, type JWTPayload } from 'jose';
import { z } from 'zod';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { CONFIGURATION_TOKEN } from '../shared/tokens.js';
import type { AuthenticatedPrincipal } from './principal.js';

const subjectSchema = z.uuid();
const rolesSchema = z.array(z.enum(['USER', 'SPECIALIST', 'ADMIN'])).length(1);

@Injectable()
export class IdentityJwtVerifier implements OnModuleInit {
  private verificationKey?: CryptoKey;

  constructor(@Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration) {}

  async onModuleInit(): Promise<void> {
    this.verificationKey = await importSPKI(this.configuration.IDENTITY_JWT_PUBLIC_KEY, 'RS256');
  }

  async verify(token: string): Promise<AuthenticatedPrincipal> {
    if (!this.verificationKey) {
      throw new Error('Identity JWT verifier is not initialized');
    }
    const { payload, protectedHeader } = await jwtVerify(token, this.verificationKey, {
      algorithms: ['RS256'],
      issuer: this.configuration.IDENTITY_JWT_ISSUER,
      audience: this.configuration.IDENTITY_JWT_AUDIENCE,
      clockTolerance: this.configuration.IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS,
      requiredClaims: ['sub', 'iat', 'nbf', 'exp', 'jti', 'roles'],
    });
    if (protectedHeader.kid !== this.configuration.IDENTITY_JWT_KEY_ID) {
      throw new Error('Identity JWT key ID is invalid');
    }
    return this.toPrincipal(payload);
  }

  private toPrincipal(payload: JWTPayload): AuthenticatedPrincipal {
    const accountId = subjectSchema.parse(payload.sub);
    const roles = rolesSchema.parse(payload.roles);
    const role = roles[0];
    if (!role) {
      throw new Error('Identity JWT role is invalid');
    }
    if (!payload.jti) {
      throw new Error('Identity JWT token ID is invalid');
    }
    return { accountId, role, tokenId: payload.jti };
  }
}
