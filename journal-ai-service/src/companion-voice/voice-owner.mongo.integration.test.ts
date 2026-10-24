import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import {
  createServer,
  type IncomingMessage,
  type Server,
  type ServerResponse,
} from "node:http";
import { createRequire } from "node:module";
import test from "node:test";
import { fileURLToPath } from "node:url";
import SwaggerParser from "@apidevtools/swagger-parser";
import { MongoClient, type Db } from "mongodb";
import { MongoMemoryReplSet } from "mongodb-memory-server";

import { VoiceAccessError, VoiceAccessService } from "./voice-access.js";
import { CurrentVoiceOwnerAuthorizer } from "./voice-authorization.js";
import {
  MongoVoiceLedger,
  type VoiceGuard,
  type VoiceSession,
} from "./voice-ledger.js";
import { voiceQuotaDay } from "./voice-policy.js";

interface FixtureValidator {
  (value: unknown): boolean;
  errors?: unknown;
}

interface FixtureAjv {
  addFormat(name: string, format: RegExp | ((value: string) => boolean)): void;
  compile(schema: unknown): FixtureValidator;
}

interface OwnerOperation {
  requestBody: { content: Record<string, { schema: unknown }> };
  responses: Record<string, { content: Record<string, { schema: unknown }> }>;
}

interface OwnerContract {
  paths: Record<
    string,
    { "x-mentalbridge-status"?: string; post: OwnerOperation }
  >;
  components: { schemas: Record<string, unknown> };
}

interface OwnerSchemas {
  request: FixtureValidator;
  success: FixtureValidator;
  unavailable: FixtureValidator;
  missing: FixtureValidator;
}

interface FixtureState {
  careStatus: 200 | 404 | 503;
  consultationStatus: 200 | 503;
  aiGranted: boolean;
  voiceGranted: boolean;
  packageCode: "FREE" | "PLUS" | "PREMIUM";
  source: "DEFAULT_FREE" | "PAID" | "DEMO";
  validUntil: string;
  wrongBinding: boolean;
  noStore: boolean;
}

const require = createRequire(import.meta.url);
const migrations = [
  require("../../migrations/010_ai_companion_chat_quotas.cjs"),
  require("../../migrations/013_companion_voice_quota.cjs"),
] as { up(database: Db): Promise<void> }[];
const carePath = "/internal/v1/ai-companion/voice-consent-authorizations";
const consultationPath =
  "/internal/v1/ai-companion/voice-entitlement-authorizations";
const careToken = "synthetic-journal-care-voice-service-credential";
const consultationToken = "synthetic-journal-entitlement-service-credential";
const syntheticNow = new Date("2026-10-24T10:00:00.000Z");

