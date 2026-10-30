import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import test from "node:test";
import { decodeJwt, jwtVerify } from "jose";
import { AgentDispatchClient, RoomServiceClient } from "livekit-server-sdk";
import { loadConfiguration } from "../configuration/configuration.js";
import {
  LiveKitVoiceMedia,
  VoiceMediaError,
  type VoiceMediaConfiguration,
  type VoiceMediaReference,
} from "./voice-media-provider.js";

const configuration: VoiceMediaConfiguration = {
  enabled: true,
  approvedVersion: "synthetic-proof-v1",
  serverUrl: "wss://voice-test.livekit.cloud",
  apiKey: "synthetic-livekit-api-key",
  apiSecret: "synthetic-livekit-api-secret-32-characters",
  agentName: "mentalbridge-ai-voice-v1",
  workerToken: "synthetic-voice-worker-token-32-characters",
};
const reference = (): VoiceMediaReference => {
  const attemptId = randomUUID();
  return {
    voiceSessionId: randomUUID(),
    attemptId,
    dispatchId: randomUUID(),
    roomName: `ai-voice-${randomUUID()}`,
    participantIdentity: `vu-${randomUUID()}`,
    agentIdentity: `va-${attemptId}`,
    hardStopAt: new Date(Date.now() + 300_000),
    providerDispatchId: null,
  };
};

