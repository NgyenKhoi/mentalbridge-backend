import { z } from 'zod';

const schema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    REALTIME_COMMUNITY_ENABLED: z.enum(['true', 'false']).default('false'),
    REALTIME_COMMUNITY_BASE_URL: z.url().default('http://localhost:8084'),
    REALTIME_COMMUNITY_DELEGATION_PRIVATE_KEY: z.string().default(''),
    REALTIME_COMMUNITY_DELEGATION_KEY_ID: z.string().default(''),
    REALTIME_COMMUNITY_BFF_PUBLIC_KEY: z.string().default(''),
    REALTIME_COMMUNITY_BFF_KEY_ID: z.string().default(''),
    KAFKA_BOOTSTRAP_SERVERS: z.string().default(''),
    REALTIME_COMMUNITY_KAFKA_GROUP: z.string().min(1).default('realtime-community-v1'),
    REALTIME_COMMUNITY_REDIS_CHANNEL: z
      .string()
      .min(1)
      .max(200)
      .default('mentalbridge:community:v1:fanout'),
  })
  .superRefine((value, context) => {
    if (value.REALTIME_COMMUNITY_ENABLED === 'true') {
      if (
        value.NODE_ENV === 'production' &&
        ['localhost', '127.0.0.1', '[::1]'].includes(
          new URL(value.REALTIME_COMMUNITY_BASE_URL).hostname,
        )
      )
        context.addIssue({
          code: 'custom',
          path: ['REALTIME_COMMUNITY_BASE_URL'],
          message: 'Production Community owner cannot use localhost',
        });
      for (const key of [
        'REALTIME_COMMUNITY_DELEGATION_PRIVATE_KEY',
        'REALTIME_COMMUNITY_DELEGATION_KEY_ID',
        'REALTIME_COMMUNITY_BFF_PUBLIC_KEY',
        'REALTIME_COMMUNITY_BFF_KEY_ID',
        'KAFKA_BOOTSTRAP_SERVERS',
      ] as const) {
        if (!value[key].trim())
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'Required for Community realtime',
          });
      }
    }
  })
  .transform((value) => ({
    enabled: value.REALTIME_COMMUNITY_ENABLED === 'true',
    baseUrl: value.REALTIME_COMMUNITY_BASE_URL,
    privateKey: value.REALTIME_COMMUNITY_DELEGATION_PRIVATE_KEY.replaceAll('\\n', '\n'),
    keyId: value.REALTIME_COMMUNITY_DELEGATION_KEY_ID,
    bffPublicKey: value.REALTIME_COMMUNITY_BFF_PUBLIC_KEY.replaceAll('\\n', '\n'),
    bffKeyId: value.REALTIME_COMMUNITY_BFF_KEY_ID,
    brokers: value.KAFKA_BOOTSTRAP_SERVERS.split(',')
      .map((v) => v.trim())
      .filter(Boolean),
    groupId: value.REALTIME_COMMUNITY_KAFKA_GROUP,
    channel: value.REALTIME_COMMUNITY_REDIS_CHANNEL,
  }));

export const communityConfiguration = (environment: NodeJS.ProcessEnv = {}) =>
  schema.parse(environment);
export type CommunityConfiguration = z.infer<typeof schema>;
