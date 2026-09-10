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
  })
  .transform((environment) => ({
    ...environment,
    SERVICE_NAME: 'content-notification-service' as const,
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
