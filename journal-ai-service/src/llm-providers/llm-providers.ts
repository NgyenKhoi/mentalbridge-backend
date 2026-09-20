import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type {
  AiProviderId,
  AnalysisRoute,
  LongitudinalAnalysisRoute,
} from "../model-routing/model-routing.js";
import {
  exactRevisionPrompt,
  normalizeExactRevisionOutput,
  type ExactRevisionPrompt,
} from "../prompts/exact-revision.js";
import {
  longitudinalPrompt,
  type LongitudinalPrompt,
  type LongitudinalPromptCoverage,
  type LongitudinalPromptSource,
} from "../prompts/longitudinal.js";

export interface ProviderUsage {
  readonly inputTokens: number | null;
  readonly outputTokens: number | null;
  readonly estimatedCostMicroUsd: number | null;
}

export interface ProviderAnalysis {
  readonly output: unknown;
  readonly latencyMs: number;
  readonly usage: ProviderUsage;
}

export interface ExactRevisionProvider {
  analyze(text: string, route: AnalysisRoute): Promise<ProviderAnalysis>;
}

export interface LongitudinalProvider {
  analyze(
    sources: readonly LongitudinalPromptSource[],
    coverage: LongitudinalPromptCoverage,
    route: LongitudinalAnalysisRoute,
  ): Promise<ProviderAnalysis>;
}

export interface ProviderFailureDiagnostics {
  readonly attemptCount?: number;
  readonly httpStatus?: number;
  readonly providerErrorCode?: string;
  readonly retryAfterMs?: number;
  readonly quotaIds?: readonly string[];
  readonly quotaMetrics?: readonly string[];
  readonly finishReason?: string;
  readonly schemaIssues?: readonly string[];
}

export class ProviderFailure extends Error {
  constructor(
    readonly kind: "RETRYABLE" | "PERMANENT",
    readonly reason: "TIMEOUT" | "UNAVAILABLE" | "INVALID_RESULT",
    readonly diagnostics: ProviderFailureDiagnostics = {},
    options?: ErrorOptions,
  ) {
    super(reason, options);
  }
}

interface LlmProvider {
  readonly id: Exclude<AiProviderId, "DETERMINISTIC_FAKE">;
  generate(
    prompt: ExactRevisionPrompt | LongitudinalPrompt,
    route: AnalysisRoute | LongitudinalAnalysisRoute,
  ): Promise<ProviderAnalysis>;
}

const tokenCount = z.number().int().min(0);
const geminiResponseSchema = z.object({
  candidates: z
    .array(
      z.looseObject({
        content: z.object({
          parts: z.array(z.looseObject({ text: z.string() })).min(1),
        }),
        finishReason: z.string().optional(),
      }),
    )
    .min(1),
  usageMetadata: z
    .object({
      promptTokenCount: tokenCount.optional(),
      candidatesTokenCount: tokenCount.optional(),
    })
    .optional(),
});
const openAiResponseSchema = z.object({
  status: z.enum([
    "completed",
    "failed",
    "in_progress",
    "cancelled",
    "queued",
    "incomplete",
  ]),
  output: z.array(
    z.looseObject({
      type: z.string(),
      content: z
        .array(z.looseObject({ type: z.string(), text: z.string().optional() }))
        .optional(),
    }),
  ),
  usage: z
    .object({
      input_tokens: tokenCount,
      output_tokens: tokenCount,
    })
    .optional(),
});

const estimatedCost = (
  route: Pick<
    AnalysisRoute,
    "inputCostMicroUsdPerMillionTokens" | "outputCostMicroUsdPerMillionTokens"
  >,
  inputTokens: number | null,
  outputTokens: number | null,
): number | null => {
  if (inputTokens === null || outputTokens === null) return null;
  return Math.ceil(
    (inputTokens * route.inputCostMicroUsdPerMillionTokens +
      outputTokens * route.outputCostMicroUsdPerMillionTokens) /
      1_000_000,
  );
};

const schemaIssues = (error: z.ZodError): string[] =>
  error.issues.map(
    (issue) =>
      `${issue.path.length === 0 ? "<root>" : issue.path.join(".")}:${issue.code}`,
  );

const parseOutput = (text: string): unknown => {
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new ProviderFailure(
      "PERMANENT",
      "INVALID_RESULT",
      {
        schemaIssues: ["structuredOutput:invalid_json"],
      },
      { cause: error },
    );
  }
};

