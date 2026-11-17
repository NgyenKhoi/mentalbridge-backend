import { decodeJwt } from "jose";
import { performance } from "node:perf_hooks";
import { randomUUID } from "node:crypto";
import type {
  VoiceAuthorizationProfile,
  VoiceAuthorizationCall,
} from "./voice-authorization-profile.js";
import {
  profileVoiceHttpRead,
  type VoiceHttpDiagnostic,
} from "./voice-http-profile.js";
import {
  AccessToken,
  AgentDispatchClient,
  JobRestartPolicy,
  ParticipantInfo_State,
  RoomServiceClient,
  ServerError,
  TrackSource,
  TrackType,
} from "livekit-server-sdk";

export interface VoiceMediaConfiguration {
  enabled: boolean;
  approvedVersion: string | null;
  serverUrl: string | null;
  apiKey: string | null;
  apiSecret: string | null;
  agentName: string;
  workerToken: string | null;
}

export interface VoiceMediaReference {
  voiceSessionId: string;
  attemptId: string;
  dispatchId: string;
  roomName: string;
  participantIdentity: string;
  agentIdentity: string;
  hardStopAt: Date;
  providerDispatchId: string | null;
}

export interface VoiceConnection {
  voiceSessionId: string;
  roomName: string;
  agentIdentity: string;
  transport: "LIVEKIT_CLOUD";
  serverUrl: string;
  participantToken: string;
  tokenExpiresAt: string;
  hardStopAt: string;
  audioOnly: true;
}

export interface VoiceMediaInspection {
  roomExists: boolean;
  providerDispatchId: string | null;
  ownerConnected: boolean;
  agentConnected: boolean;
  agentAudioPublished: boolean;
}

export interface VoiceMediaDiagnostic extends Partial<VoiceHttpDiagnostic> {
  attemptId: string;
  inspectionId: string;
  purpose: VoiceAuthorizationCall["purpose"];
  authorizationId?: string;
  turnId?: string | undefined;
  turnSequence?: number | undefined;
  operation: "ROOMS" | "DISPATCHES" | "PARTICIPANTS";
  elapsedMs: number;
  outcome: "SUCCESS" | "FAILED";
}

export interface VoiceMediaProvider {
  createRoom(reference: VoiceMediaReference): Promise<void>;
  dispatch(reference: VoiceMediaReference): Promise<string>;
  inspect(
    reference: VoiceMediaReference,
    profile?: VoiceAuthorizationProfile,
  ): Promise<VoiceMediaInspection>;
  connection(
    reference: VoiceMediaReference,
    now: Date,
  ): Promise<VoiceConnection>;
  stop(reference: VoiceMediaReference): Promise<void>;
}

export class VoiceMediaError extends Error {
  constructor() {
    super("PROVIDER_UNAVAILABLE");
  }
}

const uuidPart =
  "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const assertReference = (reference: VoiceMediaReference) => {
  if (
    !new RegExp(`^ai-voice-${uuidPart}$`).test(reference.roomName) ||
    !new RegExp(`^vu-${uuidPart}$`).test(reference.participantIdentity) ||
    reference.agentIdentity !== `va-${reference.attemptId}` ||
    !new RegExp(`^${uuidPart}$`).test(reference.attemptId) ||
    !new RegExp(`^${uuidPart}$`).test(reference.voiceSessionId) ||
    !new RegExp(`^${uuidPart}$`).test(reference.dispatchId)
  )
    throw new VoiceMediaError();
};

const dispatchMetadata = (reference: VoiceMediaReference) =>
  JSON.stringify({
    voiceSessionId: reference.voiceSessionId,
    attemptId: reference.attemptId,
    dispatchId: reference.dispatchId,
  });

type RoomClient = Pick<
  RoomServiceClient,
  | "createRoom"
  | "listRooms"
  | "listParticipants"
  | "removeParticipant"
  | "deleteRoom"
>;
type DispatchClient = Pick<
  AgentDispatchClient,
  "createDispatch" | "listDispatch" | "deleteDispatch"
>;

