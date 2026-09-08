import { config as loadDotenv } from 'dotenv';
import { z } from 'zod';

const logLevels = ['fatal', 'error', 'warn', 'info', 'debug', 'trace', 'silent'] as const;
const keyVersionPattern = /^[A-Za-z0-9._-]{1,64}$/;

const isEncodedEncryptionKey = (value: string): boolean => {
  const decoded = Buffer.from(value, 'base64');
  return decoded.length === 32 && decoded.toString('base64') === value;
};

const encodedDecryptionKeysSchema = z
  .string()
  .default('{}')
  .transform<Readonly<Record<string, string>>>((value, context) => {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value);
    } catch {
      context.addIssue({ code: 'custom', message: 'Decryption keys must be a JSON object' });
      return z.NEVER;
    }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      context.addIssue({ code: 'custom', message: 'Decryption keys must be a JSON object' });
      return z.NEVER;
    }
    const entries = Object.entries(parsed);
    if (
      entries.some(
        ([version, encodedKey]) =>
          !keyVersionPattern.test(version) ||
          typeof encodedKey !== 'string' ||
          !isEncodedEncryptionKey(encodedKey),
      )
    ) {
      context.addIssue({
        code: 'custom',
        message: 'Every decryption key must be a versioned AES-256 key',
      });
      return z.NEVER;
    }
    return Object.fromEntries(entries);
  });

