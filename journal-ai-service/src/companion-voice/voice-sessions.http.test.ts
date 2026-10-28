import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import type { Server } from "node:http";
import { createRequire } from "node:module";
import test from "node:test";
import { fileURLToPath } from "node:url";
import SwaggerParser from "@apidevtools/swagger-parser";
import { exportSPKI, generateKeyPair, SignJWT } from "jose";
import request from "supertest";
import { createApplication } from "../application.js";
import { loadConfiguration } from "../configuration/configuration.js";
import { VoiceAccessError, type VoiceAccessCode } from "./voice-access.js";
import { VoiceLedgerError } from "./voice-ledger.js";
import type { VoiceSessionOperations } from "./voice-session-boundary.js";
import { VoiceTurnError } from "./voice-turn.js";

interface ContractValidator {
  (value: unknown): boolean;
  errors?: unknown;
}
interface ContractAjv {
  addFormat(name: string, format: RegExp | ((value: string) => boolean)): void;
  compile(schema: unknown): ContractValidator;
}

const responseSchemas = async () => {
  const contract = (await SwaggerParser.validate(
    fileURLToPath(
      new URL(
        "../../../contracts/openapi/journal-ai-service-v1.yaml",
        import.meta.url,
      ),
    ),
    { resolve: { external: false } },
  )) as unknown as { components: { schemas: Record<string, unknown> } };
  const require = createRequire(import.meta.url);
  const { default: Ajv2020 } = createRequire(
    require.resolve("@apidevtools/swagger-parser"),
  )("ajv/dist/2020.js") as {
    default: new (options: object) => ContractAjv;
  };
  const schemas = new Map<string, ContractValidator>();
  for (const name of [
    "VoiceSession",
    "VoiceConnection",
    "VoiceAgentLease",
    "VoiceAgentControlResponse",
    "VoiceProblem",
    "VoiceReply",
  ]) {
    const ajv = new Ajv2020({ strict: true, allErrors: true });
    ajv.addFormat("uuid", /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i);
    ajv.addFormat(
      "date-time",
      (value) =>
        Number.isFinite(Date.parse(value)) && /^\d{4}-\d{2}-\d{2}T/.test(value),
    );
    ajv.addFormat("date", /^\d{4}-\d{2}-\d{2}$/);
    ajv.addFormat("uri", (value) => {
      try {
        return Boolean(new URL(value).protocol);
      } catch {
        return false;
      }
    });
    schemas.set(name, ajv.compile(contract.components.schemas[name]));
  }
  return (name: string, body: unknown) => {
    const validate = schemas.get(name);
    assert.ok(validate, `Missing active response schema: ${name}`);
    assert.equal(validate(body), true, JSON.stringify(validate.errors));
  };
};

