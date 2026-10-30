import { z } from "zod";
import {
  VoiceProviderFailure,
  type VoiceObserver,
} from "./voice-diagnostics.js";

export async function authorizeVoiceInput(
  apiKey: string,
  system: string,
  user: string,
  signal: AbortSignal,
  observe?: VoiceObserver,
): Promise<void> {
  observe?.({ phase: "token_count_requested" });
  const response = await fetch(
    "https://bedrock-runtime.ap-southeast-1.amazonaws.com/model/anthropic.claude-sonnet-4-6/count-tokens",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        input: {
          converse: {
            system: [{ text: system }],
            messages: [{ role: "user", content: [{ text: user }] }],
          },
        },
      }),
      redirect: "error",
      signal,
    },
  );
  if (!response.ok)
    throw new VoiceProviderFailure(
      response.status >= 500 ? "HTTP_5XX" : "HTTP_4XX",
    );
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw new VoiceProviderFailure("INVALID_RESPONSE");
  }
  const count = z
    .object({ inputTokens: z.number().int().min(1) })
    .safeParse(body);
  if (!count.success) throw new VoiceProviderFailure("INVALID_RESPONSE");
  if (count.data.inputTokens > 1900)
    throw new VoiceProviderFailure("INPUT_BUDGET_EXCEEDED");
  signal.throwIfAborted();
  observe?.({
    phase: "token_count_authorized",
    countedInputTokens: count.data.inputTokens,
  });
}
