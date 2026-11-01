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
  it('requires dedicated signing configuration and Kafka only when Community notice intake is enabled', () => {
    expect(loadConfiguration(requiredEnvironment).CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED).toBe(
      false,
    );
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: 'true',
      }),
    ).toThrow();
    expect(
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: 'true',
        KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
        CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY: 'synthetic-key-validated-at-client-startup',
        CONTENT_COMMUNITY_NOTIFICATION_KEY_ID: 'content-test',
      }).CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED,
    ).toBe(true);
  });
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
    expect(configuration.APPOINTMENT_REMINDER_ENABLED).toBe(false);
    expect(configuration.CONTENT_APPOINTMENT_CONSUMER_ENABLED).toBe(false);
    expect(configuration.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED).toBe(false);
    expect(configuration.CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED).toBe(false);
    expect(configuration.CONSULTATION_SERVICE_URL).toBe('http://localhost:8082');
    expect(configuration.IDENTITY_SERVICE_URL).toBe('http://localhost:8080');
    expect(configuration.CONTENT_PUSH_DELIVERY_ENABLED).toBe(false);
    expect(configuration.CONTENT_PUSH_RECEIPT_INITIAL_DELAY_SECONDS).toBe(900);
    expect(configuration.CONTENT_PUSH_RECEIPT_RETRY_DELAY_SECONDS).toBe(300);
    expect(configuration.CONTENT_PUSH_DEVICE_RATE_LIMIT).toBe(30);
    expect(configuration.CONTENT_PUSH_DEVICE_RATE_WINDOW_SECONDS).toBe(60);
  });

  it('normalizes configured CORS origins', () => {
    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CORS_ORIGINS: 'https://admin.example, https://app.example',
    });

    expect(configuration.ALLOWED_ORIGINS).toEqual(['https://admin.example', 'https://app.example']);
  });

  it('validates the configured resource review authority identifiers', () => {
    const configuration = loadConfiguration({
      ...requiredEnvironment,
      RESOURCE_REVIEWER_ACCOUNT_IDS:
        'a13e4567-e89b-42d3-a456-426614174000, b13e4567-e89b-42d3-a456-426614174000',
    });
    expect(configuration.RESOURCE_REVIEWER_ACCOUNT_IDS).toContain(',');

    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        RESOURCE_REVIEWER_ACCOUNT_IDS: 'not-an-account-id',
      }),
    ).toThrow();
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

  it('requires scoped delivery credentials when appointment reminders are enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        APPOINTMENT_REMINDER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      APPOINTMENT_REMINDER_ENABLED: 'true',
      APPOINTMENT_REMINDER_SERVICE_TOKEN: 'appointment-reminder-token-at-least-32-characters',
      BREVO_API_KEY: 'synthetic-provider-key',
      BREVO_SENDER_EMAIL: 'no-reply@example.test',
    });

    expect(configuration.APPOINTMENT_REMINDER_ENABLED).toBe(true);
  });

  it('requires Kafka brokers when the appointment consumer is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_APPOINTMENT_CONSUMER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CONTENT_APPOINTMENT_CONSUMER_ENABLED: 'true',
      KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
    });

    expect(configuration.CONTENT_APPOINTMENT_CONSUMER_ENABLED).toBe(true);
  });

  it('requires Kafka brokers when the Community interaction consumer is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED: 'true',
      }),
    ).toThrow();

    const configuration = loadConfiguration({
      ...requiredEnvironment,
      CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED: 'true',
      KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
    });

    expect(configuration.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED).toBe(true);
  });

  it('requires push credentials and a valid encryption key when delivery is enabled', () => {
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: 'not-a-key',
      }),
    ).toThrow();
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_PUSH_DELIVERY_ENABLED: 'true',
      }),
    ).toThrow();
    expect(() =>
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_PUSH_DELIVERY_ENABLED: 'true',
        EXPO_ACCESS_TOKEN: 'synthetic-token',
        CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: 'not-a-key',
      }),
    ).toThrow();
    expect(
      loadConfiguration({
        ...requiredEnvironment,
        CONTENT_PUSH_DELIVERY_ENABLED: 'true',
        EXPO_ACCESS_TOKEN: 'synthetic-token',
        CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: Buffer.alloc(32, 1).toString('base64'),
      }).CONTENT_PUSH_DELIVERY_ENABLED,
    ).toBe(true);
  });
});
