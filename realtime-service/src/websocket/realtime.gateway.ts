import { randomUUID } from 'node:crypto';

import { Inject } from '@nestjs/common';
import {
  ConnectedSocket,
  MessageBody,
  SubscribeMessage,
  WebSocketGateway,
  WebSocketServer,
} from '@nestjs/websockets';
import type { OnGatewayConnection, OnGatewayDisconnect, OnGatewayInit } from '@nestjs/websockets';
import type { Server, Socket } from 'socket.io';
import { z } from 'zod';

import type { ServiceConfiguration } from '../configuration/configuration.js';
import { ApplicationException } from '../http/application.exception.js';
import { MessageService } from '../messages/message.service.js';
import { normalizeCorrelationId } from '../observability/correlation-id.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { PresenceService } from '../presence/presence.service.js';
import { IdentityJwtVerifier } from '../security/identity-jwt-verifier.js';
import type { AuthenticatedPrincipal } from '../security/principal.js';
import { CONFIGURATION_TOKEN, METRICS_TOKEN } from '../shared/tokens.js';
import { commandSchema, type RealtimeCommand } from './command.schema.js';

interface SocketData {
  principal?: AuthenticatedPrincipal;
  correlationId?: string;
  countedActive?: boolean;
  expiryTimer?: NodeJS.Timeout;
}

interface ServerToClientEvents {
  'realtime.error': (payload: SocketAcknowledgement) => void;
  'realtime.event': (payload: unknown) => void;
}

type RealtimeSocket = Socket<
  Record<string, never>,
  ServerToClientEvents,
  Record<string, never>,
  SocketData
>;

const handshakeSchema = z
  .object({
    schemaVersion: z.literal(1),
    accessToken: z.string().min(1).max(8192),
    correlationId: z
      .string()
      .regex(/^[A-Za-z0-9._:-]{1,128}$/)
      .optional(),
  })
  .strict();

interface SocketAcknowledgement {
  readonly schemaVersion: 1;
  readonly commandId?: string;
  readonly correlationId: string;
  readonly status?: 'accepted' | 'duplicate';
  readonly acknowledgedAt?: string;
  readonly messageId?: string;
  readonly liveDelivery?: 'delivered' | 'degraded' | 'not_applicable';
  readonly code?: string;
  readonly message?: string;
  readonly retryable?: boolean;
}

