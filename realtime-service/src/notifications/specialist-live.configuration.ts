import { z } from 'zod';

const schema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    REALTIME_SPECIALIST_LIVE_ENABLED: z.enum(['true', 'false']).default('false'),
    CONTENT_NOTIFICATION_SERVICE_URL: z.string().default(''),
    CONTENT_NOTIFICATION_TIMEOUT_MS: z.coerce.number().int().min(100).max(5000).default(2000),
    KAFKA_BOOTSTRAP_SERVERS: z.string().default(''),
  })
  .superRefine((value, context) => {
    if (value.REALTIME_SPECIALIST_LIVE_ENABLED !== 'true') return;
    if (!value.KAFKA_BOOTSTRAP_SERVERS.split(',').some((entry) => entry.trim()))
      context.addIssue({
        code: 'custom',
        path: ['KAFKA_BOOTSTRAP_SERVERS'],
        message: 'Required for specialist live notifications',
      });
    try {
      const url = new URL(value.CONTENT_NOTIFICATION_SERVICE_URL);
      if (
        !['http:', 'https:'].includes(url.protocol) ||
        url.username ||
        url.password ||
        url.search ||
        url.hash ||
        url.pathname !== '/'
      )
        throw new Error('Invalid owner URL');
      if (
        value.NODE_ENV === 'production' &&
        ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
      )
        throw new Error('Invalid production owner URL');
    } catch {
      context.addIssue({
        code: 'custom',
        path: ['CONTENT_NOTIFICATION_SERVICE_URL'],
        message: 'A plain HTTP(S) Content owner base URL is required',
      });
    }
  })
  .transform((value) => ({
    enabled: value.REALTIME_SPECIALIST_LIVE_ENABLED === 'true',
    baseUrl: value.CONTENT_NOTIFICATION_SERVICE_URL,
    timeoutMs: value.CONTENT_NOTIFICATION_TIMEOUT_MS,
    brokers: value.KAFKA_BOOTSTRAP_SERVERS.split(',')
      .map((entry) => entry.trim())
      .filter(Boolean),
  }));

export const specialistLiveConfiguration = (environment: NodeJS.ProcessEnv = {}) =>
  schema.parse(environment);
export type SpecialistLiveConfiguration = z.infer<typeof schema>;
