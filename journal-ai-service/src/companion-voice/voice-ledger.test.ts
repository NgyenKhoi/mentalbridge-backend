import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

import {
  VOICE_LEASE_MS,
  VOICE_LEASE_RENEWAL_MS,
  VOICE_POLICY_VERSION,
  VOICE_RETENTION_MS,
  VOICE_TIME_ZONE,
  voiceChargedSeconds,
  voiceLimits,
  voiceQuotaDay,
  voiceResetAt,
} from "./voice-policy.js";
import { VoiceLedgerError } from "./voice-ledger.js";

void test("voice limits remain aligned with the approved independent voice policy", async () => {
  const policy = JSON.parse(
    await readFile(
      new URL(
        "../../../docs/policies/companion-voice-policy-v1.json",
        import.meta.url,
      ),
      "utf8",
    ),
  ) as {
    policyVersion: string;
    timeZone: string;
    packages: Record<
      "FREE" | "PLUS" | "PREMIUM",
      {
        sessionsPerDay: number;
        dailySeconds: number;
        perSessionSeconds: number;
      }
    >;
    limits: { authorizationLeaseSeconds: number; leaseRenewalSeconds: number };
    privacy: { metadataRetentionDays: number };
  };
  assert.equal(VOICE_POLICY_VERSION, policy.policyVersion);
  assert.equal(VOICE_TIME_ZONE, policy.timeZone);
  for (const plan of ["FREE", "PLUS", "PREMIUM"] as const) {
    const { sessionsPerDay, dailySeconds, perSessionSeconds } =
      policy.packages[plan];
    assert.deepEqual(voiceLimits(plan), {
      sessionsPerDay,
      dailySeconds,
      perSessionSeconds,
    });
  }
  assert.equal(VOICE_LEASE_MS, policy.limits.authorizationLeaseSeconds * 1000);
  assert.equal(
    VOICE_LEASE_RENEWAL_MS,
    policy.limits.leaseRenewalSeconds * 1000,
  );
  assert.equal(
    VOICE_RETENTION_MS,
    policy.privacy.metadataRetentionDays * 86_400_000,
  );
});

void test("voice quota days and reset use Vietnam midnight across UTC and year boundaries", () => {
  const before = new Date("2026-12-31T16:59:59.999Z");
  const after = new Date("2026-12-31T17:00:00.000Z");
  assert.equal(voiceQuotaDay(before), "2026-12-31");
  assert.equal(voiceResetAt(before).toISOString(), after.toISOString());
  assert.equal(voiceQuotaDay(after), "2027-01-01");
  assert.equal(voiceResetAt(after).toISOString(), "2027-01-01T17:00:00.000Z");
});

void test("voice billing rounds summed ACTIVE intervals once and caps the admission", () => {
  assert.equal(voiceChargedSeconds(0, 300), 0);
  assert.equal(voiceChargedSeconds(200 + 300, 300), 1);
  assert.equal(voiceChargedSeconds(1000, 300), 1);
  assert.equal(voiceChargedSeconds(1001, 300), 2);
  assert.equal(voiceChargedSeconds(301_000, 300), 300);
});

void test("ledger failures expose stable problem codes without content", () => {
  const failure = new VoiceLedgerError("GLOBAL_CAPACITY_FULL");
  assert.equal(failure.code, "GLOBAL_CAPACITY_FULL");
  assert.equal(failure.message, "GLOBAL_CAPACITY_FULL");
  assert.equal(failure.name, "VoiceLedgerError");
});
