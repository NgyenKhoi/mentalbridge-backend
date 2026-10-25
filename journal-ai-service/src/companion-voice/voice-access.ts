import { createHmac, randomUUID } from "node:crypto";
import { z } from "zod";

import {
  VoiceLedgerError,
  type MongoVoiceLedger,
  type VoicePlan,
  type VoiceSession,
  type VoiceTerminalReason,
} from "./voice-ledger.js";
import {
  VoiceAuthorizationError,
  type VoiceOwnerAuthorizer,
  type VoiceOwnerDecision,
} from "./voice-authorization.js";
import { VOICE_POLICY_VERSION, VOICE_TIME_ZONE } from "./voice-policy.js";

export type VoiceAccessLedger = Pick<
  MongoVoiceLedger,
  | "snapshot"
  | "replay"
  | "recordStartAttempt"
  | "releaseStartAttempt"
  | "reserve"
  | "findOwned"
  | "requestEnd"
  | "renewAuthority"
  | "reauthorizationCandidates"
  | "reconcile"
>;

type AccessDenial =
  "FREE_LOCKED" | "CONSENT_REQUIRED" | "VOICE_CONSENT_REQUIRED";
export type VoiceAccessCode =
  | AccessDenial
  | VoiceLedgerError["code"]
  | VoiceAuthorizationError["code"]
  | "PROVIDER_UNAVAILABLE";

export class VoiceAccessError extends Error {
  constructor(readonly code: VoiceAccessCode) {
    super(code);
  }
}

export interface VoiceEligibility {
  policyVersion: string;
  state: "ELIGIBLE" | VoiceAccessCode;
  eligible: boolean;
  packageCode: VoicePlan;
  quota: {
    policyVersion: string;
    quotaDay: string;
    timeZone: string;
    resetAt: string;
    remainingSessions: number;
    remainingSeconds: number;
    reservedSessions: number;
    reservedSeconds: number;
  };
  checkedAt: string;
  voiceConsentVersion: "ai-voice-processing-capstone-v1";
}

const isTerminal = (session: VoiceSession) =>
  ["ENDED", "FAILED", "EXPIRED"].includes(session.state);

const accessDenial = (
  decision: VoiceOwnerDecision,
  now: Date,
): AccessDenial | null => {
  if (
    decision.entitlement.packageCode === "FREE" ||
    !decision.entitlement.validUntil ||
    Date.parse(decision.entitlement.validUntil) <= now.getTime()
  ) {
    return "FREE_LOCKED";
  }
  if (!decision.consent.aiProcessingGranted) return "CONSENT_REQUIRED";
  if (!decision.consent.voiceProcessingGranted) return "VOICE_CONSENT_REQUIRED";
  if (!decision.consent.authorized) return "CONSENT_REQUIRED";
  return null;
};

const authorityDeadline = (decision: VoiceOwnerDecision, now: Date) =>
  new Date(
    Math.min(
      now.getTime() + 10_000,
      Date.parse(decision.consent.checkedAt) + 10_000,
      Date.parse(decision.entitlement.checkedAt) + 10_000,
      Date.parse(decision.entitlement.validUntil ?? "") || now.getTime(),
    ),
  );

const terminalReason = (code: VoiceAccessCode): VoiceTerminalReason => {
  if (code === "CONSENT_REQUIRED") return "CONSENT_REVOKED";
  if (code === "VOICE_CONSENT_REQUIRED") return "VOICE_CONSENT_REVOKED";
  if (code === "FREE_LOCKED") return "ENTITLEMENT_LOST";
  if (code === "PROVIDER_UNAVAILABLE") return "PROVIDER_FAILURE";
  return "OWNER_UNAVAILABLE";
};

export class VoiceAccessService {
  constructor(
    private readonly ledger: VoiceAccessLedger,
    private readonly authorizer: VoiceOwnerAuthorizer,
    private readonly idempotencySecret: Buffer,
    private readonly providerReady: (plan: VoicePlan) => boolean = () => false,
    private readonly clock: () => Date = () => new Date(),
    private readonly telemetry: (outcome: string) => void = () => undefined,
  ) {}

  async eligibility(
    ownerId: string,
    correlationId: string,
  ): Promise<VoiceEligibility> {
    try {
      const decision = await this.authorizer.check(
        ownerId,
        randomUUID(),
        correlationId,
      );
      const now = this.clock();
      const plan = decision.entitlement.packageCode;
      const quota = await this.ledger.snapshot(ownerId, plan, now);
      const state =
        accessDenial(decision, now) ??
        (!this.providerReady(plan) ? "PROVIDER_UNAVAILABLE" : null) ??
        (quota.ownerActive ? "SESSION_LIMIT" : null) ??
        (quota.globalAvailable === 0 ? "GLOBAL_CAPACITY_FULL" : null) ??
        (quota.remainingSessions === 0 || quota.remainingSeconds === 0
          ? "QUOTA_EXHAUSTED"
          : null) ??
        (quota.rateLimited ? "RATE_LIMITED" : null) ??
        "ELIGIBLE";
      this.telemetry(state);
      return {
        policyVersion: VOICE_POLICY_VERSION,
        state,
        eligible: state === "ELIGIBLE",
        packageCode: plan,
        quota: {
          policyVersion: VOICE_POLICY_VERSION,
          quotaDay: quota.quotaDay,
          timeZone: VOICE_TIME_ZONE,
          resetAt: quota.resetAt.toISOString(),
          remainingSessions: quota.remainingSessions,
          remainingSeconds: quota.remainingSeconds,
          reservedSessions: quota.reservedSessions,
          reservedSeconds: quota.reservedSeconds,
        },
        checkedAt: now.toISOString(),
        voiceConsentVersion: "ai-voice-processing-capstone-v1",
      };
    } catch (error) {
      throw this.failure(error);
    }
  }

