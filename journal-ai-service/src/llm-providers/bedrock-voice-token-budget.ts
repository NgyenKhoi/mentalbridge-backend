import { z } from "zod";

export async function authorizeVoiceInput(
  apiKey: string,
  system: string,
  user: string,
  signal: AbortSignal,
): Promise<void> {
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
  if (!response.ok) throw new Error("Voice token authorization unavailable");
  const count = z
    .object({ inputTokens: z.number().int().min(1).max(1900) })
    .safeParse(await response.json());
  if (!count.success) throw new Error("Voice input budget exceeded");
  signal.throwIfAborted();
}
