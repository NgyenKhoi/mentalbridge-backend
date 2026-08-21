import { z } from 'zod';

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3003),

  DB_HOST: z.string().min(1),
  DB_PORT: z.coerce.number().int().min(1).max(65535).default(5432),
  DB_NAME: z.string().min(1).default('mentalbridge_content_notification'),
  DB_USER: z.string().min(1),
  DB_PASSWORD: z.string().min(1),
  DB_POOL_MAX: z.coerce.number().int().min(1).default(10),
  DB_IDLE_TIMEOUT_MS: z.coerce.number().int().min(0).default(30000),
  DB_CONNECT_TIMEOUT_MS: z.coerce.number().int().min(0).default(2000),

  LOG_LEVEL: z.enum(['trace', 'debug', 'info', 'warn', 'error', 'fatal']).default('info'),

  CORS_ORIGINS: z.string().optional(),
});

// Only load .env in local development — never in production/test containers
if (process.env.NODE_ENV !== 'production' && process.env.NODE_ENV !== 'test') {
  const dotenv = await import('dotenv');
  dotenv.config();
}

function loadConfig() {
  const result = schema.safeParse(process.env);
  if (!result.success) {
    const issues = result.error.issues.map((i) => `  ${i.path.join('.')}: ${i.message}`).join('\n');
    throw new Error(`Invalid configuration:\n${issues}`);
  }
  return result.data;
}

export const config = loadConfig();
export type Config = typeof config;
