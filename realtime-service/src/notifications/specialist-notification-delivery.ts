import { Inject, Injectable, type OnApplicationShutdown } from '@nestjs/common';
import { setTimeout as delay } from 'node:timers/promises';
import type { AuthenticatedPrincipal } from '../security/principal.js';
import type { RealtimeMetrics } from '../observability/metrics.js';
import { METRICS_TOKEN } from '../shared/tokens.js';
import { ContentNotificationClient } from './content-notification.client.js';
import { SpecialistNotificationFanout } from './specialist-notification-fanout.js';
import {
  toNotificationServerEvent,
  type NotificationPointer,
  type NotificationServerEvent,
} from './notification-schema.js';

export interface NotificationSocket {
  readonly id: string;
  readonly connected: boolean;
  readonly data: { principal?: AuthenticatedPrincipal; bearerToken?: string };
  emit(name: 'notification.created', event: NotificationServerEvent): unknown;
}

interface SocketDelivery {
  readonly socket: NotificationSocket;
  readonly seen: Map<string, number>;
  readonly pending: Map<string, NotificationPointer>;
  processing: boolean;
}

@Injectable()
export class SpecialistNotificationDelivery implements OnApplicationShutdown {
  private readonly sockets = new Map<string, SocketDelivery>();

  constructor(
    fanout: SpecialistNotificationFanout,
    private readonly owner: ContentNotificationClient,
    @Inject(METRICS_TOKEN) private readonly metrics: RealtimeMetrics,
  ) {
    fanout.onPointer((pointer) => {
      this.receive(pointer);
    });
  }

  add(socket: NotificationSocket): void {
    this.sockets.set(socket.id, { socket, seen: new Map(), pending: new Map(), processing: false });
  }

  remove(socketId: string): void {
    this.sockets.delete(socketId);
  }

  onApplicationShutdown(): void {
    this.sockets.clear();
  }

  receive(pointer: NotificationPointer): void {
    for (const state of this.sockets.values()) {
      if (!this.current(state, pointer)) continue;
      for (const [id, expiresAt] of state.seen) if (expiresAt <= Date.now()) state.seen.delete(id);
      if (state.seen.has(pointer.eventId) || state.pending.has(pointer.eventId)) {
        this.metrics.commands.inc({ command: 'notification.socket', result: 'duplicate' });
        continue;
      }
      if (state.pending.size >= 32) {
        this.metrics.commands.inc({ command: 'notification.socket', result: 'backlog_dropped' });
        continue;
      }
      state.pending.set(pointer.eventId, pointer);
      if (!state.processing) void this.drain(state);
    }
  }

  private async drain(state: SocketDelivery): Promise<void> {
    state.processing = true;
    try {
      for (const [id, pointer] of state.pending) {
        await this.deliver(state, pointer);
        state.pending.delete(id);
        if (this.sockets.get(state.socket.id) !== state) break;
      }
    } finally {
      state.processing = false;
    }
  }

  private async deliver(state: SocketDelivery, pointer: NotificationPointer): Promise<void> {
    const principal = state.socket.data.principal;
    const bearer = state.socket.data.bearerToken;
    if (!principal || !bearer) return;
    for (let attempt = 0; attempt < 3; attempt++) {
      if (
        !this.current(state, pointer) ||
        state.socket.data.principal !== principal ||
        state.socket.data.bearerToken !== bearer
      )
        return;
      const remainingMs =
        Math.min(principal.expiresAtEpochSeconds * 1000, Date.parse(pointer.expiresAt)) -
        Date.now();
      const decision = await this.owner.check(
        bearer,
        pointer.notificationId,
        pointer.correlationId,
        remainingMs,
      );
      if (
        !this.current(state, pointer) ||
        state.socket.data.principal !== principal ||
        state.socket.data.bearerToken !== bearer
      )
        return;
      if (decision === 'ineligible') return;
      if (decision === 'eligible') {
        try {
          state.socket.emit('notification.created', toNotificationServerEvent(pointer));
        } catch {
          this.metrics.commands.inc({ command: 'notification.socket', result: 'emit_failed' });
          return;
        }
        state.seen.set(
          pointer.eventId,
          Math.min(Date.now() + 300_000, Date.parse(pointer.expiresAt)),
        );
        while (state.seen.size > 256) {
          const oldest = state.seen.keys().next().value;
          if (oldest === undefined) break;
          state.seen.delete(oldest);
        }
        this.metrics.commands.inc({ command: 'notification.socket', result: 'emitted' });
        this.metrics.notificationDeliverySeconds.observe(
          Math.max(0, (Date.now() - Date.parse(pointer.occurredAt)) / 1000),
        );
        return;
      }
      if (attempt < 2) await delay(attempt === 0 ? 100 : 250);
    }
    this.metrics.commands.inc({
      command: 'notification.socket',
      result: 'owner_retries_exhausted',
    });
  }

  private current(state: SocketDelivery, pointer: NotificationPointer): boolean {
    const { socket } = state;
    const principal = socket.data.principal;
    return (
      this.sockets.get(socket.id) === state &&
      socket.connected &&
      !!socket.data.bearerToken &&
      principal?.role === 'SPECIALIST' &&
      principal.accountId === pointer.recipientAccountId &&
      Date.now() < principal.expiresAtEpochSeconds * 1000 &&
      Date.now() < Date.parse(pointer.expiresAt)
    );
  }
}
