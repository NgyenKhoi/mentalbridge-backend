const quota = require("./013_companion_voice_quota.cjs");
const fences = require("./015_companion_voice_turn_fences.cjs");
const handoff = require("./016_companion_voice_context_handoff.cjs");

// companion-voice-policy-v1 now budgets minutes, not sessions (owner 2026-11-27):
// one call may use the whole day (PREMIUM 1,800 s) and a day allows up to 10
// successful sessions, matching the 10-starts-per-day fair-use cap.
const MAX_SESSION_SECONDS = 1800;
const MAX_SESSIONS = 10;

// Raises every `maximum` found at the given property names, anywhere in the schema.
const raise = (schema, limits) => {
  const copy = structuredClone(schema);
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    for (const [key, value] of Object.entries(node)) {
      if (
        key in limits &&
        value &&
        typeof value === "object" &&
        "maximum" in value
      )
        value.maximum = limits[key];
      visit(value);
    }
  };
  visit(copy);
  return copy;
};

const sessionValidator = raise(handoff.sessionValidator, {
  admittedSeconds: MAX_SESSION_SECONDS,
  chargedSeconds: MAX_SESSION_SECONDS,
  activeMilliseconds: MAX_SESSION_SECONDS * 1000,
});
const usageValidator = raise(quota.usageValidator, {
  usedSessions: MAX_SESSIONS,
  reservedSeconds: MAX_SESSION_SECONDS,
});
const commandValidator = raise(fences.commandValidator, {
  admittedSeconds: MAX_SESSION_SECONDS,
});

// A collection an earlier migration never created has no limit to raise.
const apply = async (db, collection, validator) => {
  if (!(await db.listCollections({ name: collection }).hasNext())) return;
  await db.command({
    collMod: collection,
    validator,
    validationLevel: "strict",
    validationAction: "error",
  });
};

module.exports = {
  sessionValidator,
  usageValidator,
  commandValidator,
  async up(db) {
    await apply(db, "ai_voice_sessions", sessionValidator);
    await apply(db, "ai_voice_daily_usage", usageValidator);
    await apply(db, "ai_voice_media_commands", commandValidator);
  },
  async down(db) {
    const over = await Promise.all([
      db
        .collection("ai_voice_sessions")
        .countDocuments({ admittedSeconds: { $gt: 600 } }),
      db.collection("ai_voice_daily_usage").countDocuments({
        $or: [{ usedSessions: { $gt: 3 } }, { reservedSeconds: { $gt: 600 } }],
      }),
    ]);
    if (over.some((count) => count > 0))
      throw new Error(
        "Voice rows already exceed the session-based limits; rollback must wait for their retention purge",
      );
    await apply(db, "ai_voice_sessions", handoff.sessionValidator);
    await apply(db, "ai_voice_daily_usage", quota.usageValidator);
    await apply(db, "ai_voice_media_commands", fences.commandValidator);
  },
};
