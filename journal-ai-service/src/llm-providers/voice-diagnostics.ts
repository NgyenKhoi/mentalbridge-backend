export type VoiceProviderPhase =
  | "token_count_requested"
  | "token_count_authorized"
  | "bedrock_requested"
  | "bedrock_response_received"
  | "provider_usage"
  | "bedrock_output_validation"
  | "bedrock_response_validated";
export type VoiceStopReason =
  "END_TURN" | "MAX_TOKENS" | "STOP_SEQUENCE" | "OTHER" | "MISSING";
export type VoiceFailureCode =
  | "TIMEOUT"
  | "ABORTED"
  | "HTTP_4XX"
  | "HTTP_5XX"
  | "TRANSPORT"
  | "INVALID_RESPONSE"
  | "OUTPUT_TOKEN_LIMIT"
  | "UNEXPECTED_STOP_REASON"
  | "RESPONSE_ENVELOPE_INVALID"
  | "OUTPUT_JSON_INVALID"
  | "OUTPUT_SCHEMA_INVALID"
  | "OUTPUT_SAFETY_REJECTED"
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
  stopReason?: VoiceStopReason;
}
export type VoiceObserver = (value: VoiceProviderObservation) => void;
export class VoiceProviderFailure extends Error {
  constructor(readonly code: VoiceFailureCode) {
    super(code);
  }
}
export function voiceStopReason(body: unknown): VoiceStopReason {
  if (typeof body !== "object" || body === null || !("stopReason" in body))
    return "MISSING";
  switch (body.stopReason) {
    case "end_turn":
      return "END_TURN";
    case "max_tokens":
      return "MAX_TOKENS";
    case "stop_sequence":
      return "STOP_SEQUENCE";
    default:
      return "OTHER";
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
