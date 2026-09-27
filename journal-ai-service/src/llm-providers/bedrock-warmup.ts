import type { ServiceConfiguration } from "../configuration/configuration.js";
import {
  companionMaxOutputTokens,
  providerOutputJsonSchema,
} from "../companion-chat/companion-chat.js";
import { exactRevisionOutputJsonSchema } from "../prompts/exact-revision.js";
import { longitudinalOutputJsonSchema } from "../prompts/longitudinal.js";
import { supportGuidePhrasingOutputJsonSchema } from "../prompts/support-guide-phrasing.js";
import { bedrockConverseRequest, bedrockConverseUrl } from "./bedrock.js";

const warmupSchemas = [
  {
    workload: "EXACT_REVISION",
    name: "mentalbridge_exact_revision",
    schema: exactRevisionOutputJsonSchema,
    maxTokens: 1_200,
  },
  {
    workload: "LONGITUDINAL",
    name: "mentalbridge_longitudinal",
    schema: longitudinalOutputJsonSchema,
    maxTokens: 1_200,
  },
  {
    workload: "SUPPORT_GUIDE_PHRASING",
    name: "mentalbridge_support_guide_phrasing",
    schema: supportGuidePhrasingOutputJsonSchema,
    maxTokens: 1_200,
  },
  {
    workload: "COMPANION_CHAT",
    name: "mentalbridge_companion_reply",
    schema: providerOutputJsonSchema,
    maxTokens: companionMaxOutputTokens,
  },
] as const;

const safeProviderErrorCode = (value: string | null): string | null => {
  const code = value?.split(":", 1)[0]?.trim();
  return code !== undefined && /^[A-Za-z0-9._-]{1,96}$/.test(code)
    ? code
    : null;
};

export interface BedrockWarmupResult {
  readonly models: number;
  readonly schemasPerModel: number;
  readonly completedRequests: number;
}

export const configuredBedrockModels = (
  configuration: ServiceConfiguration,
): readonly string[] => [
  ...new Set(
    [
      configuration.FREE_PLUS_ROUTE,
      configuration.PREMIUM_ROUTE,
      configuration.BENCHMARK_BEDROCK_ROUTE,
    ]
      .filter(
        (route): route is NonNullable<typeof route> =>
          route !== null && route.provider === "BEDROCK",
      )
      .map((route) => route.model),
  ),
];

export const warmBedrockStructuredOutputs = async (
  configuration: ServiceConfiguration,
  fetchImplementation: typeof fetch = fetch,
): Promise<BedrockWarmupResult> => {
  if (!configuration.BEDROCK_API_KEY)
    throw new Error("Bedrock schema warm-up requires AWS_BEARER_TOKEN_BEDROCK");

  const models = configuredBedrockModels(configuration);
  if (models.length === 0)
    throw new Error(
      "Bedrock schema warm-up requires a configured Bedrock route",
    );

  let completedRequests = 0;
  for (const model of models) {
    for (const schema of warmupSchemas) {
      const response = await fetchImplementation(
        bedrockConverseUrl(configuration.BEDROCK_REGION, model),
        {
          method: "POST",
          headers: {
            authorization: `Bearer ${configuration.BEDROCK_API_KEY}`,
            "content-type": "application/json",
          },
          body: JSON.stringify(
            bedrockConverseRequest(
              "Return one minimal JSON object that satisfies the supplied schema.",
              `Compile the ${schema.workload} structured-output schema.`,
              schema.schema,
              schema.name,
              schema.maxTokens,
            ),
          ),
          signal: AbortSignal.timeout(
            configuration.BEDROCK_SCHEMA_WARMUP_TIMEOUT_MS,
          ),
        },
      );

      if (!response.ok) {
        const providerCode = safeProviderErrorCode(
          response.headers.get("x-amzn-errortype"),
        );
        throw new Error(
          `Bedrock schema warm-up failed for ${schema.workload} with HTTP ${String(response.status)}${providerCode === null ? "" : ` (${providerCode})`}`,
        );
      }

      await response.arrayBuffer();
      completedRequests += 1;
    }
  }

  return {
    models: models.length,
    schemasPerModel: warmupSchemas.length,
    completedRequests,
  };
};