@WebSocketGateway({ namespace: '/realtime', transports: ['websocket', 'polling'] })
export class RealtimeGateway implements OnGatewayInit, OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  private server!: Server;

  private readonly rateWindows = new Map<string, { startedAt: number; count: number }>();

  constructor(
    private readonly verifier: IdentityJwtVerifier,
    private readonly presence: PresenceService,
    private readonly messages: MessageService,
    @Inject(CONFIGURATION_TOKEN) private readonly configuration: ServiceConfiguration,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {}

  afterInit(server: Server): void {
    server.use((socket, next) => {
      const realtimeSocket = socket as unknown as RealtimeSocket;
      void this.authenticate(realtimeSocket)
        .then(() => {
          next();
        })
        .catch(() => {
          this.metrics.socketConnections.inc({ result: 'authentication_failed' });
          const error = new Error('Authentication failed') as Error & { data?: unknown };
          error.data = this.error(
            'AUTHENTICATION_REQUIRED',
            normalizeCorrelationId(undefined),
            true,
          );
          next(error);
        });
    });
  }

  async handleConnection(socket: RealtimeSocket): Promise<void> {
    const principal = socket.data.principal;
    if (!principal || this.isExpired(principal)) {
      socket.disconnect(true);
      return;
    }
    const result = await this.presence.register(principal.accountId, socket.id);
    if (this.isExpired(principal)) {
      await this.presence.remove(principal.accountId, socket.id);
      this.expire(socket);
      return;
    }
    if (result === 'limit_exceeded') {
      socket.emit(
        'realtime.error',
        this.error('RATE_LIMITED', socket.data.correlationId ?? randomUUID(), true),
      );
      socket.disconnect(true);
      return;
    }
    this.scheduleExpiry(socket, principal);
    this.metrics.activeSockets.inc();
    socket.data.countedActive = true;
    this.metrics.socketConnections.inc({
      result: result === 'connected' ? 'connected' : 'degraded',
    });
    socket.emit('realtime.event', {
      schemaVersion: 1,
      eventId: randomUUID(),
      eventType: 'connection.ready',
      correlationId: socket.data.correlationId,
      occurredAt: new Date().toISOString(),
      payload: {
        accountId: principal.accountId,
        role: principal.role,
        presence: result,
      },
    });
  }

  async handleDisconnect(socket: RealtimeSocket): Promise<void> {
    this.rateWindows.delete(socket.id);
    if (socket.data.expiryTimer) clearTimeout(socket.data.expiryTimer);
    const principal = socket.data.principal;
    if (!principal) return;
    if (socket.data.countedActive) this.metrics.activeSockets.dec();
    await this.presence.remove(principal.accountId, socket.id);
  }

  @SubscribeMessage('realtime.command')
  async command(
    @ConnectedSocket() socket: RealtimeSocket,
    @MessageBody() input: unknown,
  ): Promise<SocketAcknowledgement> {
    const correlationId = normalizeCorrelationId(this.correlationIdFrom(input));
    const principal = socket.data.principal;
    if (!principal || this.isExpired(principal)) {
      const error = this.error('AUTHENTICATION_EXPIRED', correlationId, false);
      socket.emit('realtime.error', error);
      socket.disconnect(true);
      return error;
    }
    if (this.payloadBytes(input) > this.configuration.MAX_PAYLOAD_BYTES) {
      return this.reject('PAYLOAD_TOO_LARGE', correlationId, false);
    }
    if (!this.takeRateToken(socket.id)) {
      return this.reject('RATE_LIMITED', correlationId, true);
    }
    if (this.schemaVersionFrom(input) !== 1) {
      return this.reject('UNSUPPORTED_SCHEMA_VERSION', correlationId, false);
    }
    const parsed = commandSchema.safeParse(input);
    if (!parsed.success) {
      return this.reject('INVALID_ENVELOPE', correlationId, false);
    }
    try {
      const acknowledgement = await this.execute(socket, principal, parsed.data);
      this.metrics.commands.inc({
        command: parsed.data.commandType,
        result: acknowledgement.status ?? 'error',
      });
      return acknowledgement;
    } catch (error) {
      const code = error instanceof ApplicationException ? error.code : 'INTERNAL_ERROR';
      const retryable = error instanceof ApplicationException ? error.retryable : false;
      this.metrics.commands.inc({ command: parsed.data.commandType, result: code.toLowerCase() });
      return this.error(code, correlationId, retryable, parsed.data.commandId);
    }
  }

  private async authenticate(socket: RealtimeSocket): Promise<void> {
    const handshake = handshakeSchema.parse(socket.handshake.auth);
    socket.data.principal = await this.verifier.verify(handshake.accessToken);
    socket.data.correlationId = normalizeCorrelationId(handshake.correlationId);
  }

  private async execute(
    socket: RealtimeSocket,
    principal: AuthenticatedPrincipal,
    command: RealtimeCommand,
  ): Promise<SocketAcknowledgement> {
    if (command.commandType === 'presence.heartbeat') {
      const result = await this.presence.heartbeat(principal.accountId, socket.id);
      return this.accept(
        command,
        false,
        undefined,
        result === 'connected' ? 'not_applicable' : 'degraded',
      );
    }
    if (command.commandType === 'conversation.subscribe') {
      await this.messages.subscribe(principal.accountId, command.payload.conversationId);
      await socket.join(this.room(command.payload.conversationId));
      return this.accept(command, false, undefined, 'not_applicable');
    }
    const result = await this.messages.send({
      senderId: principal.accountId,
      ...command.payload,
    });
    if (!result.duplicate) {
      const event = {
        schemaVersion: 1,
        eventId: randomUUID(),
        eventType: 'message.created',
        correlationId: command.correlationId,
        occurredAt: new Date().toISOString(),
        payload: result.message,
      };
      this.server.to(this.room(command.payload.conversationId)).emit('realtime.event', event);
    }
    return this.accept(command, result.duplicate, result.message.messageId, 'not_applicable');
  }

  private accept(
    command: RealtimeCommand,
    duplicate: boolean,
    messageId: string | undefined,
    liveDelivery: 'delivered' | 'degraded' | 'not_applicable',
  ): SocketAcknowledgement {
    return {
      schemaVersion: 1,
      commandId: command.commandId,
      correlationId: command.correlationId,
      status: duplicate ? 'duplicate' : 'accepted',
      acknowledgedAt: new Date().toISOString(),
      ...(messageId ? { messageId } : {}),
      liveDelivery,
    };
  }

  private reject(code: string, correlationId: string, retryable: boolean): SocketAcknowledgement {
    this.metrics.commands.inc({ command: 'unknown', result: code.toLowerCase() });
    return this.error(code, correlationId, retryable);
  }

  private error(
    code: string,
    correlationId: string,
    retryable: boolean,
    commandId?: string,
  ): SocketAcknowledgement {
    return {
      schemaVersion: 1,
      ...(commandId ? { commandId } : {}),
      correlationId,
      code,
      message: this.safeMessage(code),
      retryable,
    };
  }

  private safeMessage(code: string): string {
    const messages: Readonly<Record<string, string>> = {
      AUTHENTICATION_REQUIRED: 'Authentication is required',
      AUTHENTICATION_EXPIRED: 'Authentication has expired',
      INVALID_ENVELOPE: 'Command envelope is invalid',
      UNSUPPORTED_SCHEMA_VERSION: 'Schema version is not supported',
      PAYLOAD_TOO_LARGE: 'Command payload is too large',
      RATE_LIMITED: 'Command rate limit was exceeded',
      ACCESS_DENIED: 'Conversation access is denied',
      CHAT_ELIGIBILITY_UNAVAILABLE: 'Conversation eligibility is unavailable',
      IDEMPOTENCY_CONFLICT: 'Client message ID conflicts with an earlier command',
      DEPENDENCY_UNAVAILABLE: 'A required dependency is unavailable',
    };
    return messages[code] ?? 'The command could not be completed';
  }

  private takeRateToken(socketId: string): boolean {
    const now = Date.now();
    const duration = this.configuration.COMMAND_RATE_WINDOW_SECONDS * 1000;
    const current = this.rateWindows.get(socketId);
    if (!current || now - current.startedAt >= duration) {
      this.rateWindows.set(socketId, { startedAt: now, count: 1 });
      return true;
    }
    current.count += 1;
    return current.count <= this.configuration.COMMAND_RATE_LIMIT;
  }

  private payloadBytes(input: unknown): number {
    try {
      return Buffer.byteLength(JSON.stringify(input), 'utf8');
    } catch {
      return Number.POSITIVE_INFINITY;
    }
  }

  private correlationIdFrom(input: unknown): unknown {
    return typeof input === 'object' && input !== null
      ? Reflect.get(input, 'correlationId')
      : undefined;
  }

  private schemaVersionFrom(input: unknown): unknown {
    return typeof input === 'object' && input !== null
      ? Reflect.get(input, 'schemaVersion')
      : undefined;
  }

  private room(conversationId: string): string {
    return `conversation:${conversationId}`;
  }

  private isExpired(principal: AuthenticatedPrincipal): boolean {
    return Date.now() >= principal.expiresAtEpochSeconds * 1000;
  }

  private scheduleExpiry(socket: RealtimeSocket, principal: AuthenticatedPrincipal): void {
    if (socket.data.expiryTimer) clearTimeout(socket.data.expiryTimer);
    const remainingMilliseconds = principal.expiresAtEpochSeconds * 1000 - Date.now();
    if (remainingMilliseconds <= 0) {
      this.expire(socket);
      return;
    }
    const maximumDelay = 2_147_483_647;
    socket.data.expiryTimer = setTimeout(
      () => {
        if (this.isExpired(principal)) this.expire(socket);
        else this.scheduleExpiry(socket, principal);
      },
      Math.min(remainingMilliseconds, maximumDelay),
    );
    socket.data.expiryTimer.unref();
  }

  private expire(socket: RealtimeSocket): void {
    socket.emit(
      'realtime.error',
      this.error(
        'AUTHENTICATION_EXPIRED',
        socket.data.correlationId ?? normalizeCorrelationId(undefined),
        false,
      ),
    );
    socket.disconnect(true);
  }
}
