import { SignJWT } from 'jose';

import type { ServiceConfiguration } from '../../src/configuration/configuration.js';

export const issueToken = async (
  privateKey: Parameters<InstanceType<typeof SignJWT>['sign']>[0],
  configuration: ServiceConfiguration,
  accountId = '11111111-1111-4111-8111-111111111111',
  options: {
    readonly expiresInSeconds?: number;
    readonly role?: 'USER' | 'SPECIALIST' | 'ADMIN';
    readonly tokenId?: string;
  } = {},
): Promise<string> => {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ roles: [options.role ?? 'USER'] })
    .setProtectedHeader({ alg: 'RS256', kid: configuration.IDENTITY_JWT_KEY_ID })
    .setIssuer(configuration.IDENTITY_JWT_ISSUER)
    .setAudience(configuration.IDENTITY_JWT_AUDIENCE)
    .setSubject(accountId)
    .setIssuedAt(now)
    .setNotBefore(now)
    .setExpirationTime(now + (options.expiresInSeconds ?? 300))
    .setJti(options.tokenId ?? 'test-token-id')
    .sign(privateKey);
};
