export type VoicePlan = "FREE" | "PLUS" | "PREMIUM";

export const VOICE_POLICY_VERSION = "companion-voice-policy-v1";
export const VOICE_TIME_ZONE = "Asia/Ho_Chi_Minh";
export const VOICE_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
export const VOICE_LEASE_MS = 10_000;
export const VOICE_LEASE_RENEWAL_MS = 5_000;

// Minutes are the budget (owner 2026-11-27): a call may use whatever is left of the day; the session count only
// matches the 10-starts-per-day fair-use cap, so short calls never forfeit the remaining minutes.
export const voiceLimits = (plan: VoicePlan) => {
  switch (plan) {
    case "FREE":
      return { sessionsPerDay: 0, dailySeconds: 0, perSessionSeconds: 0 };
    case "PLUS":
      return { sessionsPerDay: 10, dailySeconds: 600, perSessionSeconds: 600 };
    case "PREMIUM":
      return {
        sessionsPerDay: 10,
        dailySeconds: 1800,
        perSessionSeconds: 1800,
      };
  }
};

export const voiceQuotaDay = (now: Date): string =>
  new Intl.DateTimeFormat("en-CA", {
    timeZone: VOICE_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);

export const voiceResetAt = (now: Date): Date =>
  new Date(`${voiceQuotaDay(now)}T17:00:00.000Z`);

export const voiceChargedSeconds = (
  activeMilliseconds: number,
  admittedSeconds: number,
): number => Math.min(admittedSeconds, Math.ceil(activeMilliseconds / 1000));
