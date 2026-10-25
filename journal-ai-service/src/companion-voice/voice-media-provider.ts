import { decodeJwt } from "jose";
import {
  AccessToken,
  AgentDispatchClient,
  JobRestartPolicy,
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
  agentAudioPublished: boolean;
}

export interface VoiceMediaProvider {
  createRoom(reference: VoiceMediaReference): Promise<void>;
  dispatch(reference: VoiceMediaReference): Promise<string>;
  inspect(reference: VoiceMediaReference): Promise<VoiceMediaInspection>;
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
        room.metadata !== reference.dispatchId ||
        room.activeRecording
      )
        throw new VoiceMediaError();
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

  inspect(reference: VoiceMediaReference): Promise<VoiceMediaInspection> {
    return this.safe(reference, async () => {
      const rooms = await this.rooms.listRooms([reference.roomName]);
      const room = rooms.find((value) => value.name === reference.roomName);
      if (!room)
        return {
          roomExists: false,
          providerDispatchId: null,
          ownerConnected: false,
          agentAudioPublished: false,
        };
      if (room.metadata !== reference.dispatchId || room.activeRecording)
        throw new VoiceMediaError();
      const [dispatches, participants] = await Promise.all([
        this.agents.listDispatch(reference.roomName),
        this.rooms.listParticipants(reference.roomName),
      ]);
      const matching = dispatches.filter(
        (value) =>
          value.agentName === this.configuration.agentName &&
          value.metadata === dispatchMetadata(reference),
      );
      if (matching.length > 1) throw new VoiceMediaError();
      return {
        roomExists: true,
        providerDispatchId: matching[0]?.id ?? null,
        ownerConnected: participants.some(
          (value) => value.identity === reference.participantIdentity,
        ),
        agentAudioPublished: participants.some(
          (value) =>
            value.identity === reference.agentIdentity &&
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