export class LiveKitVoiceMedia implements VoiceMediaProvider {
  private readonly rooms: RoomClient;
  private readonly agents: DispatchClient;
  constructor(
    private readonly configuration: VoiceMediaConfiguration,
    clients?: { rooms: RoomClient; agents: DispatchClient },
    private readonly diagnostic?: (value: VoiceMediaDiagnostic) => void,
    private readonly monotonic: () => number = () => performance.now(),
  ) {
    if (
      !configuration.serverUrl ||
      !configuration.apiKey ||
      !configuration.apiSecret
    )
      throw new VoiceMediaError();
    const host = configuration.serverUrl.replace(/^wss:/, "https:");
    const options = { requestTimeout: 3, failover: false };
    this.rooms =
      clients?.rooms ??
      new RoomServiceClient(
        host,
        configuration.apiKey,
        configuration.apiSecret,
        options,
      );
    this.agents =
      clients?.agents ??
      new AgentDispatchClient(
        host,
        configuration.apiKey,
        configuration.apiSecret,
        options,
      );
  }

  private async safe<T>(
    reference: VoiceMediaReference,
    operation: () => Promise<T>,
  ): Promise<T> {
    assertReference(reference);
    try {
      return await operation();
    } catch {
      throw new VoiceMediaError();
    }
  }

  private inspectRead<T>(
    reference: VoiceMediaReference,
    operation: VoiceMediaDiagnostic["operation"],
    read: () => Promise<T>,
    identity: Pick<
      VoiceMediaDiagnostic,
      "inspectionId" | "purpose" | "authorizationId" | "turnId" | "turnSequence"
    >,
  ): Promise<T> {
    const started = this.monotonic();
    let http: VoiceHttpDiagnostic | undefined;
    const observe = (outcome: VoiceMediaDiagnostic["outcome"]) => {
      const elapsed = this.monotonic() - started;
      if (!Number.isFinite(elapsed)) return;
      try {
        this.diagnostic?.({
          ...identity,
          ...http,
          attemptId: reference.attemptId,
          operation,
          elapsedMs: Math.max(0, Math.floor(elapsed)),
          outcome,
        });
      } catch {
        return;
      }
    };
    return Promise.resolve()
      .then(() =>
        this.diagnostic
          ? profileVoiceHttpRead(read, (value) => {
              http = value;
            })
          : read(),
      )
      .then(
        (value) => {
          observe("SUCCESS");
          return value;
        },
        () => {
          observe("FAILED");
          throw new VoiceMediaError();
        },
      );
  }

  createRoom(reference: VoiceMediaReference): Promise<void> {
    return this.safe(reference, async () => {
      const room = await this.rooms.createRoom({
        name: reference.roomName,
        maxParticipants: 2,
        emptyTimeout: 45,
        departureTimeout: 30,
        metadata: reference.dispatchId,
        agents: [],
      });
      if (
        room.name !== reference.roomName ||
        room.metadata !== reference.dispatchId
      )
        throw new VoiceMediaError();
      if (room.activeRecording) {
        await this.stop(reference);
        throw new VoiceMediaError();
      }
    });
  }

  dispatch(reference: VoiceMediaReference): Promise<string> {
    return this.safe(reference, async () => {
      const value = await this.agents.createDispatch(
        reference.roomName,
        this.configuration.agentName,
        {
          metadata: dispatchMetadata(reference),
          restartPolicy: JobRestartPolicy.JRP_NEVER,
        },
      );
      if (
        value.room !== reference.roomName ||
        value.agentName !== this.configuration.agentName ||
        value.metadata !== dispatchMetadata(reference) ||
        !value.id
      )
        throw new VoiceMediaError();
      return value.id;
    });
  }

