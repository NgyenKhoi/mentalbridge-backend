import { describe, expect, it } from 'vitest';

import { loadConfiguration } from '../../src/configuration/configuration.js';

const validEnvironment = {
  NODE_ENV: 'test',
  REALTIME_MESSAGE_ENCRYPTION_KEY: Buffer.alloc(32, 1).toString('base64'),
  REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION: 'test-v1',
  IDENTITY_JWT_ISSUER: 'https://identity.test.mentalbridge',
  IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
  IDENTITY_JWT_KEY_ID: 'test-key',
  IDENTITY_JWT_PUBLIC_KEY: 'test-public-key',
} satisfies NodeJS.ProcessEnv;

describe('Realtime configuration', () => {
  it('loads safe test defaults and typed values', () => {
    const configuration = loadConfiguration(validEnvironment);
    expect(configuration.PORT).toBe(3004);
    expect(configuration.MONGODB_DATABASE).toBe('mentalbridge_realtime');
    expect(configuration.MESSAGE_ENCRYPTION_KEY).toHaveLength(32);
    expect(configuration.MESSAGE_DECRYPTION_KEYS['test-v1']).toHaveLength(32);
  });

  it('uses explicit environment values instead of local defaults', () => {
    const configuration = loadConfiguration({
      ...validEnvironment,
      REALTIME_PORT: '4310',
      REALTIME_MONGODB_URI: 'mongodb://database.internal:27017',
      REALTIME_REDIS_URL: 'redis://redis.internal:6379',
    });
    expect(configuration.PORT).toBe(4310);
    expect(configuration.MONGODB_URI).toBe('mongodb://database.internal:27017');
    expect(configuration.REDIS_URL).toBe('redis://redis.internal:6379');
  });

  it('loads versioned historical decryption keys', () => {
    const historicalKey = Buffer.alloc(32, 2).toString('base64');
    const configuration = loadConfiguration({
      ...validEnvironment,
      REALTIME_MESSAGE_DECRYPTION_KEYS: JSON.stringify({ 'test-v0': historicalKey }),
    });
    expect(configuration.MESSAGE_DECRYPTION_KEYS['test-v0']).toEqual(Buffer.alloc(32, 2));
    expect(configuration.MESSAGE_DECRYPTION_KEYS['test-v1']).toEqual(Buffer.alloc(32, 1));
  });

  it('rejects malformed historical decryption keys and active key conflicts', () => {
    expect(() =>
      loadConfiguration({
        ...validEnvironment,
        REALTIME_MESSAGE_DECRYPTION_KEYS: '{invalid-json',
      }),
    ).toThrow();
    expect(() =>
      loadConfiguration({
        ...validEnvironment,
        REALTIME_MESSAGE_DECRYPTION_KEYS: JSON.stringify({
          'test-v1': Buffer.alloc(32, 2).toString('base64'),
        }),
      }),
    ).toThrow();
  });

  it('rejects a short encryption key', () => {
    expect(() =>
      loadConfiguration({
        ...validEnvironment,
        REALTIME_MESSAGE_ENCRYPTION_KEY: Buffer.alloc(16).toString('base64'),
      }),
    ).toThrow();
  });

  it('rejects a TTL shorter than two heartbeat intervals', () => {
    expect(() =>
      loadConfiguration({
        ...validEnvironment,
        REALTIME_PRESENCE_TTL_SECONDS: '10',
        REALTIME_PRESENCE_HEARTBEAT_SECONDS: '6',
      }),
    ).toThrow();
  });

  it('requires explicit infrastructure in production', () => {
    expect(() => loadConfiguration({ ...validEnvironment, NODE_ENV: 'production' })).toThrow();
  });

  it('rejects localhost infrastructure in production', () => {
    expect(() =>
      loadConfiguration({
        ...validEnvironment,
        NODE_ENV: 'production',
        REALTIME_MONGODB_URI: 'mongodb://localhost:27017',
        REALTIME_MONGODB_DATABASE: 'realtime',
        REALTIME_REDIS_URL: 'redis://localhost:6379',
      }),
    ).toThrow();
  });
});