void test("official LiveKit SDK sends bounded AI-only room/explicit-dispatch calls and strict revocation", async (t) => {
  const ref = reference();
  const calls: {
    method: string;
    body: Record<string, unknown>;
    grant: Record<string, unknown>;
  }[] = [];
  const failures: unknown[] = [];
  let roomPresent = false;
  let dispatchPresent = false;
  let unavailable = false;
  let recording = false;
  const room = () => ({
    sid: "RM_synthetic",
    name: ref.roomName,
    metadata: ref.dispatchId,
    maxParticipants: 2,
    activeRecording: recording,
  });
  const dispatch = () => ({
    id: "AD_synthetic",
    room: ref.roomName,
    agentName: configuration.agentName,
    metadata: JSON.stringify({
      voiceSessionId: ref.voiceSessionId,
      attemptId: ref.attemptId,
      dispatchId: ref.dispatchId,
    }),
  });
  const server = createServer((request, response) => {
    void (async () => {
      const authorization = request.headers.authorization;
      assert.ok(authorization);
      assert.ok(authorization.startsWith("Bearer "));
      const { payload } = await jwtVerify(
        authorization.slice(7),
        new TextEncoder().encode(configuration.apiSecret ?? ""),
        { issuer: configuration.apiKey ?? "", algorithms: ["HS256"] },
      );
      const chunks: Buffer[] = [];
      for await (const chunk of request) {
        assert.ok(Buffer.isBuffer(chunk));
        chunks.push(chunk);
      }
      const body = JSON.parse(Buffer.concat(chunks).toString("utf8")) as Record<
        string,
        unknown
      >;
      const method = request.url?.split("/").at(-1) ?? "";
      calls.push({
        method,
        body,
        grant: payload.video as Record<string, unknown>,
      });
      if (unavailable) {
        response.writeHead(503, { "Content-Type": "application/json" });
        response.end(
          JSON.stringify({
            code: "unavailable",
            msg: "synthetic-private-provider-error",
          }),
        );
        return;
      }
      let value: unknown = {};
      if (method === "CreateRoom") {
        roomPresent = true;
        value = room();
      }
      if (method === "CreateDispatch") {
        dispatchPresent = true;
        value = dispatch();
      }
      if (method === "ListRooms")
        value = { rooms: roomPresent ? [room()] : [] };
      if (method === "ListDispatch")
        value = { agentDispatches: dispatchPresent ? [dispatch()] : [] };
      if (method === "ListParticipants")
        value = {
          participants: [
            {
              sid: "PA_user",
              identity: ref.participantIdentity,
              state: "ACTIVE",
            },
            {
              sid: "PA_agent",
              identity: ref.agentIdentity,
              state: "ACTIVE",
              tracks: [
                {
                  sid: "TR_audio",
                  type: "AUDIO",
                  source: "MICROPHONE",
                  muted: false,
                },
              ],
            },
          ],
        };
      if (method === "DeleteDispatch") dispatchPresent = false;
      if (method === "DeleteRoom") roomPresent = false;
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify(value));
    })().catch((error: unknown) => {
      failures.push(error);
      response.writeHead(500);
      response.end();
    });
  });
  await new Promise<void>((resolve) => {
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise<void>((resolve) => {
      server.close(() => {
        resolve();
      });
    });
    assert.deepEqual(failures, []);
  });
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  const host = `http://127.0.0.1:${String(address.port)}`;
  const options = { requestTimeout: 3, failover: false };
  const provider = new LiveKitVoiceMedia(configuration, {
    rooms: new RoomServiceClient(
      host,
      configuration.apiKey ?? "",
      configuration.apiSecret ?? "",
      options,
    ),
    agents: new AgentDispatchClient(
      host,
      configuration.apiKey ?? "",
      configuration.apiSecret ?? "",
      options,
    ),
  });
  await provider.createRoom(ref);
  ref.providerDispatchId = await provider.dispatch(ref);
  assert.equal(ref.providerDispatchId, "AD_synthetic");
  const inspection = await provider.inspect(ref);
  assert.deepEqual(inspection, {
    roomExists: true,
    providerDispatchId: "AD_synthetic",
    ownerConnected: true,
    agentConnected: true,
    agentAudioPublished: true,
  });
  const create = calls.find((call) => call.method === "CreateRoom");
  assert.ok(create);
  assert.equal(create.body.name, ref.roomName);
  assert.equal(create.body.maxParticipants, 2);
  assert.equal(create.body.egress, undefined);
  assert.equal(create.body.agents, undefined);
  assert.equal(create.body.metadata, ref.dispatchId);
  assert.equal(create.grant.roomCreate, true);
  const dispatched = calls.find((call) => call.method === "CreateDispatch");
  assert.ok(dispatched);
  assert.equal(dispatched.body.restartPolicy, "JRP_NEVER");
  assert.equal(dispatched.grant.room, ref.roomName);
  assert.equal(dispatched.grant.roomAdmin, true);
  assert.deepEqual(
    Object.keys(JSON.parse(String(dispatched.body.metadata)) as object).sort(),
    ["attemptId", "dispatchId", "voiceSessionId"],
  );
  const connection = await provider.connection(ref, new Date());
  assert.equal(connection.roomName, ref.roomName);
  assert.equal(connection.agentIdentity, ref.agentIdentity);
  const claims = decodeJwt(connection.participantToken);
  assert.equal(claims.sub, ref.participantIdentity);
  assert.ok(claims.exp);
  assert.equal(
    connection.tokenExpiresAt,
    new Date(claims.exp * 1000).toISOString(),
  );
  assert.ok(claims.exp <= Math.floor(ref.hardStopAt.getTime() / 1000));
  assert.deepEqual(claims.video, {
    roomJoin: true,
    room: ref.roomName,
    canPublish: true,
    canSubscribe: true,
    canPublishSources: ["microphone"],
    canPublishData: false,
    canUpdateOwnMetadata: false,
    roomAdmin: false,
    roomCreate: false,
    roomList: false,
    roomRecord: false,
    ingressAdmin: false,
    agent: false,
  });
  assert.equal(claims.metadata, undefined);
  assert.equal(claims.name, undefined);
  assert.equal(claims.sip, undefined);
  const nearDeadline = { ...ref, hardStopAt: new Date(Date.now() + 10_000) };
  const short = await provider.connection(nearDeadline, new Date());
  assert.ok(
    Date.parse(short.tokenExpiresAt) <= nearDeadline.hardStopAt.getTime(),
  );
  await provider.stop(ref);
  assert.equal(roomPresent, false);
  assert.equal(dispatchPresent, false);
  const removals = calls.filter((call) => call.method === "RemoveParticipant");
  assert.equal(removals.length, 2);
  for (const removal of removals) {
    assert.ok(
      Number(removal.body.revokeTokenTs) >= Math.floor(Date.now() / 1000) - 5,
    );
    assert.ok(
      Number(removal.body.revokeTokenTs) <= Math.floor(Date.now() / 1000) + 60,
    );
  }
  const before = calls.length;
  await assert.rejects(
    provider.createRoom({
      ...ref,
      roomName: "consultation-private-appointment",
    }),
    VoiceMediaError,
  );
  assert.equal(calls.length, before);
  recording = true;
  await assert.rejects(provider.createRoom(ref), VoiceMediaError);
  assert.equal(roomPresent, false);
  recording = false;
  unavailable = true;
  await assert.rejects(
    provider.dispatch(ref),
    (error: unknown) =>
      error instanceof VoiceMediaError &&
      error.message === "PROVIDER_UNAVAILABLE" &&
      error.cause === undefined,
  );
  assert.equal(
    calls.filter((call) => call.method === "CreateDispatch").length,
    2,
  );
});