const compileFixture = (schema: unknown): FixtureValidator => {
  const parserPath = require.resolve("@apidevtools/swagger-parser");
  const { default: Ajv2020 } = createRequire(parserPath)(
    "ajv/dist/2020.js",
  ) as {
    default: new (options: object) => FixtureAjv;
  };
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  ajv.addFormat(
    "uuid",
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
  ajv.addFormat(
    "date-time",
    (value) =>
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(
        value,
      ) && Number.isFinite(Date.parse(value)),
  );
  ajv.addFormat("uri", (value) => {
    try {
      return Boolean(new URL(value).protocol);
    } catch {
      return false;
    }
  });
  ajv.addFormat("uri-reference", (value) => {
    try {
      return Boolean(new URL(value, "https://mentalbridge.test").protocol);
    } catch {
      return false;
    }
  });
  return ajv.compile(schema);
};

const ownerSchemas = async (
  file: string,
  path: string,
): Promise<OwnerSchemas> => {
  const api = (await SwaggerParser.validate(
    fileURLToPath(new URL(file, import.meta.url)),
    { resolve: { external: false } },
  )) as unknown as OwnerContract;
  const route = api.paths[path];
  assert.ok(
    route,
    `Owner route must be merged before this integration runs: ${path}`,
  );
  assert.equal(route["x-mentalbridge-status"], "implemented");
  const request = route.post.requestBody.content["application/json"]?.schema;
  const success =
    route.post.responses["200"]?.content["application/json"]?.schema;
  const unavailable =
    route.post.responses["503"]?.content["application/problem+json"]?.schema;
  assert.ok(request && success && unavailable);
  return {
    request: compileFixture(request),
    success: compileFixture(success),
    unavailable: compileFixture(unavailable),
    missing: compileFixture(api.components.schemas.Problem),
  };
};

const validateFixture = (validate: FixtureValidator, value: unknown) => {
  assert.equal(validate(value), true, JSON.stringify(validate.errors));
};

const workerGuard = (session: VoiceSession, sequence: number): VoiceGuard => {
  assert.ok(session.attemptId && session.leaseId);
  return {
    attemptId: session.attemptId,
    leaseId: session.leaseId,
    generation: session.capacityGeneration,
    sequence,
  };
};

const denied =
  (code: string) =>
  (error: unknown): boolean =>
    error instanceof VoiceAccessError && error.code === code;

void test("contract-backed synthetic owner HTTP decisions govern the real Mongo voice ledger", async (t) => {
  const [careSchemas, consultationSchemas] = await Promise.all([
    ownerSchemas("../../../contracts/openapi/care-service-v1.yaml", carePath),
    ownerSchemas(
      "../../../contracts/openapi/consultation-service-v1.yaml",
      consultationPath,
    ),
  ]);
  const externalUri = process.env.JOURNAL_INTEGRATION_MONGODB_URI;
  const mongo = externalUri
    ? undefined
    : await MongoMemoryReplSet.create({ replSet: { count: 1 } });
  const uri = externalUri ?? mongo?.getUri();
  assert.ok(uri);
  const client = new MongoClient(uri);
  await client.connect();
  const databases: Db[] = [];
  const servers: Server[] = [];
  const fixtureFailures: unknown[] = [];
  t.after(async () => {
    try {
      for (const server of servers) {
        server.closeAllConnections();
        await new Promise<void>((resolve) => {
          server.close(() => {
            resolve();
          });
        });
      }
      for (const database of databases) await database.dropDatabase();
    } finally {
      await client.close();
      await mongo?.stop();
    }
    assert.deepEqual(fixtureFailures, []);
  });

  let fixtureNumber = 0;
  const fixture = async () => {
    const ownerId = randomUUID();
    const databaseName = `voice_owner_verify_${String(Date.now())}_${String(fixtureNumber++)}`;
    assert.match(databaseName, /^voice_owner_verify_\d+_\d+$/);
    const database = client.db(databaseName);
    assert.equal((await database.listCollections().toArray()).length, 0);
    databases.push(database);
    for (const migration of migrations) await migration.up(database);
    let now = new Date(syntheticNow);
    const state: FixtureState = {
      careStatus: 200,
      consultationStatus: 200,
      aiGranted: true,
      voiceGranted: true,
      packageCode: "PLUS",
      source: "PAID",
      validUntil: new Date(now.getTime() + 86_400_000).toISOString(),
      wrongBinding: false,
      noStore: true,
    };
    const calls: { owner: "care" | "consultation"; voiceSessionId: string }[] =
      [];
    const serve = async (isCare: boolean) => {
      const handle = async (
        request: IncomingMessage,
        response: ServerResponse,
      ) => {
        assert.equal(request.method, "POST");
        assert.equal(request.url, isCare ? carePath : consultationPath);
        assert.equal(request.headers.authorization, undefined);
        assert.equal(request.headers.cookie, undefined);
        assert.equal(
          request.headers["x-mentalbridge-voice-care-token"],
          isCare ? careToken : undefined,
        );
        assert.equal(
          request.headers["x-mentalbridge-voice-entitlement-token"],
          isCare ? undefined : consultationToken,
        );
        assert.equal(
          request.headers["x-mentalbridge-voice-worker-token"],
          undefined,
        );
        assert.equal(typeof request.headers["x-correlation-id"], "string");
        const chunks: Buffer[] = [];
        for await (const chunk of request) {
          assert.ok(Buffer.isBuffer(chunk));
          chunks.push(chunk);
        }
        const body: unknown = JSON.parse(
          Buffer.concat(chunks).toString("utf8"),
        );
        const schemas = isCare ? careSchemas : consultationSchemas;
        validateFixture(schemas.request, body);
        const binding = body as {
          ownerId: string;
          voiceSessionId: string;
          purpose: string;
        };
        assert.equal(binding.ownerId, ownerId);
        calls.push({
          owner: isCare ? "care" : "consultation",
          voiceSessionId: binding.voiceSessionId,
        });
        const status = isCare ? state.careStatus : state.consultationStatus;
        const value =
          status !== 200
            ? {
                type: `https://mentalbridge.test/problems/${String(status)}`,
                title:
                  status === 404
                    ? "Care prerequisite was not found"
                    : "Owner authorization unavailable",
                status,
                code:
                  status === 404
                    ? "PROFILE_NOT_FOUND"
                    : isCare
                      ? "CONSENT_UNAVAILABLE"
                      : "ENTITLEMENT_UNAVAILABLE",
                correlationId: request.headers["x-correlation-id"],
              }
            : isCare
              ? {
                  ...binding,
                  aiProcessingGranted: state.aiGranted,
                  voiceProcessingGranted: state.voiceGranted,
                  voiceConsentVersion: "ai-voice-processing-capstone-v1",
                  authorized: state.aiGranted && state.voiceGranted,
                  checkedAt: now.toISOString(),
                }
              : {
                  ...binding,
                  packageCode: state.packageCode,
                  source: state.source,
                  entitlementVersion: `${state.source.toLowerCase()}:synthetic-v1`,
                  ...(state.packageCode === "FREE"
                    ? {}
                    : { validUntil: state.validUntil }),
                  checkedAt: now.toISOString(),
                };
        validateFixture(
          status === 200
            ? schemas.success
            : status === 404
              ? schemas.missing
              : schemas.unavailable,
          value,
        );
        if (state.wrongBinding && isCare && "voiceSessionId" in value)
          value.voiceSessionId = randomUUID();
        response.writeHead(status, {
          "Content-Type":
            status === 200 ? "application/json" : "application/problem+json",
          "Cache-Control": state.noStore ? "no-store" : "public",
        });
        response.end(JSON.stringify(value));
      };
      const server = createServer((request, response) => {
        void handle(request, response).catch((error: unknown) => {
          fixtureFailures.push(error);
          response.writeHead(500, { "Cache-Control": "no-store" });
          response.end();
        });
      });
      servers.push(server);
      await new Promise<void>((resolve) =>
        server.listen(0, "127.0.0.1", resolve),
      );
      const address = server.address();
      assert.ok(address && typeof address !== "string");
      return `http://127.0.0.1:${String(address.port)}`;
    };
    const [careBaseUrl, consultationBaseUrl] = await Promise.all([
      serve(true),
      serve(false),
    ]);
    const ledger = new MongoVoiceLedger(client, databaseName);
    const clock = () => new Date(now);
    const authorizer = new CurrentVoiceOwnerAuthorizer(
      {
        careBaseUrl,
        consultationBaseUrl,
        careToken,
        entitlementToken: consultationToken,
      },
      clock,
    );
    const service = new VoiceAccessService(
      ledger,
      authorizer,
      Buffer.alloc(32, 83),
      () => true,
      clock,
    );
    const noReservation = async () => {
      assert.equal(
        await database.collection("ai_voice_sessions").countDocuments(),
        0,
      );
      const quota = await ledger.snapshot(ownerId, "PLUS", now);
      assert.equal(quota.reservedSessions, 0);
      assert.equal(quota.reservedSeconds, 0);
      assert.equal(quota.globalAvailable, 2);
      assert.equal(
        await database
          .collection("ai_voice_daily_usage")
          .countDocuments({ "pendingAttempts.0": { $exists: true } }),
        0,
      );
    };
    return {
      ownerId,
      database,
      state,
      calls,
      ledger,
      service,
      noReservation,
      advance: (milliseconds: number) => {
        now = new Date(now.getTime() + milliseconds);
      },
      clock,
    };
  };

  await t.test(
    "missing Care route/prerequisite and current missing grant fail without quota reservation",
    async () => {
      const f = await fixture();
      f.state.careStatus = 404;
      await assert.rejects(
        f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
        denied("CONSENT_UNAVAILABLE"),
      );
      await f.noReservation();
      f.state.careStatus = 200;
      f.state.voiceGranted = false;
      await assert.rejects(
        f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
        denied("VOICE_CONSENT_REQUIRED"),
      );
      await f.noReservation();
    },
  );

  for (const source of ["PAID", "DEMO"] as const) {
    await t.test(
      `${source} admission rechecks accepted replay, settles voice, and preserves text quota`,
      async () => {
        const f = await fixture();
        f.state.source = source;
        const sentinel = {
          _id: `${f.ownerId}:${voiceQuotaDay(f.clock())}`,
          ownerAccountId: f.ownerId,
          localDate: voiceQuotaDay(f.clock()),
          plan: "PLUS",
          successfulAnswers: 1,
          usedTokens: 123,
          reservations: [],
          expiresAt: new Date(f.clock().getTime() + 86_400_000),
        };
        const text = f.database.collection<{ _id: string }>(
          "ai_companion_quota_ledgers",
        );
        await text.insertOne(sentinel);
        const before = await text.findOne({ _id: sentinel._id });
        const key = randomUUID();
        const reserved = await f.service.reserve(f.ownerId, key, randomUUID());
        assert.equal(reserved.entitlement.source, source);
        f.advance(100);
        const replay = await f.service.reserve(f.ownerId, key, randomUUID());
        assert.equal(replay.voiceSessionId, reserved.voiceSessionId);
        assert.deepEqual(
          f.calls
            .filter((call) => call.owner === "care")
            .map((call) => call.voiceSessionId),
          [reserved.voiceSessionId, reserved.voiceSessionId],
        );
        assert.equal(
          f.calls.filter((call) => call.owner === "consultation").length,
          2,
        );
        assert.equal(
          (await f.ledger.availability(f.ownerId, f.clock())).dailyAttempts,
          1,
        );
        const dispatched = await f.ledger.claimDispatch(
          reserved.voiceSessionId,
          f.clock(),
        );
        f.advance(100);
        await f.ledger.transition(
          reserved.voiceSessionId,
          workerGuard(dispatched, 1),
          "ACTIVE",
          f.clock(),
        );
        f.advance(100);
        await f.ledger.firstReply(
          reserved.voiceSessionId,
          workerGuard(dispatched, 2),
          f.clock(),
        );
        f.advance(500);
        await f.ledger.requestEnd(
          f.ownerId,
          reserved.voiceSessionId,
          "USER_ENDED",
          f.clock(),
        );
        const settled = await f.ledger.settle(
          reserved.voiceSessionId,
          workerGuard(dispatched, 3),
          "PROVIDER_CONFIRMED_STOPPED",
          f.clock(),
        );
        assert.equal(settled.chargedSeconds, 1);
        assert.equal(settled.deliveredSession, true);
        assert.equal(
          (await f.ledger.availability(f.ownerId, f.clock())).globalOccupied,
          0,
        );
        assert.deepEqual(await text.findOne({ _id: sentinel._id }), before);
      },
    );
  }

  for (const consent of ["aiGranted", "voiceGranted"] as const) {
    await t.test(
      `${consent} revocation ends issued dispatch and keeps possible media capacity`,
      async () => {
        const f = await fixture();
        const reserved = await f.service.reserve(
          f.ownerId,
          randomUUID(),
          randomUUID(),
        );
        await f.ledger.claimDispatch(reserved.voiceSessionId, f.clock());
        f.advance(100);
        f.state[consent] = false;
        await assert.rejects(
          f.service.authorizeSession(
            f.ownerId,
            reserved.voiceSessionId,
            randomUUID(),
          ),
          denied(
            consent === "aiGranted"
              ? "CONSENT_REQUIRED"
              : "VOICE_CONSENT_REQUIRED",
          ),
        );
        assert.equal(
          (
            await f.ledger.findOwned(
              f.ownerId,
              reserved.voiceSessionId,
              f.clock(),
            )
          )?.state,
          "ENDING",
        );
        assert.equal(
          (await f.ledger.availability(f.ownerId, f.clock())).ownerOccupied,
          true,
        );
        assert.equal(
          (await f.ledger.snapshot(f.ownerId, "PLUS", f.clock()))
            .reservedSeconds,
          300,
        );
      },
    );
  }

  for (const change of ["downgrade", "expiry", "owner-outage"] as const) {
    await t.test(
      `live authority ends on ${change} without releasing uncertain media`,
      async () => {
        const f = await fixture();
        const reserved = await f.service.reserve(
          f.ownerId,
          randomUUID(),
          randomUUID(),
        );
        await f.ledger.claimDispatch(reserved.voiceSessionId, f.clock());
        f.advance(100);
        if (change === "downgrade") {
          f.state.packageCode = "FREE";
          f.state.source = "DEFAULT_FREE";
        } else if (change === "expiry") {
          f.state.validUntil = f.clock().toISOString();
        } else {
          f.state.consultationStatus = 503;
        }
        await assert.rejects(
          f.service.authorizeSession(
            f.ownerId,
            reserved.voiceSessionId,
            randomUUID(),
          ),
          denied(
            change === "downgrade" ? "FREE_LOCKED" : "ENTITLEMENT_UNAVAILABLE",
          ),
        );
        const ended = await f.ledger.findOwned(
          f.ownerId,
          reserved.voiceSessionId,
          f.clock(),
        );
        assert.equal(ended?.state, "ENDING");
        assert.equal(
          ended.terminalReason,
          change === "downgrade" ? "ENTITLEMENT_LOST" : "OWNER_UNAVAILABLE",
        );
        assert.equal(
          (await f.ledger.availability(f.ownerId, f.clock())).ownerOccupied,
          true,
        );
      },
    );
  }

  await t.test(
    "FREE and expired entitlement decisions deny admission before reservation",
    async () => {
      const f = await fixture();
      f.state.packageCode = "FREE";
      f.state.source = "DEFAULT_FREE";
      await assert.rejects(
        f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
        denied("FREE_LOCKED"),
      );
      await f.noReservation();
      f.state.packageCode = "PLUS";
      f.state.source = "PAID";
      f.state.validUntil = f.clock().toISOString();
      await assert.rejects(
        f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
        denied("ENTITLEMENT_UNAVAILABLE"),
      );
      await f.noReservation();
    },
  );

  for (const owner of ["care", "consultation"] as const) {
    await t.test(
      `${owner} 503 fails closed and releases only the pending attempt`,
      async () => {
        const f = await fixture();
        if (owner === "care") f.state.careStatus = 503;
        else f.state.consultationStatus = 503;
        await assert.rejects(
          f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
          denied(
            owner === "care"
              ? "CONSENT_UNAVAILABLE"
              : "ENTITLEMENT_UNAVAILABLE",
          ),
        );
        await f.noReservation();
        assert.equal(
          (await f.ledger.availability(f.ownerId, f.clock())).dailyAttempts,
          1,
        );
      },
    );
  }

  for (const failure of ["binding", "cache"] as const) {
    await t.test(
      `valid JSON with invalid ${failure} cannot authorize persistence`,
      async () => {
        const f = await fixture();
        f.state.wrongBinding = failure === "binding";
        f.state.noStore = failure !== "cache";
        await assert.rejects(
          f.service.reserve(f.ownerId, randomUUID(), randomUUID()),
          denied("CONSENT_UNAVAILABLE"),
        );
        await f.noReservation();
      },
    );
  }
});
