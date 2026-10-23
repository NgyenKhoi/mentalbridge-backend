import { config as loadDotenv } from 'dotenv';
import { z } from 'zod';

const environmentSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    PORT: z.coerce.number().int().min(1).max(65_535).default(3003),
    DATABASE_URL: z.url(),
    DB_POOL_MAX: z.coerce.number().int().min(1).default(10),
    DB_IDLE_TIMEOUT_MS: z.coerce.number().int().min(0).default(30_000),
    DB_CONNECT_TIMEOUT_MS: z.coerce.number().int().min(0).default(2_000),
    LOG_LEVEL: z
      .enum(['trace', 'debug', 'info', 'warn', 'error', 'fatal', 'silent'])
      .default('info'),
    CORS_ORIGINS: z.string().default(''),
    // JWT — Identity Service contract
    IDENTITY_JWT_ISSUER: z.string().min(1),
    IDENTITY_JWT_AUDIENCE: z.string().min(1),
    IDENTITY_JWT_PUBLIC_KEY: z.string().min(1),
    IDENTITY_JWT_KEY_ID: z.string().min(1).optional(),
    IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: z.coerce.number().int().min(0).default(60),
    RESOURCE_REVIEWER_ACCOUNT_IDS: z
      .string()
      .refine(
        (value) =>
          value.trim() === '' ||
          value.split(',').every((accountId) => z.uuid().safeParse(accountId.trim()).success),
      )
      .optional(),
    JOURNAL_AI_SERVICE_URL: z.url().default('http://localhost:3005'),
    CONTENT_CARE_BASE_URL: z.url().optional(),
    CONTENT_CARE_TIMEOUT_MS: z.coerce.number().int().min(100).max(5_000).optional(),
    JOURNAL_AI_SERVICE_TIMEOUT_MS: z.coerce.number().int().min(100).max(5_000).default(2_000),
    JOURNAL_AI_REMINDER_SERVICE_TOKEN: z.string().min(32).optional(),
    REMINDER_SCHEDULER_ENABLED: z.enum(['true', 'false']).default('false'),
    REMINDER_SCHEDULER_INTERVAL_MS: z.coerce
      .number()
      .int()
      .min(10_000)
      .max(3_600_000)
      .default(60_000),
    REMINDER_SCHEDULER_BATCH_SIZE: z.coerce.number().int().min(1).max(500).default(100),
    WELLBEING_DIGEST_SCHEDULER_ENABLED: z.enum(['true', 'false']).default('false'),
    WELLBEING_DIGEST_SCHEDULER_INTERVAL_MS: z.coerce
      .number()
      .int()
      .min(10_000)
      .max(3_600_000)
      .default(60_000),
    WELLBEING_DIGEST_BATCH_SIZE: z.coerce.number().int().min(1).max(500).default(100),
    IDENTITY_SERVICE_URL: z.url().default('http://localhost:8080'),
    IDENTITY_NOTIFICATION_SERVICE_TOKEN: z.string().min(32).optional(),
    BREVO_BASE_URL: z.url().default('https://api.brevo.com'),
    BREVO_API_KEY: z.string().min(1).optional(),
    BREVO_SENDER_EMAIL: z.email().optional(),
    BREVO_SENDER_NAME: z.string().trim().min(1).max(80).default('MentalBridge'),
    KAFKA_BOOTSTRAP_SERVERS: z.string().min(1).optional(),
    CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: z.enum(['true', 'false']).optional(),
    APPOINTMENT_REMINDER_ENABLED: z.enum(['true', 'false']).default('false'),
    APPOINTMENT_REMINDER_SERVICE_TOKEN: z.string().min(32).optional(),
    CONSULTATION_SERVICE_URL: z.url().default('http://localhost:8082'),
    APPOINTMENT_REMINDER_HTTP_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(5_000)
      .default(2_000),
    CONTENT_APPOINTMENT_CONSUMER_ENABLED: z.enum(['true', 'false']).default('false'),
    CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED: z.enum(['true', 'false']).default('false'),
    CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED: z.enum(['true', 'false']).default('false'),
    COMMUNITY_SERVICE_URL: z.url().default('http://localhost:8084'),
    CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY: z.string().optional(),
    CONTENT_COMMUNITY_NOTIFICATION_KEY_ID: z.string().optional(),
    CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED: z.enum(['true', 'false']).default('false'),
    CONTENT_PUSH_DELIVERY_ENABLED: z.enum(['true', 'false']).default('false'),
    CONTENT_PUSH_DELIVERY_INTERVAL_MS: z.coerce
      .number()
      .int()
      .min(10_000)
      .max(3_600_000)
      .default(60_000),
    CONTENT_PUSH_DELIVERY_BATCH_SIZE: z.coerce.number().int().min(1).max(100).default(50),
    CONTENT_PUSH_PROVIDER_TIMEOUT_MS: z.coerce.number().int().min(500).max(10_000).default(3_000),
    EXPO_PUSH_BASE_URL: z.url().default('https://exp.host'),
    EXPO_ACCESS_TOKEN: z.string().min(1).optional(),
    CONTENT_PUSH_TOKEN_ENCRYPTION_KEY: z.string().optional(),
    APPOINTMENT_REMINDER_APP_URL: z.url().default('http://localhost:3000/appointments'),
    E2E_TEST_MODE: z.coerce.boolean().default(false),
    E2E_TEST_SECRET: z.string().min(16).optional(),
  })
  .superRefine((environment, context) => {
    if (environment.CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED === 'true') {
      for (const key of [
        'KAFKA_BOOTSTRAP_SERVERS',
        'CONTENT_COMMUNITY_NOTIFICATION_PRIVATE_KEY',
        'CONTENT_COMMUNITY_NOTIFICATION_KEY_ID',
      ] as const) {
        if (!environment[key]?.trim())
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'is required when Community notice delivery is enabled',
          });
      }
    }
    if (
      environment.REMINDER_SCHEDULER_ENABLED === 'true' &&
      !environment.JOURNAL_AI_REMINDER_SERVICE_TOKEN
    ) {
      context.addIssue({
        code: 'custom',
        path: ['JOURNAL_AI_REMINDER_SERVICE_TOKEN'],
        message: 'is required when the reminder scheduler is enabled',
      });
    }
    if (environment.WELLBEING_DIGEST_SCHEDULER_ENABLED === 'true') {
      for (const key of ['BREVO_API_KEY', 'BREVO_SENDER_EMAIL'] as const) {
        if (!environment[key]) {
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'is required when the wellbeing digest scheduler is enabled',
          });
        }
      }
    }
    if (
      environment.CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED === 'true' &&
      !environment.KAFKA_BOOTSTRAP_SERVERS
    ) {
      context.addIssue({
        code: 'custom',
        path: ['KAFKA_BOOTSTRAP_SERVERS'],
        message: 'is required when the account lifecycle consumer is enabled',
      });
    }
    if (environment.APPOINTMENT_REMINDER_ENABLED === 'true') {
      for (const key of [
        'APPOINTMENT_REMINDER_SERVICE_TOKEN',
        'BREVO_API_KEY',
        'BREVO_SENDER_EMAIL',
      ] as const) {
        if (!environment[key]) {
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'is required when appointment reminders are enabled',
          });
        }
      }
    }
    if (
      environment.CONTENT_APPOINTMENT_CONSUMER_ENABLED === 'true' &&
      !environment.KAFKA_BOOTSTRAP_SERVERS
    ) {
      context.addIssue({
        code: 'custom',
        path: ['KAFKA_BOOTSTRAP_SERVERS'],
        message: 'is required when the appointment consumer is enabled',
      });
    }
    if (
      environment.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED === 'true' &&
      !environment.KAFKA_BOOTSTRAP_SERVERS
    ) {
      context.addIssue({
        code: 'custom',
        path: ['KAFKA_BOOTSTRAP_SERVERS'],
        message: 'is required when the Community interaction consumer is enabled',
      });
    }
    if (
      environment.CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED === 'true' &&
      !environment.KAFKA_BOOTSTRAP_SERVERS
    ) {
      context.addIssue({
        code: 'custom',
        path: ['KAFKA_BOOTSTRAP_SERVERS'],
        message: 'is required when the Care user notification consumer is enabled',
      });
    }
    if (environment.CONTENT_PUSH_DELIVERY_ENABLED === 'true') {
      for (const key of ['EXPO_ACCESS_TOKEN', 'CONTENT_PUSH_TOKEN_ENCRYPTION_KEY'] as const) {
        if (!environment[key]) {
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'is required when native push delivery is enabled',
          });
        }
      }
    }
    if (
      environment.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY &&
      Buffer.from(environment.CONTENT_PUSH_TOKEN_ENCRYPTION_KEY, 'base64').length !== 32
    ) {
      context.addIssue({
        code: 'custom',
        path: ['CONTENT_PUSH_TOKEN_ENCRYPTION_KEY'],
        message: 'must be a base64-encoded 32-byte key',
      });
    }
  })
  .transform((environment) => ({
    ...environment,
    SERVICE_NAME: 'content-notification-service' as const,
    REMINDER_SCHEDULER_ENABLED: environment.REMINDER_SCHEDULER_ENABLED === 'true',
    WELLBEING_DIGEST_SCHEDULER_ENABLED: environment.WELLBEING_DIGEST_SCHEDULER_ENABLED === 'true',
    APPOINTMENT_REMINDER_ENABLED: environment.APPOINTMENT_REMINDER_ENABLED === 'true',
    CONTENT_APPOINTMENT_CONSUMER_ENABLED:
      environment.CONTENT_APPOINTMENT_CONSUMER_ENABLED === 'true',
    CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED:
      environment.CONTENT_COMMUNITY_INTERACTION_CONSUMER_ENABLED === 'true',
    CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED:
      environment.CONTENT_COMMUNITY_NOTICE_CONSUMER_ENABLED === 'true',
    CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED:
      environment.CONTENT_CARE_USER_NOTIFICATION_CONSUMER_ENABLED === 'true',
    CONTENT_PUSH_DELIVERY_ENABLED: environment.CONTENT_PUSH_DELIVERY_ENABLED === 'true',
    ALLOWED_ORIGINS: environment.CORS_ORIGINS.split(',')
      .map((origin) => origin.trim())
      .filter(Boolean),
  }));

export type ServiceConfiguration = z.infer<typeof environmentSchema>;

export const loadConfiguration = (
  environment: NodeJS.ProcessEnv = process.env,
): ServiceConfiguration => {
  if ((environment.NODE_ENV ?? 'development') === 'development') {
    loadDotenv({ override: false, quiet: true });
  }

  return environmentSchema.parse(environment);
};