  inspect(
    reference: VoiceMediaReference,
    profile?: VoiceAuthorizationProfile,
  ): Promise<VoiceMediaInspection> {
    return this.safe(reference, async () => {
      const identity = {
        ...(profile?.tags ?? { purpose: "UNSCOPED" as const }),
        inspectionId: randomUUID(),
      };
      const roomRead = this.inspectRead(
        reference,
        "ROOMS",
        () => this.rooms.listRooms([reference.roomName]),
        identity,
      );
      const detailsRead = Promise.all([
        this.inspectRead(
          reference,
          "DISPATCHES",
          () => this.agents.listDispatch(reference.roomName),
          identity,
        ),
        this.inspectRead(
          reference,
          "PARTICIPANTS",
          () => this.rooms.listParticipants(reference.roomName),
          identity,
        ),
      ]).catch(() => null);
      const rooms = await roomRead;
      const room = rooms.find((value) => value.name === reference.roomName);
      if (!room)
        return {
          roomExists: false,
          providerDispatchId: null,
          ownerConnected: false,
          agentConnected: false,
          agentAudioPublished: false,
        };
      if (room.metadata !== reference.dispatchId) throw new VoiceMediaError();
      if (room.activeRecording) {
        await this.stop(reference);
        throw new VoiceMediaError();
      }
      const details = await detailsRead;
      if (!details) throw new VoiceMediaError();
      const [dispatches, participants] = details;
      if (
        participants.some(
          (participant) =>
            ![reference.participantIdentity, reference.agentIdentity].includes(
              participant.identity,
            ) ||
            participant.tracks.some((track) => track.type !== TrackType.AUDIO),
        )
      ) {
        await this.stop(reference);
        throw new VoiceMediaError();
      }
      const matching = dispatches.filter(
        (value) =>
          value.agentName === this.configuration.agentName &&
          value.room === reference.roomName &&
          value.metadata === dispatchMetadata(reference),
      );
      if (matching.length > 1) throw new VoiceMediaError();
      return {
        roomExists: true,
        providerDispatchId: matching[0]?.id ?? null,
        ownerConnected: participants.some(
          (value) =>
            value.identity === reference.participantIdentity &&
            value.state === ParticipantInfo_State.ACTIVE,
        ),
        agentConnected: participants.some(
          (value) =>
            value.identity === reference.agentIdentity &&
            value.state === ParticipantInfo_State.ACTIVE,
        ),
        agentAudioPublished: participants.some(
          (value) =>
            value.identity === reference.agentIdentity &&
            value.state === ParticipantInfo_State.ACTIVE &&
            value.tracks.some(
              (track) => track.type === TrackType.AUDIO && !track.muted,
            ),
        ),
      };
    });
  }

  connection(
    reference: VoiceMediaReference,
    now: Date,
  ): Promise<VoiceConnection> {
    return this.safe(reference, async () => {
      const { apiKey, apiSecret, serverUrl } = this.configuration;
      if (!apiKey || !apiSecret || !serverUrl) throw new VoiceMediaError();
      const expiresAt = Math.min(
        Math.floor(now.getTime() / 1000) + 60,
        Math.floor(reference.hardStopAt.getTime() / 1000),
      );
      const ttl = expiresAt - Math.floor(Date.now() / 1000);
      if (ttl < 1 || ttl > 60) throw new VoiceMediaError();
      const token = new AccessToken(apiKey, apiSecret, {
        identity: reference.participantIdentity,
        ttl,
      });
      token.addGrant({
        roomJoin: true,
        room: reference.roomName,
        canPublish: true,
        canSubscribe: true,
        canPublishSources: [TrackSource.MICROPHONE],
        canPublishData: false,
        canUpdateOwnMetadata: false,
        roomAdmin: false,
        roomCreate: false,
        roomList: false,
        roomRecord: false,
        ingressAdmin: false,
        agent: false,
      });
      const participantToken = await token.toJwt();
      const claims = decodeJwt(participantToken);
      if (
        !claims.exp ||
        claims.exp > expiresAt ||
        claims.exp <= Math.floor(Date.now() / 1000)
      )
        throw new VoiceMediaError();
      return {
        voiceSessionId: reference.voiceSessionId,
        roomName: reference.roomName,
        agentIdentity: reference.agentIdentity,
        transport: "LIVEKIT_CLOUD",
        serverUrl,
        participantToken,
        tokenExpiresAt: new Date(claims.exp * 1000).toISOString(),
        hardStopAt: reference.hardStopAt.toISOString(),
        audioOnly: true,
      };
    });
  }

  stop(reference: VoiceMediaReference): Promise<void> {
    return this.safe(reference, async () => {
      const ignoreMissing = async (operation: () => Promise<void>) => {
        try {
          await operation();
        } catch (error) {
          if (!(error instanceof ServerError) || error.status !== 404)
            throw error;
        }
      };
      const revokeTokenTs = BigInt(Math.floor(Date.now() / 1000) + 1);
      await Promise.all(
        [reference.participantIdentity, reference.agentIdentity].map(
          (identity) =>
            ignoreMissing(() =>
              this.rooms.removeParticipant(reference.roomName, identity, {
                revokeTokenTs,
              }),
            ),
        ),
      );
      if (reference.providerDispatchId)
        await ignoreMissing(() =>
          this.agents.deleteDispatch(
            reference.providerDispatchId ?? "",
            reference.roomName,
          ),
        );
      await ignoreMissing(() => this.rooms.deleteRoom(reference.roomName));
      const remaining = await this.rooms.listRooms([reference.roomName]);
      if (remaining.some((room) => room.name === reference.roomName))
        throw new VoiceMediaError();
    });
  }
}