const safeProviderErrorCode = (body: unknown): string | undefined => {
  if (typeof body !== "object" || body === null) return undefined;
  const error = (body as Record<string, unknown>).error;
  if (typeof error !== "object" || error === null) return undefined;
  const status = (error as Record<string, unknown>).status;
  if (typeof status === "string" && status.length <= 96) return status;
  const code = (error as Record<string, unknown>).code;
  if (typeof code === "string" || typeof code === "number")
    return String(code).slice(0, 96);
  return undefined;
};

const retryAfterMs = (value: string | null): number | undefined => {
  if (!value) return undefined;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0)
    return Math.min(30_000, Math.ceil(seconds * 1_000));
  const date = Date.parse(value);
  if (!Number.isFinite(date)) return undefined;
  return Math.min(30_000, Math.max(0, date - Date.now()));
};

const durationMs = (value: unknown): number | undefined => {
  if (typeof value !== "string") return undefined;
  const match = /^(\d+)(?:\.(\d{1,9}))?s$/.exec(value);
  if (!match) return undefined;
  const seconds = Number(match[1]);
  const fraction = Number(`0.${match[2] ?? "0"}`);
  if (!Number.isFinite(seconds) || !Number.isFinite(fraction)) return undefined;
  return Math.min(300_000, Math.ceil((seconds + fraction) * 1_000));
};

const safeProviderErrorDetails = (
  body: unknown,
): Pick<
  ProviderFailureDiagnostics,
  "retryAfterMs" | "quotaIds" | "quotaMetrics"
> => {
  if (typeof body !== "object" || body === null) return {};
  const error = (body as Record<string, unknown>).error;
  if (typeof error !== "object" || error === null) return {};
  const details = (error as Record<string, unknown>).details;
  if (!Array.isArray(details)) return {};
  let providerRetryAfterMs: number | undefined;
  const quotaIds = new Set<string>();
  const quotaMetrics = new Set<string>();
  for (const detailValue of details) {
    const detail: unknown = detailValue;
    if (typeof detail !== "object" || detail === null) continue;
    const detailRecord = detail as Record<string, unknown>;
    providerRetryAfterMs ??= durationMs(detailRecord.retryDelay);
    const violations = detailRecord.violations;
    if (!Array.isArray(violations)) continue;
    for (const violationValue of violations) {
      const violation: unknown = violationValue;
      if (typeof violation !== "object" || violation === null) continue;
      const violationRecord = violation as Record<string, unknown>;
      const quotaId = violationRecord.quotaId;
      const quotaMetric = violationRecord.quotaMetric;
      if (typeof quotaId === "string" && quotaId.length <= 160)
        quotaIds.add(quotaId);
      if (typeof quotaMetric === "string" && quotaMetric.length <= 200)
        quotaMetrics.add(quotaMetric);
    }
  }
  return {
    ...(providerRetryAfterMs === undefined
      ? {}
      : { retryAfterMs: providerRetryAfterMs }),
    ...(quotaIds.size === 0 ? {} : { quotaIds: [...quotaIds].slice(0, 8) }),
    ...(quotaMetrics.size === 0
      ? {}
      : { quotaMetrics: [...quotaMetrics].slice(0, 8) }),
  };
};

const safeFinishReason = (body: unknown): string | undefined => {
  if (typeof body !== "object" || body === null) return undefined;
  const candidates = (body as Record<string, unknown>).candidates;
  if (!Array.isArray(candidates)) return undefined;
  const first: unknown = candidates[0];
  if (typeof first !== "object" || first === null) return undefined;
  const value = (first as Record<string, unknown>).finishReason;
  return typeof value === "string" && value.length <= 96 ? value : undefined;
};

abstract class HttpLlmProvider {
  constructor(protected readonly configuration: ServiceConfiguration) {}

  protected async post(
    url: URL,
    headers: HeadersInit,
    body: unknown,
  ): Promise<Response> {
    try {
      const requestHeaders = new Headers(headers);
      requestHeaders.set("content-type", "application/json");
      return await fetch(url, {
        method: "POST",
        headers: requestHeaders,
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(this.configuration.PROVIDER_TIMEOUT_MS),
      });
    } catch (error) {
      const name = error instanceof Error ? error.name : "";
      throw new ProviderFailure(
        "RETRYABLE",
        name === "TimeoutError" || name === "AbortError"
          ? "TIMEOUT"
          : "UNAVAILABLE",
        {},
        { cause: error },
      );
    }
  }

