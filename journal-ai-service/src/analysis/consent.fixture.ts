import { createCipheriv } from "node:crypto";

import type {
  AiConsentAuthorizations,
  ConsentClient,
  ConsentDecision,
} from "./analysis.js";

const granted: ConsentDecision = { authorized: true, reason: "GRANTED" };

export const aiConsentAuthorizationsFixture = (
  context: ConsentDecision = granted,
  chat: ConsentDecision = granted,
): AiConsentAuthorizations => {
  const level = (
    decision: ConsentDecision,
    consentType: "AI_PROCESSING" | "AI_CONTEXT_PROCESSING",
    currentVersion: string,
  ) => ({
    ...decision,
    consentType,
    policyVersion:
      decision.reason === "MISSING"
        ? null
        : (decision.policyVersion ?? currentVersion),
    decidedAt:
      decision.reason === "MISSING"
        ? null
        : (decision.decidedAt ?? "2026-11-06T04:00:00Z"),
  });
  return {
    chat: level(chat, "AI_PROCESSING", "ai-processing-capstone-v2"),
    context: level(
      context,
      "AI_CONTEXT_PROCESSING",
      "ai-context-processing-capstone-v1",
    ),
    checkedAt: "2026-11-06T04:00:01Z",
  };
};

export const contextConsentClient = (
  client: Pick<ConsentClient, "check">,
): ConsentClient => ({
  check: client.check,
  snapshot: async (bearer, correlationId) =>
    aiConsentAuthorizationsFixture(await client.check(bearer, correlationId)),
});

/** Withdrawal fixture: L2 lost by revocation or by L1 withdrawal (DEPENDENCY_REQUIRED). */
export const withdrawnAuthorizations = (
  reason: "REVOKED" | "DEPENDENCY_REQUIRED",
): AiConsentAuthorizations =>
  aiConsentAuthorizationsFixture(
    { authorized: false, reason },
    reason === "DEPENDENCY_REQUIRED"
      ? { authorized: false, reason: "REVOKED" }
      : granted,
  );

/** A source read that stays in flight until the test resumes it. */
export const pausedRead = () => {
  let release!: () => void;
  let markStarted!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const started = new Promise<void>((resolve) => {
    markStarted = resolve;
  });
  return {
    started,
    resume: () => {
      release();
    },
    wait: () => {
      markStarted();
      return gate;
    },
  };
};

/** A synthetic journal row holding genuine AES-256-GCM ciphertext. */
export const sealedJournalRow = (
  key: Buffer,
  ownerAccountId: string,
  journalId: string,
) => {
  const iv = Buffer.alloc(12, 3);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(`${ownerAccountId}:${journalId}:1`));
  const ciphertext = Buffer.concat([
    cipher.update("SYNTHETIC_PRIVATE_TEXT"),
    cipher.final(),
  ]);
  return {
    _id: journalId,
    ownerAccountId,
    deleted: false,
    currentRevision: 1,
    revisions: [
      { revision: 1, content: { iv, ciphertext, tag: cipher.getAuthTag() } },
    ],
  };
};
