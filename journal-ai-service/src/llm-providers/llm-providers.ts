import { z } from "zod";

import type { ServiceConfiguration } from "../configuration/configuration.js";
import type {
  AiProviderId,
  AnalysisRoute,
} from "../model-routing/model-routing.js";
import {
  exactRevisionPrompt,
  normalizeExactRevisionOutput,
  type ExactRevisionPrompt,
} from "../prompts/exact-revision.js";

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

export class ProviderFailure extends Error {
  constructor(
    readonly kind: "RETRYABLE" | "PERMANENT",
    readonly reason: "TIMEOUT" | "UNAVAILABLE" | "INVALID_RESULT",
    options?: ErrorOptions,
  ) {
    super(reason, options);
  }
}

interface LlmProvider {
  readonly id: Exclude<AiProviderId, "DETERMINISTIC_FAKE">;
  generate(
    prompt: ExactRevisionPrompt,
    route: AnalysisRoute,
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
  route: AnalysisRoute,
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

const parseOutput = (text: string): unknown => {
  try {
    return normalizeExactRevisionOutput(JSON.parse(text));
  } catch (error) {
    throw new ProviderFailure("PERMANENT", "INVALID_RESULT", { cause: error });
  }
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
        { cause: error },
      );
    }
  }

  protected requireSuccess(response: Response): void {
    if (response.ok) return;
    throw new ProviderFailure(
      response.status === 429 || response.status >= 500
        ? "RETRYABLE"
        : "PERMANENT",
      "UNAVAILABLE",
    );
  }
}

export class GeminiProvider extends HttpLlmProvider implements LlmProvider {
  readonly id = "GEMINI" as const;

  async generate(prompt: ExactRevisionPrompt, route: AnalysisRoute) {
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
    this.requireSuccess(response);
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        cause: error,
      });
    }
    const parsed = geminiResponseSchema.safeParse(body);
    if (!parsed.success)
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT");
    const text = parsed.data.candidates[0]?.content.parts
      .map((part) => part.text)
      .join("");
    if (!text) throw new ProviderFailure("PERMANENT", "INVALID_RESULT");
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

  async generate(prompt: ExactRevisionPrompt, route: AnalysisRoute) {
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
            name: "mentalbridge_exact_revision",
            strict: true,
            schema: prompt.schema,
          },
        },
      },
    );
    this.requireSuccess(response);
    let body: unknown;
    try {
      body = await response.json();
    } catch (error) {
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT", {
        cause: error,
      });
    }
    const parsed = openAiResponseSchema.safeParse(body);
    if (!parsed.success || parsed.data.status !== "completed")
      throw new ProviderFailure("PERMANENT", "INVALID_RESULT");
    const text = parsed.data.output
      .flatMap((item) => item.content ?? [])
      .find((content) => content.type === "output_text")?.text;
    if (!text) throw new ProviderFailure("PERMANENT", "INVALID_RESULT");
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
    return provider.generate(exactRevisionPrompt(text), route);
  }
}