  protected async requireSuccess(response: Response): Promise<void> {
    if (response.ok) return;
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      body = undefined;
    }
    const providerErrorCode = safeProviderErrorCode(body);
    const providerDetails = safeProviderErrorDetails(body);
    const retryDelay =
      retryAfterMs(response.headers.get("retry-after")) ??
      providerDetails.retryAfterMs;
    throw new ProviderFailure(
      response.status === 429 || response.status >= 500
        ? "RETRYABLE"
        : "PERMANENT",
      "UNAVAILABLE",
      {
        httpStatus: response.status,
        ...(providerErrorCode === undefined ? {} : { providerErrorCode }),
        ...(retryDelay === undefined ? {} : { retryAfterMs: retryDelay }),
        ...(providerDetails.quotaIds === undefined
          ? {}
          : { quotaIds: providerDetails.quotaIds }),
        ...(providerDetails.quotaMetrics === undefined
          ? {}
          : { quotaMetrics: providerDetails.quotaMetrics }),
      },
    );
  }
}

export class GeminiProvider extends HttpLlmProvider implements LlmProvider {
  readonly id = "GEMINI" as const;

  async generate(
    prompt: ExactRevisionPrompt | LongitudinalPrompt,
    route: AnalysisRoute | LongitudinalAnalysisRoute,
  ) {
    if (!this.configuration.GEMINI_API_KEY)
      throw new ProviderFailure("PERMANENT", "UNAVAILABLE");
    const model = route.model.replace(/^models\//, "");
    const url = new URL(
      `/v1beta/models/${encodeURIComponent(model)}:generateContent`,
      this.configuration.GEMINI_BASE_URL,
    );
    const startedAt = performance.now();
    const response = await this.post(
      url,
      { "x-goog-api-key": this.configuration.GEMINI_API_KEY },
      {
        systemInstruction: { parts: [{ text: prompt.system }] },
        contents: [{ role: "user", parts: [{ text: prompt.user }] }],
        generationConfig: {
          responseMimeType: "application/json",
          responseJsonSchema: prompt.schema,
          temperature: 0.2,
          maxOutputTokens: 1_200,
        },
      },
    );
    await this.requireSuccess(response);
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      throw new ProviderFailure(
        "PERMANENT",
        "INVALID_RESULT",
        { schemaIssues: ["response:invalid_json"] },
        { cause: error },
      );
    }
    const parsed = geminiResponseSchema.safeParse(body);
    if (!parsed.success) {
      const finishReason = safeFinishReason(body);
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        ...(finishReason === undefined ? {} : { finishReason }),
        schemaIssues: schemaIssues(parsed.error),
      });
    }
    const text = parsed.data.candidates[0]?.content.parts
      .map((part) => part.text)
      .join("");
    if (!text) {
      const finishReason = parsed.data.candidates[0]?.finishReason;
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        ...(finishReason === undefined ? {} : { finishReason }),
        schemaIssues: ["candidates.0.content.parts:text_missing"],
      });
    }
    const inputTokens = parsed.data.usageMetadata?.promptTokenCount ?? null;
    const outputTokens =
      parsed.data.usageMetadata?.candidatesTokenCount ?? null;
    return {
      output: parseOutput(text),
      latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
      usage: {
        inputTokens,
        outputTokens,
        estimatedCostMicroUsd: estimatedCost(route, inputTokens, outputTokens),
      },
    };
  }
}

export class OpenAiProvider extends HttpLlmProvider implements LlmProvider {
  readonly id = "OPENAI" as const;

