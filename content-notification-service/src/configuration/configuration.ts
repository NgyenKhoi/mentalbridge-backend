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
    JOURNAL_AI_SERVICE_URL: z.url().default('http://localhost:3005'),
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
    KAFKA_BOOTSTRAP_SERVERS: z.string().min(1).optional(),
    CONTENT_ACCOUNT_LIFECYCLE_CONSUMER_ENABLED: z.enum(['true', 'false']).optional(),
    E2E_TEST_MODE: z.coerce.boolean().default(false),
    E2E_TEST_SECRET: z.string().min(16).optional(),
  })
  .superRefine((environment, context) => {
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
  })
  .transform((environment) => ({
    ...environment,
    SERVICE_NAME: 'content-notification-service' as const,
    REMINDER_SCHEDULER_ENABLED: environment.REMINDER_SCHEDULER_ENABLED === 'true',
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