  async reserve(
    ownerId: string,
    idempotencyKey: string,
    correlationId: string,
  ): Promise<VoiceSession> {
    if (
      !z.uuid().safeParse(ownerId).success ||
      !z.uuid().safeParse(idempotencyKey).success
    ) {
      throw new VoiceAccessError("INVALID_REQUEST");
    }
    const keyHash = this.digest(`voice:start:${ownerId}:${idempotencyKey}`);
    const fingerprint = this.digest(
      `voice:start:${ownerId}:companion-voice-policy-v1:{}`,
    );
    try {
      const existing = await this.ledger.replay(ownerId, keyHash, this.clock());
      if (existing) {
        return isTerminal(existing) || existing.state === "ENDING"
          ? existing
          : await this.authorizeSession(
              ownerId,
              existing.voiceSessionId,
              correlationId,
            );
      }
      const claim = await this.ledger.recordStartAttempt(
        ownerId,
        this.clock(),
        keyHash,
      );
      if (claim.status === "ACCEPTED") {
        const accepted = await this.ledger.replay(
          ownerId,
          keyHash,
          this.clock(),
        );
        if (!accepted) throw new VoiceAccessError("INVALID_SESSION_STATE");
        return isTerminal(accepted) || accepted.state === "ENDING"
          ? accepted
          : await this.authorizeSession(
              ownerId,
              accepted.voiceSessionId,
              correlationId,
            );
      }
      if (claim.status === "PENDING") {
        throw new VoiceAccessError("INVALID_SESSION_STATE");
      }
      try {
        const voiceSessionId = randomUUID();
        const decision = await this.authorizer.check(
          ownerId,
          voiceSessionId,
          correlationId,
        );
        const now = this.clock();
        const denial = accessDenial(decision, now);
        if (denial) throw new VoiceAccessError(denial);
        if (!this.providerReady(decision.entitlement.packageCode)) {
          throw new VoiceAccessError("PROVIDER_UNAVAILABLE");
        }
        const session = await this.ledger.reserve({
          voiceSessionId,
          ownerId,
          keyHash,
          fingerprint,
          claimExpiresAt: claim.expiresAt,
          now,
          authorityExpiresAt: authorityDeadline(decision, now),
          entitlement: {
            packageCode: decision.entitlement.packageCode,
            source: decision.entitlement.source,
            entitlementVersion: decision.entitlement.entitlementVersion,
            checkedAt: new Date(decision.entitlement.checkedAt),
            ...(decision.entitlement.validUntil
              ? { validUntil: new Date(decision.entitlement.validUntil) }
              : {}),
          },
        });
        this.telemetry("ADMITTED");
        return session;
      } finally {
        await this.ledger.releaseStartAttempt(
          ownerId,
          keyHash,
          this.clock(),
          claim.expiresAt,
        );
      }
    } catch (error) {
      throw this.failure(error);
    }
  }

  async authorizeSession(
    ownerId: string,
    voiceSessionId: string,
    correlationId: string,
  ): Promise<VoiceSession> {
    const session = await this.ledger.findOwned(ownerId, voiceSessionId);
    if (!session) throw new VoiceAccessError("SESSION_NOT_FOUND");
    if (isTerminal(session) || session.state === "ENDING")
      throw new VoiceAccessError("SESSION_EXPIRED");
    try {
      const decision = await this.authorizer.check(
        ownerId,
        voiceSessionId,
        correlationId,
      );
      const denial = accessDenial(decision, this.clock());
      if (denial) throw new VoiceAccessError(denial);
      if (!this.providerReady(decision.entitlement.packageCode))
        throw new VoiceAccessError("PROVIDER_UNAVAILABLE");
      return await this.ledger.renewAuthority(
        ownerId,
        voiceSessionId,
        this.clock(),
        authorityDeadline(decision, this.clock()),
      );
    } catch (error) {
      const failure = this.failure(error);
      await this.ledger.requestEnd(
        ownerId,
        voiceSessionId,
        terminalReason(failure.code),
        this.clock(),
      );
      throw failure;
    }
  }

  async reconcile(): Promise<void> {
    await this.ledger.reconcile(this.clock());
    const sessions = await this.ledger.reauthorizationCandidates(this.clock());
    await Promise.all(
      sessions.map(async (session) => {
        try {
          await this.authorizeSession(
            session.ownerId,
            session.voiceSessionId,
            randomUUID(),
          );
        } catch (error) {
          this.telemetry(this.failure(error).code);
        }
      }),
    );
  }

  private digest(value: string): string {
    return createHmac("sha256", this.idempotencySecret)
      .update(value)
      .digest("base64url");
  }

  private failure(error: unknown): VoiceAccessError {
    const failure =
      error instanceof VoiceAccessError
        ? error
        : error instanceof VoiceLedgerError ||
            error instanceof VoiceAuthorizationError
          ? new VoiceAccessError(error.code)
          : new VoiceAccessError("PROVIDER_UNAVAILABLE");
    this.telemetry(failure.code);
    return failure;
  }
}