  async generate(
    prompt: ExactRevisionPrompt | LongitudinalPrompt,
    route: AnalysisRoute | LongitudinalAnalysisRoute,
  ) {
    if (!this.configuration.OPENAI_API_KEY)
      throw new ProviderFailure("PERMANENT", "UNAVAILABLE");
    const startedAt = performance.now();
    const response = await this.post(
      new URL("/v1/responses", this.configuration.OPENAI_BASE_URL),
      { authorization: `Bearer ${this.configuration.OPENAI_API_KEY}` },
      {
        model: route.model,
        store: false,
        instructions: prompt.system,
        input: prompt.user,
        max_output_tokens: 1_200,
        text: {
          format: {
            type: "json_schema",
            name:
              route.workload === "LONGITUDINAL"
                ? "mentalbridge_longitudinal"
                : "mentalbridge_exact_revision",
            strict: true,
            schema: prompt.schema,
          },
        },
      },
    );
    await this.requireSuccess(response);
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      throw new ProviderFailure(
        "PERMANENT",
        "INVALID_RESULT",
        { schemaIssues: ["response:invalid_json"] },
        { cause: error },
      );
    }
    const parsed = openAiResponseSchema.safeParse(body);
    if (!parsed.success || parsed.data.status !== "completed")
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        schemaIssues: parsed.success
          ? [`status:${parsed.data.status}`]
          : schemaIssues(parsed.error),
      });
    const text = parsed.data.output
      .flatMap((item) => item.content ?? [])
      .find((content) => content.type === "output_text")?.text;
    if (!text)
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        schemaIssues: ["output:output_text_missing"],
      });
    const inputTokens = parsed.data.usage?.input_tokens ?? null;
    const outputTokens = parsed.data.usage?.output_tokens ?? null;
    return {
      output: parseOutput(text),
      latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
      usage: {
        inputTokens,
        outputTokens,
        estimatedCostMicroUsd: estimatedCost(route, inputTokens, outputTokens),
      },
    };
  }
}

export class RoutedExactRevisionProvider implements ExactRevisionProvider {
  private readonly providers: ReadonlyMap<AiProviderId, LlmProvider>;

  constructor(configuration: ServiceConfiguration) {
    const providers: LlmProvider[] = [
      new GeminiProvider(configuration),
      new OpenAiProvider(configuration),
    ];
    this.providers = new Map(
      providers.map((provider) => [provider.id, provider]),
    );
  }

  analyze(text: string, route: AnalysisRoute): Promise<ProviderAnalysis> {
    if (route.provider === "DETERMINISTIC_FAKE") {
      const startedAt = performance.now();
      return Promise.resolve({
        output: {
          summary: `Bạn đã ghi lại một phản ánh gồm ${String(Array.from(text).length)} ký tự.`,
          contextSignals: [],
          emotionIndicators: [],
          themes: [],
          preferenceSignals: [],
          barrierSignals: [],
          suggestedAction: "NONE",
        },
        latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
        usage: {
          inputTokens: null,
          outputTokens: null,
          estimatedCostMicroUsd: null,
        },
      });
    }
    const provider = this.providers.get(route.provider);
    if (!provider) throw new ProviderFailure("PERMANENT", "UNAVAILABLE");
    return provider
      .generate(exactRevisionPrompt(text), route)
      .then((analysis) => ({
        ...analysis,
        output: normalizeExactRevisionOutput(analysis.output),
      }));
  }
}

export class RoutedLongitudinalProvider implements LongitudinalProvider {
  private readonly providers: ReadonlyMap<AiProviderId, LlmProvider>;

  constructor(configuration: ServiceConfiguration) {
    const providers: LlmProvider[] = [
      new GeminiProvider(configuration),
      new OpenAiProvider(configuration),
    ];
    this.providers = new Map(
      providers.map((provider) => [provider.id, provider]),
    );
  }

  analyze(
    sources: readonly LongitudinalPromptSource[],
    coverage: LongitudinalPromptCoverage,
    route: LongitudinalAnalysisRoute,
  ): Promise<ProviderAnalysis> {
    if (route.provider === "DETERMINISTIC_FAKE") {
      const startedAt = performance.now();
      return Promise.resolve({
        output: {
          contextSignals: [],
          emotionIndicators: [],
          recurringThemes: [],
          changesComparedWithPreviousPeriod: coverage.sufficientForComparison
            ? []
            : [
                {
                  signal: "AVAILABLE_JOURNAL_COVERAGE",
                  direction: "INSUFFICIENT_DATA",
                },
              ],
          preferences: [],
          barriers: [],
          helpfulPatterns: [],
        },
        latencyMs: Math.max(0, Math.round(performance.now() - startedAt)),
        usage: {
          inputTokens: null,
          outputTokens: null,
          estimatedCostMicroUsd: null,
        },
      });
    }
    const provider = this.providers.get(route.provider);
    if (!provider) throw new ProviderFailure("PERMANENT", "UNAVAILABLE");
    return provider.generate(longitudinalPrompt(sources, coverage), route);
  }
}
