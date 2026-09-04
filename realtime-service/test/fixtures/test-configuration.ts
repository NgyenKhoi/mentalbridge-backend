import type { ServiceConfiguration } from '../../src/configuration/configuration.js';

export const encryptionKey = Buffer.alloc(32, 7);

export const testConfiguration = (
  publicKey: string,
  overrides: Partial<ServiceConfiguration> = {},
): ServiceConfiguration => ({
  NODE_ENV: 'test',
  PORT: 3004,
  LOG_LEVEL: 'silent',
  SERVICE_NAME: 'realtime-service',
  ALLOWED_ORIGINS: [],
  MONGODB_URI: 'mongodb://localhost:27017',
  MONGODB_DATABASE: 'mentalbridge_realtime_test',
  MONGODB_CONNECTION_TIMEOUT_MS: 100,
  REDIS_URL: 'redis://localhost:6379',
  REDIS_CONNECTION_TIMEOUT_MS: 100,
  PRESENCE_TTL_SECONDS: 10,
  PRESENCE_HEARTBEAT_SECONDS: 3,
  MAX_CONNECTIONS_PER_ACCOUNT: 5,
  MAX_PAYLOAD_BYTES: 16_384,
  COMMAND_RATE_LIMIT: 60,
  COMMAND_RATE_WINDOW_SECONDS: 60,
  SHUTDOWN_TIMEOUT_MS: 10_000,
  MESSAGE_ENCRYPTION_KEY: encryptionKey,
  MESSAGE_ENCRYPTION_KEY_VERSION: 'test-v1',
  IDENTITY_JWT_ISSUER: 'https://identity.test.mentalbridge',
  IDENTITY_JWT_AUDIENCE: 'mentalbridge-api',
  IDENTITY_JWT_KEY_ID: 'test-key',
  IDENTITY_JWT_PUBLIC_KEY: publicKey,
  IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: 0,
  ...overrides,
});
