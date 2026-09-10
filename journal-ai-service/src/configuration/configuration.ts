import { config as loadDotenv } from "dotenv";
import { z } from "zod";

const nodeEnvironments = ["development", "test", "production"] as const;
const keyIdPattern = /^[A-Za-z0-9._-]{1,64}$/;
const localEncryptionKey = Buffer.alloc(32, 7).toString("base64");

const encryptionKeySchema = z.string().transform((value, context) => {
  const decoded = Buffer.from(value, "base64");
  if (decoded.length !== 32 || decoded.toString("base64") !== value) {
    context.addIssue({
      code: "custom",
      message:
        "Journal encryption key must be a canonical base64-encoded 32-byte key",
    });
    return z.NEVER;
  }
  return decoded;
});

const environmentSchema = z
  .object({
    NODE_ENV: z.enum(nodeEnvironments).default("development"),
    JOURNAL_AI_PORT: z.coerce.number().int().min(1).max(65_535).default(3000),
    JOURNAL_AI_LOG_LEVEL: z
      .enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"])
      .default("info"),
    JOURNAL_AI_MONGODB_URI: z.string().min(1),
    JOURNAL_AI_MONGODB_DATABASE: z.string().regex(/^[A-Za-z0-9_-]+$/),
    JOURNAL_AI_MONGODB_CONNECTION_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(30_000)
      .default(2_000),
    JOURNAL_AI_ENCRYPTION_KEY: encryptionKeySchema,
    JOURNAL_AI_ENCRYPTION_KEY_ID: z
      .string()
      .regex(keyIdPattern)
      .default("local-v1"),
    JOURNAL_AI_IDEMPOTENCY_HMAC_KEY: encryptionKeySchema,
    IDENTITY_JWT_ISSUER: z.url(),
    IDENTITY_JWT_AUDIENCE: z.string().min(1),
    IDENTITY_JWT_KEY_ID: z.string().min(1),
    IDENTITY_JWT_PUBLIC_KEY: z
      .string()
      .min(1)
      .transform((value) => value.replaceAll("\\n", "\n")),
  })
  .transform((environment) => ({
    NODE_ENV: environment.NODE_ENV,
    PORT: environment.JOURNAL_AI_PORT,
    LOG_LEVEL: environment.JOURNAL_AI_LOG_LEVEL,
    SERVICE_NAME: "journal-ai-service" as const,
    MONGODB_URI: environment.JOURNAL_AI_MONGODB_URI,
    MONGODB_DATABASE: environment.JOURNAL_AI_MONGODB_DATABASE,
    MONGODB_CONNECTION_TIMEOUT_MS:
      environment.JOURNAL_AI_MONGODB_CONNECTION_TIMEOUT_MS,
    JOURNAL_ENCRYPTION_KEY: environment.JOURNAL_AI_ENCRYPTION_KEY,
    JOURNAL_ENCRYPTION_KEY_ID: environment.JOURNAL_AI_ENCRYPTION_KEY_ID,
    JOURNAL_IDEMPOTENCY_HMAC_KEY: environment.JOURNAL_AI_IDEMPOTENCY_HMAC_KEY,
    IDENTITY_JWT_ISSUER: environment.IDENTITY_JWT_ISSUER,
    IDENTITY_JWT_AUDIENCE: environment.IDENTITY_JWT_AUDIENCE,
    IDENTITY_JWT_KEY_ID: environment.IDENTITY_JWT_KEY_ID,
    IDENTITY_JWT_PUBLIC_KEY: environment.IDENTITY_JWT_PUBLIC_KEY,
  }));

export type ServiceConfiguration = z.infer<typeof environmentSchema>;

export const loadConfiguration = (
  environment: NodeJS.ProcessEnv = process.env,
): ServiceConfiguration => {
  const nodeEnvironment = environment.NODE_ENV ?? "development";

  if (nodeEnvironment !== "production") {
    loadDotenv({ override: false, quiet: true });
  }

  const environmentWithLocalDefaults = {
    ...environment,
    ...(nodeEnvironment === "production"
      ? {}
      : {
          JOURNAL_AI_MONGODB_URI:
            environment.JOURNAL_AI_MONGODB_URI ?? "mongodb://localhost:27017",
          JOURNAL_AI_MONGODB_DATABASE:
            environment.JOURNAL_AI_MONGODB_DATABASE ??
            "mentalbridge_journal_ai",
          JOURNAL_AI_ENCRYPTION_KEY:
            environment.JOURNAL_AI_ENCRYPTION_KEY ?? localEncryptionKey,
          JOURNAL_AI_IDEMPOTENCY_HMAC_KEY:
            environment.JOURNAL_AI_IDEMPOTENCY_HMAC_KEY ?? localEncryptionKey,
        }),
  };

  return environmentSchema.parse(environmentWithLocalDefaults);
};
