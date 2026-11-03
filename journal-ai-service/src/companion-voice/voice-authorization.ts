import { z } from "zod";
import { performance } from "node:perf_hooks";
import { setTimeout as pause } from "node:timers/promises";

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
    voiceConsentVersion: z.literal("ai-voice-processing-capstone-v2"),
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

export interface VoiceOwnerDiagnostic {
  service: "CARE" | "CONSULTATION";
  reason:
    | "MISSING_CREDENTIAL"
    | "TIMEOUT"
    | "TRANSPORT"
    | "HTTP_4XX"
    | "HTTP_5XX"
    | "HTTP_OTHER"
    | "INVALID_HEADERS"
    | "INVALID_JSON"
    | "INVALID_RESPONSE"
    | "BINDING_MISMATCH"
    | "STALE_DECISION"
    | "FUTURE_DECISION"
    | "CLOCK_CAUGHT_UP"
    | "EXPIRED_ENTITLEMENT";
  elapsedMs: number;
  status?: number;
  decisionAgeMs?: number;
}

export class CurrentVoiceOwnerAuthorizer implements VoiceOwnerAuthorizer {
  constructor(
    private readonly configuration: VoiceOwnerConfiguration,
    private readonly clock: () => Date = () => new Date(),
    private readonly transport: typeof fetch = fetch,
    private readonly diagnostic: (value: VoiceOwnerDiagnostic) => void = () =>
      undefined,
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
    const now = this.clock().getTime();
    for (const [decision, service, code] of [
      [consent, "CARE", "CONSENT_UNAVAILABLE"],
      [entitlement, "CONSULTATION", "ENTITLEMENT_UNAVAILABLE"],
    ] as const) {
      const age = now - Date.parse(decision.checkedAt);
      if (age < 0 || age > 2_000) {
        this.observe({
          service,
          reason: age < 0 ? "FUTURE_DECISION" : "STALE_DECISION",
          elapsedMs: 0,
          decisionAgeMs: Math.max(-60_000, Math.min(60_000, age)),
        });
        throw new VoiceAuthorizationError(code);
      }
    }
    if (
      entitlement.packageCode !== "FREE" &&
      Date.parse(entitlement.validUntil ?? "") <= now
    ) {
      this.observe({
        service: "CONSULTATION",
        reason: "EXPIRED_ENTITLEMENT",
        elapsedMs: 0,
      });
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
    const started = performance.now();
    const service = failure === "CONSENT_UNAVAILABLE" ? "CARE" : "CONSULTATION";
    const reject = (
      reason: VoiceOwnerDiagnostic["reason"],
      status?: number,
      age?: number,
    ): never => {
      this.observe({
        service,
        reason,
        elapsedMs: Math.min(
          30_000,
          Math.max(0, Math.floor(performance.now() - started)),
        ),
        ...(status === undefined ? {} : { status }),
        ...(age === undefined
          ? {}
          : { decisionAgeMs: Math.max(-60_000, Math.min(60_000, age)) }),
      });
      throw new VoiceAuthorizationError(failure);
    };
    if (!token) return reject("MISSING_CREDENTIAL");
    const deadline = AbortSignal.timeout(2_000);
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
        signal: deadline,
      });
      if (!response.ok) {
        return reject(
          response.status >= 400 && response.status < 500
            ? "HTTP_4XX"
            : response.status >= 500 && response.status < 600
              ? "HTTP_5XX"
              : "HTTP_OTHER",
          response.status,
        );
      }
      if (response.headers.get("cache-control") !== "no-store")
        return reject("INVALID_HEADERS");
      let data: unknown;
      try {
        data = await response.json();
      } catch {
        return reject(deadline.aborted ? "TIMEOUT" : "INVALID_JSON");
      }
      const parsed = schema.safeParse(data);
      const binding = z.object(ownerBinding).safeParse(data);
      if (!parsed.success || !binding.success) {
        return reject("INVALID_RESPONSE");
      }
      const checkedAt = Date.parse(binding.data.checkedAt);
      let now = this.clock().getTime();
      if (
        binding.data.ownerId !== ownerId ||
        binding.data.voiceSessionId !== voiceSessionId
      ) {
        return reject("BINDING_MISMATCH");
      }
      if (checkedAt < now - 2_000)
        return reject("STALE_DECISION", undefined, now - checkedAt);
      const initialAge = now - checkedAt;
      if (initialAge < 0 && initialAge >= -25) {
        const waitStarted = performance.now();
        while (
          checkedAt > now &&
          performance.now() - waitStarted < 25 &&
          performance.now() - started < 2_000
        ) {
          await pause(1, undefined, { signal: deadline });
          now = this.clock().getTime();
        }
        if (checkedAt <= now)
          this.observe({
            service,
            reason: "CLOCK_CAUGHT_UP",
            elapsedMs: Math.min(2_000, Math.floor(performance.now() - started)),
            decisionAgeMs: initialAge,
          });
      }
      if (deadline.aborted || performance.now() - started >= 2_000)
        return reject("TIMEOUT");
      if (checkedAt > now)
        return reject("FUTURE_DECISION", undefined, now - checkedAt);
      if (checkedAt < now - 2_000)
        return reject("STALE_DECISION", undefined, now - checkedAt);
      return parsed.data;
    } catch (error) {
      if (error instanceof VoiceAuthorizationError) throw error;
      return reject(deadline.aborted ? "TIMEOUT" : "TRANSPORT");
    }
  }

  private observe(value: VoiceOwnerDiagnostic) {
    try {
      this.diagnostic(value);
    } catch {
      return;
    }
  }
}
