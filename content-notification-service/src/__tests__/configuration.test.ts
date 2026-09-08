import { describe, expect, it } from 'vitest';

import { loadConfiguration } from '../configuration/configuration.js';

const requiredEnvironment = {
  NODE_ENV: 'test',
  DATABASE_URL: 'postgres://test_user:test_password@localhost:5432/test_db',
  IDENTITY_JWT_ISSUER: 'https://identity.local.mentalbridge',
  IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
  IDENTITY_JWT_PUBLIC_KEY:
    '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0\n-----END PUBLIC KEY-----',
} satisfies NodeJS.ProcessEnv;

describe('configuration', () => {
  it('loads required values and defaults', () => {
    const configuration = loadConfiguration(requiredEnvironment);

    expect(configuration.DATABASE_URL).toBe(requiredEnvironment.DATABASE_URL);
    expect(configuration.DB_POOL_MAX).toBe(10);
    expect(configuration.PORT).toBe(3003);
    expect(configuration.LOG_LEVEL).toBe('info');
  });

  it('normalizes configured CORS origins', () => {
    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CORS_ORIGINS: 'https://admin.example, https://app.example',
    });

    expect(configuration.ALLOWED_ORIGINS).toEqual(['https://admin.example', 'https://app.example']);
  });

  it('rejects a missing DATABASE_URL', () => {
    const environment: NodeJS.ProcessEnv = { ...requiredEnvironment };
    delete environment.DATABASE_URL;

    expect(() => loadConfiguration(environment)).toThrow();
  });

  it('rejects an invalid port', () => {
    expect(() => loadConfiguration({ ...requiredEnvironment, PORT: '70000' })).toThrow();
  });
});
