import { randomUUID } from 'node:crypto';
import { Inject } from '@nestjs/common';
import {
  ConnectedSocket,
  MessageBody,
  SubscribeMessage,
  WebSocketGateway,
  type OnGatewayConnection,
  type OnGatewayDisconnect,
  type OnGatewayInit,
} from '@nestjs/websockets';
import type { Server, Socket } from 'socket.io';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { METRICS_TOKEN } from '../shared/tokens.js';
import { CommunityCredentials, type CommunitySession } from './community-credentials.js';
import { CommunityFanout } from './community-fanout.js';
import { CommunityOwnerClient } from './community-owner.client.js';
import { CommunityPresence } from './community-presence.js';
import { CommunityPresenceProjection } from './community-presence-projection.js';
import {
  commandSchema,
  handshakeSchema,
  CommunityError,
  type CommunityFact,
  type ReadState,
} from './community-schema.js';

interface Subscription {
  cursor: string | null;
  generation: number;
  tail: Promise<void>;
  readState?: string;
  presence?: Map<string, string>;
  presenceRoutes?: Set<string>;
  presenceDue?: number;
  presencePending?: boolean;
}
interface Data {
  session?: CommunitySession;
  subscriptions?: Map<string, Subscription>;
  expiry?: NodeJS.Timeout;
  maintenance?: NodeJS.Timeout;
  presenceMaintenance?: NodeJS.Timeout;
  startedAt?: number;
  commands?: number;
  pending?: number;
  renewing?: boolean;
  buffered?: number;
  drain?: () => void;
  heartbeatGeneration?: number;
  lastForegroundHeartbeat?: number;
}
type CommunitySocket = Socket<
  Record<string, never>,
  { 'community.event': (event: unknown) => void },
  Record<string, never>,
  Data
