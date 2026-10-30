export type VoiceProviderPhase =
  | "token_count_requested"
  | "token_count_authorized"
  | "bedrock_requested"
  | "bedrock_response_received"
  | "provider_usage"
  | "bedrock_response_validated";
export type VoiceFailureCode =
  | "TIMEOUT"
  | "ABORTED"
  | "HTTP_4XX"
  | "HTTP_5XX"
  | "TRANSPORT"
  | "INVALID_RESPONSE"
  | "INPUT_BUDGET_EXCEEDED"
  | "AUTHORITY_OR_STATE"
  | "INTERNAL";
export interface VoiceProviderObservation {
  phase: VoiceProviderPhase;
  code?: VoiceFailureCode;
  countedInputTokens?: number;
  inputTokens?: number;
  outputTokens?: number;
  usageSource?: "PROVIDER";
}
export type VoiceObserver = (value: VoiceProviderObservation) => void;
export class VoiceProviderFailure extends Error {
  constructor(readonly code: VoiceFailureCode) {
    super(code);
  }
}
export function providerUsage(
  body: unknown,
): Pick<
  VoiceProviderObservation,
  "inputTokens" | "outputTokens" | "usageSource"
> {
  if (typeof body !== "object" || body === null || !("usage" in body))
    return {};
  const usage = body.usage;
  if (
    typeof usage !== "object" ||
    usage === null ||
    !("inputTokens" in usage) ||
    !("outputTokens" in usage)
  )
    return {};
  const { inputTokens, outputTokens } = usage;
  if (
    typeof inputTokens !== "number" ||
    typeof outputTokens !== "number" ||
    !Number.isSafeInteger(inputTokens) ||
    !Number.isSafeInteger(outputTokens) ||
    inputTokens < 0 ||
    outputTokens < 0
  )
    return {};
  return { inputTokens, outputTokens, usageSource: "PROVIDER" };
}
