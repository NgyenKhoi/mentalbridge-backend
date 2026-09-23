import { config as loadDotenv } from "dotenv";
import { z } from "zod";

const nodeEnvironments = ["development", "test", "production"] as const;
const providerModes = ["DETERMINISTIC_FAKE", "APPROVED_REAL"] as const;
const realProviders = ["GEMINI", "OPENAI"] as const;
const versionPattern = /^[A-Za-z0-9._-]{1,96}$/;
const modelPattern = /^[A-Za-z0-9._:/-]{1,128}$/;
const localEncryptionKey = Buffer.alloc(32, 7).toString("base64");
const localIdempotencyKey = Buffer.alloc(32, 8).toString("base64");

const configuredRoute = <TProvider extends string>(
  provider: TProvider | undefined,
  model: string | undefined,
  inputCost: number | undefined,
  outputCost: number | undefined,
) =>
  provider !== undefined &&
  model !== undefined &&
  inputCost !== undefined &&
  outputCost !== undefined
    ? {
        provider,
        model,
        inputCostMicroUsdPerMillionTokens: inputCost,
        outputCostMicroUsdPerMillionTokens: outputCost,
      }
    : null;

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
    CI: z.enum(["true", "false"]).default("false"),
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
    JOURNAL_AI_IDEMPOTENCY_HMAC_KEY: encryptionKeySchema,
    IDENTITY_JWT_ISSUER: z.url(),
    IDENTITY_JWT_AUDIENCE: z.string().min(1),
    IDENTITY_JWT_KEY_ID: z.string().min(1),
    IDENTITY_JWT_PUBLIC_KEY: z
      .string()
      .min(1)
      .transform((value) => value.replaceAll("\\n", "\n")),
    JOURNAL_AI_CARE_BASE_URL: z.url(),
    JOURNAL_AI_CARE_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(5_000)
      .default(2_000),
    JOURNAL_AI_CONSULTATION_BASE_URL: z.url(),
    JOURNAL_AI_CONSULTATION_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(100)
      .max(5_000)
      .default(2_000),
    JOURNAL_AI_PROVIDER_MODE: z
      .enum(providerModes)
      .default("DETERMINISTIC_FAKE"),
    JOURNAL_AI_ROUTING_POLICY_VERSION: z
      .string()
      .regex(versionPattern)
      .default("exact-revision-routing-v1"),
    JOURNAL_AI_PROVIDER_APPROVAL_VERSION: z
      .string()
      .regex(versionPattern)
      .optional(),
    JOURNAL_AI_FREE_PLUS_PROVIDER: z.enum(realProviders).optional(),
    JOURNAL_AI_FREE_PLUS_MODEL: z.string().regex(modelPattern).optional(),
    JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: z.coerce
      .number()
      .int()
      .min(0)
      .max(1_000_000_000)
      .optional(),
    JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: z.coerce
      .number()
      .int()
      .min(0)
      .max(1_000_000_000)
      .optional(),
    JOURNAL_AI_PREMIUM_PROVIDER: z.enum(realProviders).optional(),
    JOURNAL_AI_PREMIUM_MODEL: z.string().regex(modelPattern).optional(),
    JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS: z.coerce
      .number()
      .int()
      .min(0)
      .max(1_000_000_000)
      .optional(),
    JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS: z.coerce
      .number()
      .int()
      .min(0)
      .max(1_000_000_000)
      .optional(),
    JOURNAL_AI_GEMINI_BASE_URL: z
      .url()
      .default("https://generativelanguage.googleapis.com"),
    JOURNAL_AI_GEMINI_API_KEY: z.string().min(1).optional(),
    JOURNAL_AI_OPENAI_BASE_URL: z.url().default("https://api.openai.com"),
    JOURNAL_AI_OPENAI_API_KEY: z.string().min(1).optional(),
    JOURNAL_AI_PROVIDER_TIMEOUT_MS: z.coerce
      .number()
      .int()
      .min(1_000)
      .max(30_000)
      .default(30_000),
    JOURNAL_AI_BENCHMARK_ENABLED: z.enum(["true", "false"]).default("false"),
    JOURNAL_AI_BENCHMARK_DATASET_PATH: z
      .string()
      .min(1)
      .default("benchmarks/datasets/exact-revision-synthetic-v1.json"),
    JOURNAL_AI_BENCHMARK_GEMINI_MODEL: z
      .string()
      .regex(modelPattern)
      .optional(),
    JOURNAL_AI_BENCHMARK_GEMINI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS:
      z.coerce.number().int().min(0).max(1_000_000_000).optional(),
    JOURNAL_AI_BENCHMARK_GEMINI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS:
      z.coerce.number().int().min(0).max(1_000_000_000).optional(),
    JOURNAL_AI_BENCHMARK_OPENAI_MODEL: z
      .string()
      .regex(modelPattern)
      .optional(),
    JOURNAL_AI_BENCHMARK_OPENAI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS:
      z.coerce.number().int().min(0).max(1_000_000_000).optional(),
    JOURNAL_AI_BENCHMARK_OPENAI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS:
      z.coerce.number().int().min(0).max(1_000_000_000).optional(),
    JOURNAL_AI_ANALYSIS_ENABLED: z.enum(["true", "false"]).optional(),
    JOURNAL_AI_ANALYSIS_POLL_INTERVAL_MS: z.coerce
      .number()
      .int()
      .min(25)
      .max(10_000)
      .default(250),
    JOURNAL_AI_ANALYSIS_LEASE_MS: z.coerce
      .number()
      .int()
      .min(31_000)
      .max(120_000)
      .default(35_000),
    JOURNAL_AI_CHAT_RETENTION_DAYS: z.coerce
      .number()
      .int()
      .min(1)
      .max(365)
      .default(90),
    JOURNAL_AI_CHAT_FREE_DAILY_ANSWERS: z.coerce
      .number()
      .int()
      .min(1)
      .max(100)
      .default(5),
    JOURNAL_AI_CHAT_PLUS_DAILY_ANSWERS: z.coerce
      .number()
      .int()
      .min(1)
      .max(500)
      .default(30),
    JOURNAL_AI_CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS: z.coerce
      .number()
      .int()
      .min(1)
      .max(2_000)
      .default(200),
    JOURNAL_AI_CHAT_RATE_LIMIT_PER_MINUTE: z.coerce
      .number()
      .int()
      .min(1)
      .max(60)
      .default(10),
    JOURNAL_AI_CHAT_DAILY_TOKEN_BUDGET: z.coerce
      .number()
      .int()
      .min(1_000)
      .max(10_000_000)
      .default(100_000),
    JOURNAL_AI_CHAT_DEFAULT_TIMEZONE: z
      .string()
      .min(1)
      .max(64)
      .default("Asia/Ho_Chi_Minh"),
    JOURNAL_AI_CHAT_ROUTING_POLICY_VERSION: z
      .string()
      .regex(versionPattern)
      .default("companion-chat-routing-v1"),
  })
  .superRefine((environment, context) => {
    if (
      environment.NODE_ENV === "production" &&
      environment.JOURNAL_AI_ENCRYPTION_KEY.equals(
        environment.JOURNAL_AI_IDEMPOTENCY_HMAC_KEY,
      )
    )
      context.addIssue({
        code: "custom",
        path: ["JOURNAL_AI_IDEMPOTENCY_HMAC_KEY"],
        message:
          "Production journal encryption and idempotency HMAC keys must be different",
      });
    if (
      (environment.NODE_ENV === "test" || environment.CI === "true") &&
      environment.JOURNAL_AI_PROVIDER_MODE !== "DETERMINISTIC_FAKE"
    )
      context.addIssue({
        code: "custom",
        path: ["JOURNAL_AI_PROVIDER_MODE"],
        message: "Test and CI must use the deterministic fake provider",
      });
    if (environment.JOURNAL_AI_PROVIDER_MODE === "APPROVED_REAL") {
      const required = [
        "JOURNAL_AI_PROVIDER_APPROVAL_VERSION",
        "JOURNAL_AI_FREE_PLUS_PROVIDER",
        "JOURNAL_AI_FREE_PLUS_MODEL",
        "JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
        "JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
        "JOURNAL_AI_PREMIUM_PROVIDER",
        "JOURNAL_AI_PREMIUM_MODEL",
        "JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
        "JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
      ] as const;
      for (const key of required) {
        if (environment[key] === undefined)
          context.addIssue({
            code: "custom",
            path: [key],
            message: `${key} is required for approved real-provider routing`,
          });
      }
      const selected = new Set([
        environment.JOURNAL_AI_FREE_PLUS_PROVIDER,
        environment.JOURNAL_AI_PREMIUM_PROVIDER,
      ]);
      if (selected.has("GEMINI") && !environment.JOURNAL_AI_GEMINI_API_KEY)
        context.addIssue({
          code: "custom",
          path: ["JOURNAL_AI_GEMINI_API_KEY"],
          message: "Gemini credentials are required by the approved route",
        });
      if (selected.has("OPENAI") && !environment.JOURNAL_AI_OPENAI_API_KEY)
        context.addIssue({
          code: "custom",
          path: ["JOURNAL_AI_OPENAI_API_KEY"],
          message: "OpenAI credentials are required by the approved route",
        });
    }
    if (environment.JOURNAL_AI_BENCHMARK_ENABLED === "true") {
      if (environment.NODE_ENV === "test" || environment.CI === "true")
        context.addIssue({
          code: "custom",
          path: ["JOURNAL_AI_BENCHMARK_ENABLED"],
          message: "Paid benchmark calls are disabled in test and CI",
        });

      const benchmarkCandidates = [
        {
          name: "Gemini",
          credential: "JOURNAL_AI_GEMINI_API_KEY",
          routeKeys: [
            "JOURNAL_AI_BENCHMARK_GEMINI_MODEL",
            "JOURNAL_AI_BENCHMARK_GEMINI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
            "JOURNAL_AI_BENCHMARK_GEMINI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
          ],
        },
        {
          name: "OpenAI",
          credential: "JOURNAL_AI_OPENAI_API_KEY",
          routeKeys: [
            "JOURNAL_AI_BENCHMARK_OPENAI_MODEL",
            "JOURNAL_AI_BENCHMARK_OPENAI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
            "JOURNAL_AI_BENCHMARK_OPENAI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS",
          ],
        },
      ] as const;

      let completeCandidateCount = 0;

      for (const candidate of benchmarkCandidates) {
        const routeConfigured = candidate.routeKeys.every(
          (key) => environment[key] !== undefined,
        );

        const routePartiallyConfigured = candidate.routeKeys.some(
          (key) => environment[key] !== undefined,
        );

        if (routePartiallyConfigured && !routeConfigured) {
          for (const key of candidate.routeKeys) {
            if (environment[key] === undefined) {
              context.addIssue({
                code: "custom",
                path: [key],
                message: `${key} is required when the ${candidate.name} benchmark candidate is configured`,
              });
            }
          }
        }

        if (routeConfigured) {
          if (!environment[candidate.credential]) {
            context.addIssue({
              code: "custom",
              path: [candidate.credential],
              message: `${candidate.name} credentials are required for the configured benchmark candidate`,
            });
          } else {
            completeCandidateCount += 1;
          }
        }
      }

      if (completeCandidateCount === 0) {
        context.addIssue({
          code: "custom",
          path: ["JOURNAL_AI_BENCHMARK_ENABLED"],
          message:
            "At least one complete Gemini or OpenAI benchmark candidate is required",
        });
      }
    }
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
    JOURNAL_ENCRYPTION_KEY_ID: "single-key" as const,
    JOURNAL_IDEMPOTENCY_HMAC_KEY: environment.JOURNAL_AI_IDEMPOTENCY_HMAC_KEY,
    IDENTITY_JWT_ISSUER: environment.IDENTITY_JWT_ISSUER,
    IDENTITY_JWT_AUDIENCE: environment.IDENTITY_JWT_AUDIENCE,
    IDENTITY_JWT_KEY_ID: environment.IDENTITY_JWT_KEY_ID,
    IDENTITY_JWT_PUBLIC_KEY: environment.IDENTITY_JWT_PUBLIC_KEY,
    CARE_BASE_URL: environment.JOURNAL_AI_CARE_BASE_URL,
    CARE_TIMEOUT_MS: environment.JOURNAL_AI_CARE_TIMEOUT_MS,
    CONSULTATION_BASE_URL: environment.JOURNAL_AI_CONSULTATION_BASE_URL,
    CONSULTATION_TIMEOUT_MS: environment.JOURNAL_AI_CONSULTATION_TIMEOUT_MS,
    PROVIDER_MODE: environment.JOURNAL_AI_PROVIDER_MODE,
    ROUTING_POLICY_VERSION: environment.JOURNAL_AI_ROUTING_POLICY_VERSION,
    PROVIDER_APPROVAL_VERSION:
      environment.JOURNAL_AI_PROVIDER_APPROVAL_VERSION ?? null,
    FREE_PLUS_ROUTE: configuredRoute(
      environment.JOURNAL_AI_FREE_PLUS_PROVIDER,
      environment.JOURNAL_AI_FREE_PLUS_MODEL,
      environment.JOURNAL_AI_FREE_PLUS_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
      environment.JOURNAL_AI_FREE_PLUS_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
    ),
    PREMIUM_ROUTE: configuredRoute(
      environment.JOURNAL_AI_PREMIUM_PROVIDER,
      environment.JOURNAL_AI_PREMIUM_MODEL,
      environment.JOURNAL_AI_PREMIUM_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
      environment.JOURNAL_AI_PREMIUM_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
    ),
    GEMINI_BASE_URL: environment.JOURNAL_AI_GEMINI_BASE_URL,
    GEMINI_API_KEY: environment.JOURNAL_AI_GEMINI_API_KEY ?? null,
    OPENAI_BASE_URL: environment.JOURNAL_AI_OPENAI_BASE_URL,
    OPENAI_API_KEY: environment.JOURNAL_AI_OPENAI_API_KEY ?? null,
    PROVIDER_TIMEOUT_MS: environment.JOURNAL_AI_PROVIDER_TIMEOUT_MS,
    BENCHMARK_ENABLED: environment.JOURNAL_AI_BENCHMARK_ENABLED === "true",
    BENCHMARK_DATASET_PATH: environment.JOURNAL_AI_BENCHMARK_DATASET_PATH,
    BENCHMARK_GEMINI_ROUTE: configuredRoute(
      "GEMINI",
      environment.JOURNAL_AI_BENCHMARK_GEMINI_MODEL,
      environment.JOURNAL_AI_BENCHMARK_GEMINI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
      environment.JOURNAL_AI_BENCHMARK_GEMINI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
    ),
    BENCHMARK_OPENAI_ROUTE: configuredRoute(
      "OPENAI",
      environment.JOURNAL_AI_BENCHMARK_OPENAI_MODEL,
      environment.JOURNAL_AI_BENCHMARK_OPENAI_INPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
      environment.JOURNAL_AI_BENCHMARK_OPENAI_OUTPUT_COST_MICRO_USD_PER_MILLION_TOKENS,
    ),
    ANALYSIS_ENABLED:
      environment.JOURNAL_AI_ANALYSIS_ENABLED !== undefined
        ? environment.JOURNAL_AI_ANALYSIS_ENABLED === "true"
        : environment.NODE_ENV !== "production",
    ANALYSIS_POLL_INTERVAL_MS: environment.JOURNAL_AI_ANALYSIS_POLL_INTERVAL_MS,
    ANALYSIS_LEASE_MS: environment.JOURNAL_AI_ANALYSIS_LEASE_MS,
    CHAT_RETENTION_DAYS: environment.JOURNAL_AI_CHAT_RETENTION_DAYS,
    CHAT_FREE_DAILY_ANSWERS: environment.JOURNAL_AI_CHAT_FREE_DAILY_ANSWERS,
    CHAT_PLUS_DAILY_ANSWERS: environment.JOURNAL_AI_CHAT_PLUS_DAILY_ANSWERS,
    CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS:
      environment.JOURNAL_AI_CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS,
    CHAT_RATE_LIMIT_PER_MINUTE:
      environment.JOURNAL_AI_CHAT_RATE_LIMIT_PER_MINUTE,
    CHAT_DAILY_TOKEN_BUDGET: environment.JOURNAL_AI_CHAT_DAILY_TOKEN_BUDGET,
    CHAT_DEFAULT_TIMEZONE: environment.JOURNAL_AI_CHAT_DEFAULT_TIMEZONE,
    CHAT_ROUTING_POLICY_VERSION:
      environment.JOURNAL_AI_CHAT_ROUTING_POLICY_VERSION,
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
            environment.JOURNAL_AI_IDEMPOTENCY_HMAC_KEY ?? localIdempotencyKey,
          JOURNAL_AI_CARE_BASE_URL:
            environment.JOURNAL_AI_CARE_BASE_URL ?? "http://localhost:8081",
          JOURNAL_AI_CONSULTATION_BASE_URL:
            environment.JOURNAL_AI_CONSULTATION_BASE_URL ??
            "http://localhost:8082",
        }),
  };

  return environmentSchema.parse(environmentWithLocalDefaults);
};