void test("Voice public and worker REST separate identities, validate closed commands and sanitize every documented failure", async () => {
  const assertContract = await responseSchemas();
  const owner = randomUUID();
  const id = randomUUID();
  const attemptId = randomUUID();
  const dispatchId = randomUUID();
  const leaseId = randomUUID();
  const turnId = randomUUID();
  const now = new Date();
  const hardStop = new Date(now.getTime() + 300_000).toISOString();
  const key = randomUUID();
  const worker = "synthetic-worker-only-credential-32-characters";
  const { privateKey, publicKey } = await generateKeyPair("RS256", {
    extractable: true,
  });
  const environment = {
    NODE_ENV: "test",
    CI: "true",
    IDENTITY_JWT_ISSUER: "https://identity.voice-session.test",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "voice-http-key",
    IDENTITY_JWT_PUBLIC_KEY: await exportSPKI(publicKey),
    JOURNAL_AI_LOG_LEVEL: "silent",
    JOURNAL_AI_ANALYSIS_ENABLED: "false",
    JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN: worker,
  };
  const jwt = (account = owner, role = "USER") =>
    new SignJWT({ roles: [role] })
      .setProtectedHeader({
        alg: "RS256",
        kid: environment.IDENTITY_JWT_KEY_ID,
      })
      .setIssuer(environment.IDENTITY_JWT_ISSUER)
      .setAudience(environment.IDENTITY_JWT_AUDIENCE)
      .setSubject(account)
      .setIssuedAt()
      .setNotBefore("0s")
      .setExpirationTime("5m")
      .setJti(randomUUID())
      .sign(privateKey);
  const view = {
    voiceSessionId: id,
    policyVersion: "companion-voice-policy-v1",
    state: "CONNECTING" as const,
    createdAt: now.toISOString(),
    authorizedAt: now.toISOString(),
    hardStopAt: hardStop,
    quotaDay: "2026-10-25",
    admittedSeconds: 300,
    chargedSeconds: 0,
    deliveredSession: false,
  };
  let calls = 0;
  let failure: VoiceAccessCode | null = null;
  let turnFailure: "PROVIDER_TIMEOUT" | "INVALID_FINAL_TRANSCRIPT" | null =
    null;
  const checked = (account: string, sessionId: string = id) => {
    calls += 1;
    if (account !== owner || sessionId !== id)
      throw new VoiceLedgerError("SESSION_NOT_FOUND");
    if (failure) throw new VoiceAccessError(failure);
  };
  const operations: VoiceSessionOperations = {
    start: (account, acceptedKey) => {
      assert.equal(acceptedKey, key);
      checked(account);
      return Promise.resolve(view);
    },
    status: (account, sessionId) => {
      checked(account, sessionId);
      return Promise.resolve(view);
    },
    end: (account, sessionId, acceptedKey) => {
      assert.equal(acceptedKey, key);
      checked(account, sessionId);
      return Promise.resolve({
        ...view,
        state: "ENDED",
        terminalReason: "USER_ENDED",
      });
    },
    connection: (account, sessionId, acceptedKey) => {
      assert.equal(acceptedKey, key);
      checked(account, sessionId);
      return Promise.resolve({
        voiceSessionId: id,
        transport: "LIVEKIT_CLOUD",
        roomName: `ai-voice-${id}`,
        agentIdentity: `va-${id}`,
        serverUrl: "wss://synthetic.livekit.cloud",
        participantToken: "synthetic-memory-only-token-not-recorded",
        tokenExpiresAt: new Date(now.getTime() + 60_000).toISOString(),
        hardStopAt: hardStop,
        audioOnly: true,
      });
    },
    claim: (sessionId, attempt, dispatch) => {
      assert.equal(sessionId, id);
      assert.equal(attempt, attemptId);
      assert.equal(dispatch, dispatchId);
      calls += 1;
      return Promise.resolve({
        voiceSessionId: id,
        attemptId,
        leaseId,
        validUntil: new Date(now.getTime() + 10_000).toISOString(),
        hardStopAt: hardStop,
        policyVersion: "companion-voice-policy-v1",
      });
    },
    control: (sessionId, input) => {
      assert.equal(sessionId, id);
      assert.equal(input.voiceSessionId, id);
      calls += 1;
      return Promise.resolve({
        action: "CONTINUE",
        control: {
          schemaVersion: 1,
          kind: "LEASE_RENEWED",
          commandId: randomUUID(),
          voiceSessionId: id,
          attemptId,
          sequence: 1,
          issuedAt: now.toISOString(),
          expiresAt: new Date(now.getTime() + 10_000).toISOString(),
          payload: {
            leaseId,
            validUntil: new Date(now.getTime() + 10_000).toISOString(),
            hardStopAt: hardStop,
            admittedSeconds: 300,
          },
        },
      });
    },
  };
  const app = await createApplication(loadConfiguration(environment), {
    readinessProbe: { check: () => Promise.resolve() },
    companionVoice: {
      sessions: operations,
      maintenance: false,
      turns: {
        generate: (sessionId, acceptedKey, _input, _correlation, signal) => {
          assert.equal(sessionId, id);
          assert.equal(acceptedKey, key);
          assert.equal(signal?.aborted, false);
          if (turnFailure) throw new VoiceTurnError(turnFailure);
          return Promise.resolve({
            voiceSessionId: id,
            turnId,
            replyId: randomUUID(),
            language: "vi-VN" as const,
            spokenText: "Mình đang lắng nghe. Bạn có thể chia sẻ thêm không?",
            expiresAt: new Date(now.getTime() + 10000).toISOString(),
            policyVersion: "companion-voice-policy-v1" as const,
          });
        },
      },
    },
  });
  await app.init();
  const server = app.getHttpServer() as Server;
  const token = await jwt();
  const root = "/api/v1/ai-companion/voice-sessions";
  const authenticated = (method: "get" | "post", path = root) => {
    const client = request(server);
    return (method === "get" ? client.get(path) : client.post(path))
      .set("Authorization", `Bearer ${token}`)
      .set("Idempotency-Key", key);
  };
  const assertProblem = (body: unknown, code: string, status: number) => {
    assertContract("VoiceProblem", body);
    assert.ok(body && typeof body === "object");
    const problem = body as Record<string, unknown>;
    assert.deepEqual(Object.keys(problem).sort(), [
      "code",
      "correlationId",
      "status",
      "title",
      "type",
    ]);
    assert.equal(problem.code, code);
    assert.equal(problem.status, status);
    assert.match(String(problem.correlationId), /^[0-9a-f-]{36}$/i);
    assert.match(String(problem.type), /^https:\/\//);
  };
  try {
    const unauthenticated = await request(server)
      .post(root)
      .send({})
      .expect(401)
      .expect("Cache-Control", "no-store");
    assertProblem(unauthenticated.body, "UNAUTHENTICATED", 401);
    await request(server)
      .post(root)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send({})
      .expect(401);
    await request(server)
      .post(root)
      .set("Authorization", `Bearer ${await jwt(owner, "SPECIALIST")}`)
      .send({})
      .expect(403);
    await request(server)
      .post(root)
      .set("Authorization", "Bearer forged")
      .send({})
      .expect(401);
    assert.equal(calls, 0);
    for (const body of [
      { ownerId: randomUUID() },
      { roomName: "consultation-foreign" },
      { packageCode: "PREMIUM" },
      { audio: "synthetic-private-input" },
    ]) {
      const invalid = await authenticated("post")
        .send(body)
        .expect(422)
        .expect("Cache-Control", "no-store");
      assertProblem(invalid.body, "INVALID_REQUEST", 422);
    }
    const malformed = await authenticated("post")
      .set("Content-Type", "application/json")
      .set("X-Correlation-Id", "legacy-correlation")
      .send('{"synthetic-private-input":')
      .expect(422)
      .expect("Cache-Control", "no-store");
    assertProblem(malformed.body, "INVALID_REQUEST", 422);
    assert.equal(
      JSON.stringify(malformed.body).includes("synthetic-private-input"),
      false,
    );
    await request(server)
      .post(root)
      .set("Authorization", `Bearer ${token}`)
      .send({})
      .expect(422);
    await authenticated("post")
      .set("Idempotency-Key", "invalid")
      .send({})
      .expect(422);
    assert.equal(calls, 0);
    const start = await authenticated("post")
      .send({})
      .expect(202)
      .expect((response) => {
        assertContract("VoiceSession", response.body);
      })
      .expect("Cache-Control", "no-store");
    assert.equal((start.body as typeof view).voiceSessionId, id);
    await authenticated("get", `${root}/${id}`)
      .expect(200)
      .expect((response) => {
        assertContract("VoiceSession", response.body);
      })
      .expect("Cache-Control", "no-store");
    await request(server)
      .get(`${root}/${id}`)
      .set("Authorization", `Bearer ${await jwt(randomUUID())}`)
      .expect(404)
      .expect("Cache-Control", "no-store");
    await authenticated("get", `${root}/forged-session`).expect(404);
    const connection = await authenticated("post", `${root}/${id}/connection`)
      .send({})
      .expect(200)
      .expect((response) => {
        assertContract("VoiceConnection", response.body);
      })
      .expect("Cache-Control", "no-store");
    assert.equal((connection.body as { audioOnly: boolean }).audioOnly, true);
    await authenticated("post", `${root}/${id}/end`)
      .send({})
      .expect(202)
      .expect((response) => {
        assertContract("VoiceSession", response.body);
      });
    for (const [code, status] of [
      ["FREE_LOCKED", 403],
      ["CONSENT_REQUIRED", 403],
      ["VOICE_CONSENT_REQUIRED", 403],
      ["GLOBAL_CAPACITY_FULL", 429],
      ["QUOTA_EXHAUSTED", 429],
      ["RATE_LIMITED", 429],
      ["SESSION_LIMIT", 409],
      ["IDEMPOTENCY_CONFLICT", 409],
      ["CONSENT_UNAVAILABLE", 503],
      ["ENTITLEMENT_UNAVAILABLE", 503],
      ["PROVIDER_UNAVAILABLE", 503],
    ] as const) {
      failure = code;
      const failed = await authenticated("post")
        .send({})
        .expect(status)
        .expect("Cache-Control", "no-store");
      assertProblem(failed.body, code, status);
    }
    failure = "SESSION_EXPIRED";
    const expired = await authenticated("post", `${root}/${id}/connection`)
      .send({})
      .expect(410);
    assertProblem(expired.body, "SESSION_EXPIRED", 410);
    failure = null;
    const workerRoot = `/internal/v1/ai-companion/voice-sessions/${id}`;
    const claim = { attemptId, dispatchId };
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("Authorization", `Bearer ${token}`)
      .send(claim)
      .expect(401);
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("X-MentalBridge-Voice-Care-Token", worker)
      .send(claim)
      .expect(401);
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("X-MentalBridge-Voice-Worker-Token", "wrong-token")
      .send(claim)
      .expect(401);
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("Authorization", `Bearer ${token}`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send(claim)
      .expect(401);
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send({ ...claim, ownerId: owner })
      .expect(422);
    await request(server)
      .post(`${workerRoot}/agent-claim`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send(claim)
      .expect(200)
      .expect((response) => {
        assertContract("VoiceAgentLease", response.body);
      })
      .expect("Cache-Control", "no-store");
    const control = {
      schemaVersion: 1,
      kind: "MEDIA_READY",
      commandId: randomUUID(),
      voiceSessionId: id,
      attemptId,
      sequence: 1,
      issuedAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + 10_000).toISOString(),
      payload: { leaseId },
    };
    await request(server)
      .post(`${workerRoot}/agent-control`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send({ ...control, kind: "STOP" })
      .expect(422);
    await request(server)
      .post(`${workerRoot}/agent-control`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send({
        ...control,
        payload: { ...control.payload, transcript: "synthetic-private-input" },
      })
      .expect(422);
    await request(server)
      .post(`${workerRoot}/agent-control`)
      .set("X-MentalBridge-Voice-Worker-Token", worker)
      .send(control)
      .expect(200)
      .expect((response) => {
        assertContract("VoiceAgentControlResponse", response.body);
      })
      .expect("Cache-Control", "no-store");
    const turnBody = {
      attemptId,
      leaseId,
      turnId,
      sequence: 1,
      language: "vi-VN",
      isFinal: true,
      finalTranscript: "Hôm nay tôi cảm thấy hơi lo",
    };
    await request(server)
      .post(`${workerRoot}/turns`)
      .set("Authorization", `Bearer ${token}`)
      .send(turnBody)
      .expect(401)
      .expect("Cache-Control", "no-store");
    const turnRequest = () =>
      request(server)
        .post(`${workerRoot}/turns`)
        .set("X-MentalBridge-Voice-Worker-Token", worker)
        .set("Idempotency-Key", key);
    await turnRequest()
      .send({ ...turnBody, ownerId: owner })
      .expect(422);
    await turnRequest()
      .send({ ...turnBody, isFinal: false })
      .expect(422);
    await turnRequest()
      .send({ ...turnBody, language: "en-US" })
      .expect(422);
    await turnRequest()
      .send(turnBody)
      .expect(200)
      .expect("Cache-Control", "no-store")
      .expect((response) => {
        assertContract("VoiceReply", response.body);
      });
    for (const [code, status] of [
      ["PROVIDER_TIMEOUT", 504],
      ["INVALID_FINAL_TRANSCRIPT", 422],
    ] as const) {
      turnFailure = code;
      const failed = await turnRequest()
        .send(turnBody)
        .expect(status)
        .expect("Cache-Control", "no-store");
      assertProblem(failed.body, code, status);
    }
    const legacy = await request(server).get("/api/v1/journals").expect(401);
    assert.equal(
      (legacy.body as { code: string }).code,
      "AUTHENTICATION_REQUIRED",
    );
  } finally {
    await app.close();
  }
});
