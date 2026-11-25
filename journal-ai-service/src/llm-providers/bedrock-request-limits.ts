import type { ServiceConfiguration } from "../configuration/configuration.js";

export const companionMaxOutputTokens = 2_000;
export const voiceMaxInputTokens = 1_900;

export function effectiveBedrockRequestLimits(
  configuration: Pick<ServiceConfiguration, "VOICE_PILOT">,
  voice: boolean,
): {
  inputGuardEnabled: boolean;
  inputTokenLimit: typeof voiceMaxInputTokens | null;
  outputTokenLimit: number;
} {
  const pilot = voice ? configuration.VOICE_PILOT : undefined;
  return {
    inputGuardEnabled: voice,
    inputTokenLimit: voice ? voiceMaxInputTokens : null,
    // 600 tokens fit an 800-character Vietnamese reply plus its JSON envelope (MB-846d).
    outputTokenLimit: pilot
      ? (pilot.maxOutputTokens ?? 600)
      : companionMaxOutputTokens,
  };
}
