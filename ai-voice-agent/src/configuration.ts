import { z } from "zod";
import type { FailureCode } from "./diagnostics.js";

export class AgentFailure extends Error {
  constructor(
    readonly code:
      | "CONFIGURATION_INVALID"
      | "DISPATCH_INVALID"
      | "JOURNAL_UNAVAILABLE"
      | "AUTHORITY_EXPIRED"
      | "INVALID_RESPONSE"
      | "PROVIDER_FAILED",
    readonly failure?: FailureCode,
  ) {
    super(code);
  }
}
const optional = (schema: z.ZodType) =>
  z.preprocess(
    (value) => (value === "" ? undefined : value),
    schema.optional(),
  );
const secret = z.string().regex(/^[\x21-\x7e]{32,256}$/);
const environmentSchema = z.object({
  AI_VOICE_AGENT_ENABLED: z.enum(["true", "false"]).default("false"),
  AI_VOICE_PILOT: z.enum(["true", "false"]).default("false"),
  AI_VOICE_AGENT_APPROVAL_VERSION: optional(
    z.string().regex(/^[a-z0-9][a-z0-9._-]{0,127}$/),
  ),
  LIVEKIT_URL: optional(
    z.url().refine((value) => {
      const url = new URL(value);
      return (
        url.protocol === "wss:" &&
        url.hostname.endsWith(".livekit.cloud") &&
        !url.username &&
        !url.password &&
        !url.search &&
        !url.hash &&
        !url.port &&
        url.pathname === "/"
      );
    }),
  ),
  LIVEKIT_API_KEY: optional(z.string().regex(/^[a-zA-Z0-9_-]{8,128}$/)),
  LIVEKIT_API_SECRET: optional(secret),
  AI_VOICE_JOURNAL_BASE_URL: z
    .url()
    .default("http://127.0.0.1:3002")
    .refine((value) => {
      const url = new URL(value);
      return (
        (url.protocol === "https:" ||
          (url.protocol === "http:" &&
            ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname))) &&
        !url.username &&
        !url.password &&
        !url.search &&
        !url.hash &&
        url.pathname === "/"
      );
    }),
  AI_VOICE_JOURNAL_SERVICE_TOKEN: optional(secret),
  AI_VOICE_STT_MODEL: z
    .enum(["deepgram/nova-3", "deepgram/nova-2"])
    .default("deepgram/nova-3"),
  AI_VOICE_TTS_MODEL: z
    .literal("cartesia/sonic-3-2026-01-12")
    .default("cartesia/sonic-3-2026-01-12"),
  AI_VOICE_TTS_VOICE_ID: optional(z.uuid()),
});
export interface AgentConfiguration {
  pilot?: boolean;
  enabled: boolean;
  approvalVersion: string | null;
  livekitUrl: string | null;
  apiKey: string | null;
  apiSecret: string | null;
  journalUrl: string;
  journalToken: string | null;
  sttModel: "deepgram/nova-3" | "deepgram/nova-2";
  ttsModel: "cartesia/sonic-3-2026-01-12";
  voiceId: string | null;
}
export function loadConfiguration(
  environment: Record<string, string | undefined>,
): AgentConfiguration {
  const parsed = environmentSchema.safeParse(environment);
  if (!parsed.success) throw new AgentFailure("CONFIGURATION_INVALID");
  const value = parsed.data;
  const enabled = value.AI_VOICE_AGENT_ENABLED === "true";
  const optionalString = (input: unknown): string | null =>
    typeof input === "string" ? input : null;
  const configuration: AgentConfiguration = {
    pilot: value.AI_VOICE_PILOT === "true",
    enabled,
    approvalVersion: optionalString(value.AI_VOICE_AGENT_APPROVAL_VERSION),
    livekitUrl: optionalString(value.LIVEKIT_URL),
    apiKey: optionalString(value.LIVEKIT_API_KEY),
    apiSecret: optionalString(value.LIVEKIT_API_SECRET),
    journalUrl: value.AI_VOICE_JOURNAL_BASE_URL,
    journalToken: optionalString(value.AI_VOICE_JOURNAL_SERVICE_TOKEN),
    sttModel: value.AI_VOICE_STT_MODEL,
    ttsModel: value.AI_VOICE_TTS_MODEL,
    voiceId: optionalString(value.AI_VOICE_TTS_VOICE_ID),
  };
  if (
    configuration.pilot &&
    (configuration.sttModel !== "deepgram/nova-3" ||
      configuration.voiceId !== "b8cd71e3-bc14-4538-a530-d6314731c036")
  )
    throw new AgentFailure("CONFIGURATION_INVALID");
  if (
    configuration.journalToken &&
    configuration.journalToken === configuration.apiSecret
  )
    throw new AgentFailure("CONFIGURATION_INVALID");
  if (
    enabled &&
    (environment.CI === "true" ||
      environment.NODE_ENV === "test" ||
      Object.entries(configuration).some(
        ([key, item]) =>
          [
            "approvalVersion",
            "livekitUrl",
            "apiKey",
            "apiSecret",
            "journalToken",
            "voiceId",
          ].includes(key) && item === null,
      ) ||
      [
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_BEARER_TOKEN_BEDROCK",
        "ANTHROPIC_API_KEY",
        "OPENAI_API_KEY",
        "GOOGLE_API_KEY",
        "DEEPGRAM_API_KEY",
        "CARTESIA_API_KEY",
        "OTEL_EXPORTER_OTLP_ENDPOINT",
      ].some((key) => Boolean(environment[key])))
  )
    throw new AgentFailure("CONFIGURATION_INVALID");
  return configuration;
}