const environmentSchema = z
  .object({
    NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
    REALTIME_PORT: z.coerce.number().int().min(1).max(65_535).default(3004),
    REALTIME_LOG_LEVEL: z.enum(logLevels).default('info'),
    REALTIME_CORS_ORIGINS: z.string().default(''),
    REALTIME_MONGODB_URI: z.url(),
    REALTIME_MONGODB_DATABASE: z.string().regex(/^[A-Za-z0-9_-]+$/),
    REALTIME_MONGODB_CONNECTION_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(30_000)
      .default(2000),
    REALTIME_REDIS_URL: z.url(),
    REALTIME_REDIS_CONNECTION_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(30_000)
      .default(1000),
    REALTIME_PRESENCE_TTL_SECONDS: z.coerce.number().int().min(10).max(300).default(45),
    REALTIME_PRESENCE_HEARTBEAT_SECONDS: z.coerce.number().int().min(3).max(120).default(15),
    REALTIME_MAX_CONNECTIONS_PER_ACCOUNT: z.coerce.number().int().min(1).max(20).default(5),
    REALTIME_MAX_PAYLOAD_BYTES: z.coerce.number().int().min(1024).max(65_536).default(16_384),
    REALTIME_COMMAND_RATE_LIMIT: z.coerce.number().int().min(1).max(1000).default(60),
    REALTIME_COMMAND_RATE_WINDOW_SECONDS: z.coerce.number().int().min(1).max(3600).default(60),
    REALTIME_SHUTDOWN_TIMEOUT_MS: z.coerce.number().int().min(1000).max(60_000).default(10_000),
    REALTIME_MESSAGE_ENCRYPTION_KEY: z.string().min(1),
    REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION: z.string().regex(keyVersionPattern),
    REALTIME_MESSAGE_DECRYPTION_KEYS: encodedDecryptionKeysSchema,
    IDENTITY_JWT_ISSUER: z.url(),
    IDENTITY_JWT_AUDIENCE: z.string().min(1),
    IDENTITY_JWT_KEY_ID: z.string().min(1),
    IDENTITY_JWT_PUBLIC_KEY: z
      .string()
      .min(1)
      .transform((value) => value.replaceAll('\\n', '\n')),
    IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: z.coerce.number().int().min(0).max(300).default(60),
  })
  .superRefine((environment, context) => {
    if (
      environment.REALTIME_PRESENCE_TTL_SECONDS <
      environment.REALTIME_PRESENCE_HEARTBEAT_SECONDS * 2
    ) {
      context.addIssue({
        code: 'custom',
        path: ['REALTIME_PRESENCE_TTL_SECONDS'],
        message: 'Presence TTL must be at least twice the heartbeat interval',
      });
    }
    if (!isEncodedEncryptionKey(environment.REALTIME_MESSAGE_ENCRYPTION_KEY)) {
      context.addIssue({
        code: 'custom',
        path: ['REALTIME_MESSAGE_ENCRYPTION_KEY'],
        message: 'Message encryption key must decode to exactly 32 bytes',
      });
    }
    const configuredActiveKey =
      environment.REALTIME_MESSAGE_DECRYPTION_KEYS[
        environment.REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION
      ];
    if (
      configuredActiveKey !== undefined &&
      configuredActiveKey !== environment.REALTIME_MESSAGE_ENCRYPTION_KEY
    ) {
      context.addIssue({
        code: 'custom',
        path: ['REALTIME_MESSAGE_DECRYPTION_KEYS'],
        message: 'The active key version cannot resolve to a different key',
      });
    }
    if (environment.NODE_ENV === 'production') {
      if (
        environment.REALTIME_CORS_ORIGINS.split(',').every((value) => value.trim().length === 0)
      ) {
        context.addIssue({
          code: 'custom',
          path: ['REALTIME_CORS_ORIGINS'],
          message: 'Production requires at least one explicit CORS origin',
        });
      }
      for (const [key, value] of [
        ['REALTIME_MONGODB_URI', environment.REALTIME_MONGODB_URI],
        ['REALTIME_REDIS_URL', environment.REALTIME_REDIS_URL],
      ] as const) {
        const hostname = new URL(value).hostname;
        if (hostname === 'localhost' || hostname === '127.0.0.1') {
          context.addIssue({
            code: 'custom',
            path: [key],
            message: 'Production endpoints cannot use localhost',
          });
        }
      }
    }
  })
  .transform((environment) => {
    const messageEncryptionKey = Buffer.from(environment.REALTIME_MESSAGE_ENCRYPTION_KEY, 'base64');
    const messageDecryptionKeys = Object.fromEntries(
      Object.entries(environment.REALTIME_MESSAGE_DECRYPTION_KEYS).map(([version, encodedKey]) => [
        version,
        Buffer.from(encodedKey, 'base64'),
      ]),
    );
    messageDecryptionKeys[environment.REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION] =
      messageEncryptionKey;
    return {
      NODE_ENV: environment.NODE_ENV,
      PORT: environment.REALTIME_PORT,
      LOG_LEVEL: environment.REALTIME_LOG_LEVEL,
      SERVICE_NAME: 'realtime-service' as const,
      ALLOWED_ORIGINS: environment.REALTIME_CORS_ORIGINS.split(',')
        .map((value) => value.trim())
        .filter(Boolean),
      MONGODB_URI: environment.REALTIME_MONGODB_URI,
      MONGODB_DATABASE: environment.REALTIME_MONGODB_DATABASE,
      MONGODB_CONNECTION_TIMEOUT_MS: environment.REALTIME_MONGODB_CONNECTION_TIMEOUT_MS,
      REDIS_URL: environment.REALTIME_REDIS_URL,
      REDIS_CONNECTION_TIMEOUT_MS: environment.REALTIME_REDIS_CONNECTION_TIMEOUT_MS,
      PRESENCE_TTL_SECONDS: environment.REALTIME_PRESENCE_TTL_SECONDS,
      PRESENCE_HEARTBEAT_SECONDS: environment.REALTIME_PRESENCE_HEARTBEAT_SECONDS,
      MAX_CONNECTIONS_PER_ACCOUNT: environment.REALTIME_MAX_CONNECTIONS_PER_ACCOUNT,
      MAX_PAYLOAD_BYTES: environment.REALTIME_MAX_PAYLOAD_BYTES,
      COMMAND_RATE_LIMIT: environment.REALTIME_COMMAND_RATE_LIMIT,
      COMMAND_RATE_WINDOW_SECONDS: environment.REALTIME_COMMAND_RATE_WINDOW_SECONDS,
      SHUTDOWN_TIMEOUT_MS: environment.REALTIME_SHUTDOWN_TIMEOUT_MS,
      MESSAGE_ENCRYPTION_KEY: messageEncryptionKey,
      MESSAGE_ENCRYPTION_KEY_VERSION: environment.REALTIME_MESSAGE_ENCRYPTION_KEY_VERSION,
      MESSAGE_DECRYPTION_KEYS: messageDecryptionKeys,
      IDENTITY_JWT_ISSUER: environment.IDENTITY_JWT_ISSUER,
      IDENTITY_JWT_AUDIENCE: environment.IDENTITY_JWT_AUDIENCE,
      IDENTITY_JWT_KEY_ID: environment.IDENTITY_JWT_KEY_ID,
      IDENTITY_JWT_PUBLIC_KEY: environment.IDENTITY_JWT_PUBLIC_KEY,
      IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS: environment.IDENTITY_JWT_CLOCK_TOLERANCE_SECONDS,
    };
  });

export type ServiceConfiguration = z.infer<typeof environmentSchema>;

export const loadConfiguration = (
  environment: NodeJS.ProcessEnv = process.env,
): ServiceConfiguration => {
  const nodeEnvironment = environment.NODE_ENV ?? 'development';
  if (nodeEnvironment === 'development') {
    loadDotenv({ override: false, quiet: true });
  }
  const localDefaults =
    nodeEnvironment === 'production'
      ? {}
      : {
          REALTIME_MONGODB_URI: environment.REALTIME_MONGODB_URI ?? 'mongodb://localhost:27017',
          REALTIME_MONGODB_DATABASE:
            environment.REALTIME_MONGODB_DATABASE ?? 'mentalbridge_realtime',
          REALTIME_REDIS_URL: environment.REALTIME_REDIS_URL ?? 'redis://localhost:6379',
        };
  return environmentSchema.parse({ ...environment, ...localDefaults });
};