void test("voice media configuration stays disabled without new accounts and rejects unsafe enablement", () => {
  const identity = {
    NODE_ENV: "test",
    CI: "true",
    IDENTITY_JWT_ISSUER: "https://identity.voice.test",
    IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
    IDENTITY_JWT_KEY_ID: "synthetic-key",
    IDENTITY_JWT_PUBLIC_KEY: "synthetic-public-key",
  };
  const disabled = loadConfiguration(identity);
  assert.equal(disabled.VOICE_MEDIA?.enabled, false);
  assert.equal(disabled.VOICE_MEDIA.workerToken, null);
  const approved = {
    ...identity,
    NODE_ENV: "development",
    CI: "false",
    JOURNAL_AI_VOICE_ENABLED: "true",
    JOURNAL_AI_VOICE_PROVIDER_MODE: "APPROVED_BEDROCK",
    JOURNAL_AI_VOICE_PROVIDER_APPROVAL_VERSION: "mb762-scoped-voice-v1",
    AWS_BEARER_TOKEN_BEDROCK: "synthetic-bedrock-credential",
    JOURNAL_AI_VOICE_APPROVAL_VERSION: "reviewed-demo-v1",
    JOURNAL_AI_VOICE_LIVEKIT_URL: configuration.serverUrl ?? "",
    JOURNAL_AI_VOICE_LIVEKIT_API_KEY: configuration.apiKey ?? "",
    JOURNAL_AI_VOICE_LIVEKIT_API_SECRET: configuration.apiSecret ?? "",
    JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN: configuration.workerToken ?? "",
    JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN:
      "synthetic-care-credential-at-least-32-characters",
    JOURNAL_AI_VOICE_ENTITLEMENT_SERVICE_TOKEN:
      "synthetic-entitlement-credential-at-least-32-characters",
  };
  assert.equal(loadConfiguration(approved).VOICE_MEDIA?.enabled, true);
  assert.throws(() => loadConfiguration({ ...approved, CI: "true" }));
  assert.throws(() =>
    loadConfiguration({ ...approved, JOURNAL_AI_VOICE_APPROVAL_VERSION: "" }),
  );
  assert.throws(() =>
    loadConfiguration({
      ...approved,
      JOURNAL_AI_VOICE_WORKER_SERVICE_TOKEN:
        approved.JOURNAL_AI_VOICE_CARE_SERVICE_TOKEN,
    }),
  );
  for (const url of [
    "ws://project.livekit.cloud",
    "wss://project.livekit.cloud.evil.test",
    "wss://user:password@project.livekit.cloud",
    "wss://project.livekit.cloud/?token=private",
    "wss://project.livekit.cloud/private",
    "wss://localhost",
  ]) {
    assert.throws(() =>
      loadConfiguration({ ...approved, JOURNAL_AI_VOICE_LIVEKIT_URL: url }),
    );
  }
});
