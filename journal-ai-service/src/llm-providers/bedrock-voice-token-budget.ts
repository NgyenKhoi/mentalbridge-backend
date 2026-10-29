import { z } from "zod";

export async function authorizeVoiceInput(
  apiKey: string,
  system: string,
  user: string,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(
    "https://bedrock-mantle.ap-northeast-1.api.aws/anthropic/v1/messages/count_tokens",
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": apiKey,
        "anthropic-version": "2023-06-01",
      },
      body: JSON.stringify({
        model: "anthropic.claude-sonnet-4-6",
        system,
        messages: [{ role: "user", content: [{ type: "text", text: user }] }],
      }),
      signal,
    },
  );
  if (!response.ok) throw new Error("Voice token authorization unavailable");
  const count = z
    .object({ input_tokens: z.number().int().min(1).max(1900) })
    .safeParse(await response.json());
  if (!count.success) throw new Error("Voice input budget exceeded");
  signal.throwIfAborted();
}
