import type { ServiceConfiguration } from "../configuration/configuration.js";

export const companionMaxOutputTokens = 2_000;
export const voiceMaxInputTokens = 1_900;

export function effectiveBedrockRequestLimits(
  configuration: Pick<
    ServiceConfiguration,
    "VOICE_PILOT" | "VOICE_FRIEND_ENABLED"
  >,
  voice: boolean,
): {
  inputGuardEnabled: boolean;
  inputTokenLimit: typeof voiceMaxInputTokens | null;
  outputTokenLimit: number;
} {
  const pilot = voice ? configuration.VOICE_PILOT : undefined;
  const inputGuardEnabled =
    pilot !== undefined ||
    (voice && configuration.VOICE_FRIEND_ENABLED === true);
  return {
    inputGuardEnabled,
    inputTokenLimit: inputGuardEnabled ? voiceMaxInputTokens : null,
    outputTokenLimit: pilot
      ? (pilot.maxOutputTokens ?? 300)
      : companionMaxOutputTokens,
  };
}
