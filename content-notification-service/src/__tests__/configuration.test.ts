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
    expect(configuration.REMINDER_SCHEDULER_ENABLED).toBe(false);
    expect(configuration.REMINDER_SCHEDULER_INTERVAL_MS).toBe(60_000);
    expect(configuration.REMINDER_SCHEDULER_BATCH_SIZE).toBe(100);
    expect(configuration.CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED).toBeUndefined();
    expect(configuration.WELLBEING_DIGEST_SCHEDULER_ENABLED).toBe(false);
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

  it('requires a scoped service token when the reminder scheduler is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        REMINDER_SCHEDULER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      REMINDER_SCHEDULER_ENABLED: 'true',
      JOURNAL_AI_REMINDER_SERVICE_TOKEN: 'test-reminder-service-token-at-least-32-characters',
    });

    expect(configuration.REMINDER_SCHEDULER_ENABLED).toBe(true);
  });

  it('requires Kafka brokers when the account lifecycle consumer is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: 'true',
      KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
    });

    expect(configuration.CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED).toBe('true');
  });

  it('requires scoped contact and Brevo configuration when wellbeing email is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        WELLBEING_DIGEST_SCHEDULER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      WELLBEING_DIGEST_SCHEDULER_ENABLED: 'true',
      IDENTITY_NOTIFICATION_SERVICE_TOKEN: 'test-notification-service-token-at-least-32-characters',
      BREVO_API_KEY: 'test-key',
      BREVO_SENDER_EMAIL: 'no-reply@example.test',
    });
    expect(configuration.WELLBEING_DIGEST_SCHEDULER_ENABLED).toBe(true);
  });
});