>;
@WebSocketGateway({ namespace: '/community', transports: ['websocket'] })
export class CommunityGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect {
  private readonly sockets = new Set<CommunitySocket>();
  constructor(
    private readonly credentials: CommunityCredentials,
    private readonly owner: CommunityOwnerClient,
    private readonly fanout: CommunityFanout,
    private readonly presence: CommunityPresence,
    private readonly presenceProjection: CommunityPresenceProjection,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {}
  afterInit(server: Server): void {
    server.use((socket, next) => {
      void this.authenticate(socket as unknown as CommunitySocket)
        .then(() => {
          next();
        })
        .catch(() => {
          next(new Error('UNAUTHENTICATED'));
        });
    });
    this.fanout.onFact((fact) => {
      this.receive(fact);
    });
    this.fanout.onFailure(() => {
      for (const socket of this.sockets) socket.disconnect(true);
    });
    this.fanout.onPresence((subject) => {
      for (const socket of this.sockets)
        for (const [conversation, subscription] of this.subscriptions(socket)) {
          if (subscription.presenceRoutes?.has(subject))
            this.schedulePresence(socket, conversation, subscription);
        }
    });
  }
  handleConnection(socket: CommunitySocket): void {
    if (!this.current(socket) || !this.fanout.available()) {
      socket.disconnect(true);
      return;
    }
    this.sockets.add(socket);
    socket.data.drain = () => {
      socket.data.buffered = 0;
    };
    socket.conn.on('drain', socket.data.drain);
    this.metrics.activeSockets.inc();
    const expires = this.session(socket).identityExpiresAt * 1000 - Date.now();
    socket.data.expiry = setTimeout(
      () => socket.disconnect(true),
      Math.min(expires, 2_147_483_647),
    );
    socket.data.expiry.unref();
    socket.data.maintenance = setInterval(() => {
      void this.maintain(socket);
    }, 20_000);
    socket.data.maintenance.unref();
    socket.data.presenceMaintenance = setInterval(() => {
      for (const [conversation, subscription] of this.subscriptions(socket))
        if (Date.now() >= (subscription.presenceDue ?? 0))
          this.schedulePresence(socket, conversation, subscription);
    }, 500);
    socket.data.presenceMaintenance.unref();
  }
  async handleDisconnect(socket: CommunitySocket): Promise<void> {
    if (socket.data.drain) socket.conn.off('drain', socket.data.drain);
    if (socket.data.expiry) clearTimeout(socket.data.expiry);
    if (socket.data.maintenance) clearInterval(socket.data.maintenance);
    if (socket.data.presenceMaintenance) clearInterval(socket.data.presenceMaintenance);
    if (this.sockets.delete(socket)) this.metrics.activeSockets.dec();
    socket.data.subscriptions?.clear();
    if (socket.data.session) {
      try {
        if (await this.presence.remove(socket.data.session.subject, socket.id))
          await this.fanout.publishPresence(socket.data.session.subject);
      } catch {
        this.metrics.commands.inc({ command: 'community.presence', result: 'unavailable' });
      }
      await this.credentials.remove(socket.data.session, socket.id).catch(() => undefined);
    }
  }
  @SubscribeMessage('community.command')
  async command(
    @ConnectedSocket() socket: CommunitySocket,
    @MessageBody() input: unknown,
  ): Promise<unknown> {
    const parsed = commandSchema.safeParse(input);
    const correlationId = parsed.success ? parsed.data.correlationId : randomUUID();
    const commandId = parsed.success ? parsed.data.commandId : randomUUID();
    if (!this.current(socket)) {
      socket.disconnect(true);
      return this.error(commandId, correlationId, 'UNAUTHENTICATED');
    }
    if (!parsed.success || Buffer.byteLength(JSON.stringify(input)) > 32768)
      return this.error(commandId, correlationId, 'INVALID_COMMAND');
    if (parsed.data.commandType === 'community.presence.heartbeat') {
      try {
        const session = this.session(socket);
        const foreground = parsed.data.payload.foreground;
        if (foreground && Date.now() - (socket.data.lastForegroundHeartbeat ?? 0) < 10_000)
          throw new CommunityError('RATE_LIMITED');
        const generation = (socket.data.heartbeatGeneration ?? 0) + 1;
        socket.data.heartbeatGeneration = generation;
        if (foreground) {
          socket.data.lastForegroundHeartbeat = Date.now();
          await this.owner.session(session.subject, correlationId);
        }
        if (!this.current(socket)) throw new CommunityError('UNAUTHENTICATED');
        if (
          generation === socket.data.heartbeatGeneration &&
          (await this.presence.heartbeat(
            session.subject,
            socket.id,
            foreground,
            session.identityExpiresAt,
          ))
        )
          await this.fanout.publishPresence(session.subject);
        this.metrics.commands.inc({ command: 'community.presence.heartbeat', result: 'accepted' });
        return this.event(
          'community.command.ack',
          { commandId, status: 'ACCEPTED' },
          correlationId,
        );
      } catch (error) {
        return this.error(
          commandId,
          correlationId,
          error instanceof CommunityError ? error.code : 'DEPENDENCY_UNAVAILABLE',
          'community.presence.heartbeat',
        );
      }
    }
    const now = Date.now();
    if (now - (socket.data.startedAt ?? 0) >= 60_000) {
      socket.data.startedAt = now;
      socket.data.commands = 0;
    }
    socket.data.commands = (socket.data.commands ?? 0) + 1;
    const subscriptions = this.subscriptions(socket);
    const conversation = parsed.data.payload.conversationId;
    if (socket.data.commands > 30 || (!subscriptions.has(conversation) && subscriptions.size >= 30))
      return this.error(commandId, correlationId, 'RATE_LIMITED');
    try {
      await this.owner.subscribe(this.session(socket).subject, conversation, correlationId);
      if (!this.current(socket)) throw new CommunityError('UNAUTHENTICATED');
      let subscription = subscriptions.get(conversation);
      if (!subscription) {
        subscription = { cursor: null, generation: 0, tail: Promise.resolve() };
        subscriptions.set(conversation, subscription);
        const active = subscription;
        await this.enqueue(socket, conversation, active, async () => {
          const generation = active.generation;
          const snapshot = await this.owner.replay(
            this.session(socket).subject,
            conversation,
            null,
            correlationId,
          );
          if (!this.valid(socket, conversation, active, generation)) return;
          active.cursor = snapshot.cursor;
          this.emit(
            socket,
            'community.resync.required',
            { conversationId: conversation },
            correlationId,
          );
          this.readState(socket, conversation, active, snapshot.readState, correlationId);
          await this.refreshPresence(socket, conversation, active, correlationId, generation);
        });
      } else {
        const active = subscription;
        await this.enqueue(socket, conversation, active, () =>
          this.refreshPresence(
            socket,
            conversation,
            active,
            correlationId,
            active.generation,
            true,
          ),
        );
      }
      if (!this.current(socket)) throw new CommunityError('UNAUTHENTICATED');
      return this.event('community.command.ack', { commandId, status: 'ACCEPTED' }, correlationId);
    } catch (error) {
      return this.error(
        commandId,
        correlationId,
        error instanceof CommunityError ? error.code : 'DEPENDENCY_UNAVAILABLE',
      );
    }
  }
  private async authenticate(socket: CommunitySocket): Promise<void> {
    if (!this.fanout.available()) throw new CommunityError('DEPENDENCY_UNAVAILABLE');
    const handshake = handshakeSchema.parse(socket.handshake.auth);
    const session = await this.credentials.consume(handshake.credential);
    if (!(await this.credentials.register(session, socket.id)))
      throw new CommunityError('RATE_LIMITED');
    socket.data.session = session;
    socket.data.subscriptions = new Map();
  }
  private receive(fact: CommunityFact): void {
    const { conversationId, kind } = fact.payload;
    for (const socket of this.sockets) {
      if (
        kind === 'CONVERSATION_CLEARED' &&
        this.current(socket) &&
        fact.payload.recipientRoutingReference === this.session(socket).subject
      ) {
        const current = socket.data.subscriptions?.get(conversationId);
        if (current) {
          current.generation++;
          current.presence?.clear();
          current.presenceRoutes?.clear();
          socket.data.subscriptions?.delete(conversationId);
        }
        this.emit(
          socket,
          'community.conversation.invalidated',
          { conversationId },
          fact.correlationId,
        );
        continue;
      }
      const subscription = socket.data.subscriptions?.get(conversationId);
      if (!subscription || !this.current(socket)) continue;
      if (kind === 'AUTHORIZATION_CHANGED') {
        subscription.generation++;
        if (subscription.presence?.size)
          this.emit(
            socket,
            'community.conversation.invalidated',
            { conversationId },
            fact.correlationId,
          );
        subscription.presence?.clear();
        subscription.presenceRoutes?.clear();
      }
      if (
        kind === 'READ_STATE_CHANGED' &&
        fact.payload.recipientRoutingReference !== this.session(socket).subject
      )
        continue;
      if (
        ![
          'MESSAGE_SAVED',
          'MESSAGE_RETRACTED',
          'AUTHORIZATION_CHANGED',
          'READ_STATE_CHANGED',
          'CONVERSATION_CLEARED',
        ].includes(kind)
      )
        continue;
      void this.enqueue(socket, conversationId, subscription, () =>
        this.deliver(socket, subscription, fact),
      ).catch(() => undefined);
    }
  }
  private async deliver(
    socket: CommunitySocket,
    subscription: Subscription,
    fact: CommunityFact,
  ): Promise<void> {
    const conversation = fact.payload.conversationId;
    const correlation = fact.correlationId;
    const generation = subscription.generation;
    if (!this.valid(socket, conversation, subscription, generation)) return;
    const subject = this.session(socket).subject;
    if (fact.payload.kind === 'AUTHORIZATION_CHANGED') {
      await this.reset(socket, conversation, subscription, correlation, generation);
      return;
    }
    if (fact.payload.kind === 'MESSAGE_SAVED') {
      try {
        await this.owner.message(subject, conversation, fact.payload.targetId, correlation);
      } catch (error) {
        if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE') return;
        throw error;
      }
    }
    if (fact.payload.kind === 'MESSAGE_RETRACTED') {
      try {
        const message = await this.owner.message(
          subject,
          conversation,
          fact.payload.targetId,
          correlation,
        );
        if (!this.valid(socket, conversation, subscription, generation)) return;
        this.emit(socket, 'community.message.updated', { message }, correlation);
      } catch (error) {
        if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE') return;
        throw error;
      }
      return;
    }
    if (subscription.cursor === null) return;
    try {
      for (let page = 0; page < 10; page++) {
        const projection = await this.owner.replay(
          subject,
          conversation,
          subscription.cursor,
          correlation,
        );
        for (const id of projection.messageIds) {
          const message = await this.owner.message(subject, conversation, id, correlation);
          if (!this.valid(socket, conversation, subscription, generation)) return;
          this.emit(socket, 'community.message.created', { message }, correlation);
        }
        if (!this.valid(socket, conversation, subscription, generation)) return;
        subscription.cursor = projection.cursor;
        if (!projection.hasMore) {
          this.readState(socket, conversation, subscription, projection.readState, correlation);
          return;
        }
      }
      socket.disconnect(true);
    } catch (error) {
      if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE') {
        await this.reset(socket, conversation, subscription, correlation, generation);
        return;
      }
      throw error;
    }
  }
  private async reset(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
    correlation: string,
    generation: number,
  ): Promise<void> {
    if (!this.valid(socket, conversation, subscription, generation)) return;
    try {
      if (subscription.presence?.size)
        this.emit(
          socket,
          'community.conversation.invalidated',
          { conversationId: conversation },
          correlation,
        );
      subscription.presence = new Map();
      subscription.presenceRoutes = new Set();
      await this.owner.subscribe(this.session(socket).subject, conversation, correlation);
      const snapshot = await this.owner.replay(
        this.session(socket).subject,
        conversation,
        null,
        correlation,
      );
      if (!this.valid(socket, conversation, subscription, generation)) return;
      subscription.cursor = snapshot.cursor;
      this.emit(socket, 'community.resync.required', { conversationId: conversation }, correlation);
      this.readState(socket, conversation, subscription, snapshot.readState, correlation);
      await this.refreshPresence(socket, conversation, subscription, correlation, generation);
    } catch (error) {
      if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE') {
        if (this.valid(socket, conversation, subscription, generation)) {
          this.subscriptions(socket).delete(conversation);
          this.emit(
            socket,
            'community.conversation.invalidated',
            { conversationId: conversation },
            correlation,
          );
        }
        return;
      }
      throw error;
    }
  }
  private enqueue(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
    task: () => Promise<void>,
  ): Promise<void> {
    socket.data.pending = (socket.data.pending ?? 0) + 1;
    if (socket.data.pending > 64) {
      socket.disconnect(true);
      return Promise.reject(new CommunityError('DEPENDENCY_UNAVAILABLE'));
    }
    const pending = subscription.tail.then(async () => {
      if (this.current(socket) && socket.data.subscriptions?.get(conversation) === subscription)
        await task();
    });
    subscription.tail = pending
      .catch(() => {
        socket.disconnect(true);
      })
      .finally(() => {
        socket.data.pending = Math.max(0, (socket.data.pending ?? 1) - 1);
      });
    return pending;
  }
  private schedulePresence(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
  ): void {
    if (subscription.presencePending || !this.current(socket)) return;
    subscription.presencePending = true;
    const generation = subscription.generation;
    void this.enqueue(socket, conversation, subscription, async () => {
      try {
        await this.refreshPresence(socket, conversation, subscription, randomUUID(), generation);
      } catch (error) {
        if (error instanceof CommunityError && error.code === 'TARGET_UNAVAILABLE') {
          await this.reset(socket, conversation, subscription, randomUUID(), generation);
          return;
        }
        throw error;
      } finally {
        subscription.presencePending = false;
      }
    }).catch(() => undefined);
  }
  private async refreshPresence(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
    correlation: string,
    generation: number,
    force = false,
  ): Promise<void> {
    if (!this.valid(socket, conversation, subscription, generation)) return;
    const snapshot = await this.presenceProjection.snapshot(
      this.session(socket).subject,
      conversation,
      correlation,
    );
    if (!this.valid(socket, conversation, subscription, generation)) return;
    const current = new Map(
      snapshot.projections.map((value) => [value.communityProfileId, value.state]),
    );
    if (subscription.presence && [...subscription.presence.keys()].some((id) => !current.has(id))) {
      this.emit(
        socket,
        'community.conversation.invalidated',
        { conversationId: conversation },
        correlation,
      );
      subscription.presence.clear();
    }
    for (const projection of snapshot.projections) {
      if (!force && subscription.presence?.get(projection.communityProfileId) === projection.state)
        continue;
      this.emit(
        socket,
        'community.presence.changed',
        {
          communityProfileId: projection.communityProfileId,
          state: projection.state,
        },
        correlation,
      );
    }
    subscription.presence = current;
    subscription.presenceRoutes = new Set(snapshot.projections.map((value) => value.routing));
    subscription.presenceDue = snapshot.refreshAt;
  }
  private readState(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
    state: ReadState,
    correlation: string,
  ): void {
    const serialized = JSON.stringify(state);
    if (serialized === subscription.readState) return;
    subscription.readState = serialized;
    this.emit(
      socket,
      'community.read-state.changed',
      { conversationId: conversation, ...state },
      correlation,
    );
  }
  private emit(socket: CommunitySocket, type: string, payload: unknown, correlation: string): void {
    if (!this.current(socket)) return;
    if ((socket.data.buffered ?? 0) >= 32) {
      socket.disconnect(true);
      return;
    }
    socket.data.buffered = (socket.data.buffered ?? 0) + 1;
    socket.emit('community.event', this.event(type, payload, correlation));
  }
  private event(eventType: string, payload: unknown, correlationId: string) {
    return {
      schemaVersion: 1,
      eventId: randomUUID(),
      correlationId,
      occurredAt: new Date().toISOString(),
      eventType,
      payload,
    };
  }
  private error(
    commandId: string,
    correlation: string,
    code: string,
    command = 'community.subscribe',
  ) {
    this.metrics.commands.inc({ command, result: code.toLowerCase() });
    return this.event(
      'community.command.error',
      { commandId, code, retryable: code === 'DEPENDENCY_UNAVAILABLE' || code === 'RATE_LIMITED' },
      correlation,
    );
  }
  private current(socket: CommunitySocket): boolean {
    return (
      socket.connected &&
      !!socket.data.session &&
      Date.now() < socket.data.session.identityExpiresAt * 1000
    );
  }
  private session(socket: CommunitySocket): CommunitySession {
    const session = socket.data.session;
    if (!session || Date.now() >= session.identityExpiresAt * 1000)
      throw new CommunityError('UNAUTHENTICATED');
    return session;
  }
  private subscriptions(socket: CommunitySocket): Map<string, Subscription> {
    socket.data.subscriptions ??= new Map();
    return socket.data.subscriptions;
  }
  private valid(
    socket: CommunitySocket,
    conversation: string,
    subscription: Subscription,
    generation: number,
  ): boolean {
    return (
      this.current(socket) &&
      socket.data.subscriptions?.get(conversation) === subscription &&
      subscription.generation === generation
    );
  }
  private async maintain(socket: CommunitySocket): Promise<void> {
    if (socket.data.renewing) return;
    socket.data.renewing = true;
    try {
      if (!this.current(socket) || !this.fanout.available()) {
        socket.disconnect(true);
        return;
      }
      await this.owner.session(this.session(socket).subject);
      if (!(await this.credentials.renew(this.session(socket), socket.id))) socket.disconnect(true);
    } catch {
      socket.disconnect(true);
    } finally {
      socket.data.renewing = false;
    }
  }
}
