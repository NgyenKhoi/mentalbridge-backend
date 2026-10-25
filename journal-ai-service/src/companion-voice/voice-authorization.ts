import { z } from "zod";

const ownerBinding = {
  ownerId: z.uuid(),
  voiceSessionId: z.uuid(),
  purpose: z.literal("AI_COMPANION_VOICE"),
  checkedAt: z.iso.datetime({ offset: true }),
};

const consentSchema = z
  .object({
    ...ownerBinding,
    aiProcessingGranted: z.boolean(),
    voiceProcessingGranted: z.boolean(),
    voiceConsentVersion: z.literal("ai-voice-processing-capstone-v1"),
    authorized: z.boolean(),
  })
  .strict()
  .refine(
    (decision) =>
      decision.authorized ===
      (decision.aiProcessingGranted && decision.voiceProcessingGranted),
  );

const entitlementSchema = z
  .object({
    ...ownerBinding,
    packageCode: z.enum(["FREE", "PLUS", "PREMIUM"]),
    source: z.enum(["DEFAULT_FREE", "PAID", "DEMO"]),
    validUntil: z.iso.datetime({ offset: true }).optional(),
    entitlementVersion: z.string().min(1).max(128),
  })
  .strict()
  .refine((decision) =>
    decision.packageCode === "FREE"
      ? decision.source === "DEFAULT_FREE"
      : decision.source !== "DEFAULT_FREE" && decision.validUntil !== undefined,
  );

export type VoiceConsentDecision = z.infer<typeof consentSchema>;
export type VoiceEntitlementDecision = z.infer<typeof entitlementSchema>;

export interface VoiceOwnerDecision {
  readonly consent: VoiceConsentDecision;
  readonly entitlement: VoiceEntitlementDecision;
}

export interface VoiceOwnerAuthorizer {
  check(
    ownerId: string,
    voiceSessionId: string,
    correlationId: string,
  ): Promise<VoiceOwnerDecision>;
}

export interface VoiceOwnerConfiguration {
  readonly careBaseUrl: string;
  readonly consultationBaseUrl: string;
  readonly careToken: string | null;
  readonly entitlementToken: string | null;
}

export class VoiceAuthorizationError extends Error {
  constructor(
    readonly code: "CONSENT_UNAVAILABLE" | "ENTITLEMENT_UNAVAILABLE",
  ) {
    super(code);
  }
}

export class CurrentVoiceOwnerAuthorizer implements VoiceOwnerAuthorizer {
  constructor(
    private readonly configuration: VoiceOwnerConfiguration,
    private readonly clock: () => Date = () => new Date(),
    private readonly transport: typeof fetch = fetch,
  ) {}

  async check(
    ownerId: string,
    voiceSessionId: string,
    correlationId: string,
  ): Promise<VoiceOwnerDecision> {
    const [consent, entitlement] = await Promise.all([
      this.read(
        "/internal/v1/ai-companion/voice-consent-authorizations",
        this.configuration.careBaseUrl,
        "X-MentalBridge-Voice-Care-Token",
        this.configuration.careToken,
        "CONSENT_UNAVAILABLE",
        consentSchema,
        ownerId,
        voiceSessionId,
        correlationId,
      ),
      this.read(
        "/internal/v1/ai-companion/voice-entitlement-authorizations",
        this.configuration.consultationBaseUrl,
        "X-MentalBridge-Voice-Entitlement-Token",
        this.configuration.entitlementToken,
        "ENTITLEMENT_UNAVAILABLE",
        entitlementSchema,
        ownerId,
        voiceSessionId,
        correlationId,
      ),
    ]);
    if (
      entitlement.packageCode !== "FREE" &&
      Date.parse(entitlement.validUntil ?? "") <= this.clock().getTime()
    ) {
      throw new VoiceAuthorizationError("ENTITLEMENT_UNAVAILABLE");
    }
    return { consent, entitlement };
  }

  private async read<T extends z.ZodType>(
    path: string,
    baseUrl: string,
    tokenHeader: string,
    token: string | null,
    failure: VoiceAuthorizationError["code"],
    schema: T,
    ownerId: string,
    voiceSessionId: string,
    correlationId: string,
  ): Promise<z.infer<T>> {
    if (!token) throw new VoiceAuthorizationError(failure);
    try {
      const response = await this.transport(new URL(path, baseUrl), {
        method: "POST",
        redirect: "error",
        headers: {
          [tokenHeader]: token,
          "Content-Type": "application/json",
          "X-Correlation-Id": correlationId,
        },
        body: JSON.stringify({
          ownerId,
          voiceSessionId,
          purpose: "AI_COMPANION_VOICE",
        }),
        signal: AbortSignal.timeout(2_000),
      });
      if (
        !response.ok ||
        response.headers.get("cache-control") !== "no-store"
      ) {
        throw new VoiceAuthorizationError(failure);
      }
      const data: unknown = await response.json();
      const parsed = schema.safeParse(data);
      const binding = z.object(ownerBinding).safeParse(data);
      if (!parsed.success || !binding.success) {
        throw new VoiceAuthorizationError(failure);
      }
      const checkedAt = Date.parse(binding.data.checkedAt);
      if (
        binding.data.ownerId !== ownerId ||
        binding.data.voiceSessionId !== voiceSessionId ||
        checkedAt < this.clock().getTime() - 2_000 ||
        checkedAt > this.clock().getTime()
      ) {
        throw new VoiceAuthorizationError(failure);
      }
      return parsed.data;
    } catch {
      throw new VoiceAuthorizationError(failure);
    }
  }
}
