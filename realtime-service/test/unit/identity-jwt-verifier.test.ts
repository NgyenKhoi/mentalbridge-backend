import { exportSPKI, generateKeyPair, SignJWT } from 'jose';
import { describe, expect, it } from 'vitest';

import { IdentityJwtVerifier } from '../../src/security/identity-jwt-verifier.js';
import { testConfiguration } from '../fixtures/test-configuration.js';
import { issueToken } from '../fixtures/token.js';

describe('Identity JWT verification', () => {
  it('accepts the exact Identity issuer, audience, key and claims', async () => {
    const keys = await generateKeyPair('RS256', { extractable: true });
    const configuration = testConfiguration(await exportSPKI(keys.publicKey));
    const verifier = new IdentityJwtVerifier(configuration);
    await verifier.onModuleInit();
    const principal = await verifier.verify(await issueToken(keys.privateKey, configuration));
    expect(principal).toEqual({
      accountId: '11111111-1111-4111-8111-111111111111',
      role: 'USER',
      tokenId: 'test-token-id',
    });
  });

  it('rejects a token for another audience and an expired token', async () => {
    const keys = await generateKeyPair('RS256', { extractable: true });
    const configuration = testConfiguration(await exportSPKI(keys.publicKey));
    const verifier = new IdentityJwtVerifier(configuration);
    await verifier.onModuleInit();
    const now = Math.floor(Date.now() / 1000);
    const invalidAudience = await new SignJWT({ roles: ['USER'] })
      .setProtectedHeader({ alg: 'RS256', kid: configuration.IDENTITY_JWT_KEY_ID })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience('another-api')
      .setSubject('11111111-1111-4111-8111-111111111111')
      .setIssuedAt(now)
      .setNotBefore(now)
      .setExpirationTime(now + 60)
      .setJti('invalid-audience')
      .sign(keys.privateKey);
    const expired = await new SignJWT({ roles: ['USER'] })
      .setProtectedHeader({ alg: 'RS256', kid: configuration.IDENTITY_JWT_KEY_ID })
      .setIssuer(configuration.IDENTITY_JWT_ISSUER)
      .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
      .setSubject('11111111-1111-4111-8111-111111111111')
      .setIssuedAt(now - 120)
      .setNotBefore(now - 120)
      .setExpirationTime(now - 60)
      .setJti('expired')
      .sign(keys.privateKey);
    await expect(verifier.verify(invalidAudience)).rejects.toThrow();
    await expect(verifier.verify(expired)).rejects.toThrow();
  });
});
