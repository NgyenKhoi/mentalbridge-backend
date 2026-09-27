import { z } from "zod";

const unsupportedSchemaKeywords = new Set([
  "minLength",
  "maxLength",
  "minimum",
  "maximum",
  "multipleOf",
  "maxItems",
]);

export const toBedrockStructuredOutputSchema = (schema: unknown): unknown => {
  if (Array.isArray(schema))
    return schema.map((value) => toBedrockStructuredOutputSchema(value));
  if (typeof schema !== "object" || schema === null) return schema;

  const adapted: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(schema)) {
    if (unsupportedSchemaKeywords.has(key)) continue;
    if (key === "minItems" && value !== 0 && value !== 1) continue;
    if (key === "additionalProperties" && value !== false) continue;
    adapted[key] = toBedrockStructuredOutputSchema(value);
  }
  return adapted;
};

export const bedrockConverseUrl = (region: string, model: string): URL =>
  new URL(
    `/model/${encodeURIComponent(model)}/converse`,
    `https://bedrock-runtime.${region}.amazonaws.com`,
  );

export const bedrockConverseRequest = (
  system: string,
  user: string,
  schema: unknown,
  schemaName: string,
  maxTokens: number,
) => ({
  system: [{ text: system }],
  messages: [{ role: "user", content: [{ text: user }] }],
  inferenceConfig: { temperature: 0.2, maxTokens },
  outputConfig: {
    textFormat: {
      type: "json_schema",
      structure: {
        jsonSchema: {
          name: schemaName,
          schema: JSON.stringify(toBedrockStructuredOutputSchema(schema)),
        },
      },
    },
  },
});

const tokenCount = z.number().int().min(0);
const bedrockResponseSchema = z.object({
  output: z.object({
    message: z.object({
      content: z.array(z.looseObject({ text: z.string().optional() })).min(1),
    }),
  }),
  usage: z
    .object({
      inputTokens: tokenCount.optional(),
      outputTokens: tokenCount.optional(),
    })
    .optional(),
});

export type BedrockConverseOutput = {
  readonly text: string;
  readonly inputTokens: number | null;
  readonly outputTokens: number | null;
};

export const parseBedrockConverseResponse = (
  value: unknown,
): BedrockConverseOutput | null => {
  const parsed = bedrockResponseSchema.safeParse(value);
  if (!parsed.success) return null;
  const text = parsed.data.output.message.content
    .flatMap((content) => (content.text === undefined ? [] : [content.text]))
    .join("");
  if (text.length === 0) return null;
  return {
    text,
    inputTokens: parsed.data.usage?.inputTokens ?? null,
    outputTokens: parsed.data.usage?.outputTokens ?? null,
  };
};
