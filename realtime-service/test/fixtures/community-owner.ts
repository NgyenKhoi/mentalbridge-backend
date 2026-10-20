import { randomUUID } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { jwtVerify, type CryptoKey } from 'jose';
import { z } from 'zod';
import type { CommunityFact, CommunityMessage } from '../../src/community/community-schema.js';
import { factSchema } from '../../src/community/community-schema.js';

export const communityConversation = '88888888-8888-4888-8888-888888888888';
export const communityAlice = '11111111-1111-4111-8111-111111111111';
export const communityBob = '22222222-2222-4222-8222-222222222222';
export const communityPeer = '33333333-3333-4333-8333-333333333333';
const bodySchema = z
  .object({
    conversationId: z.uuid().optional(),
    messageId: z.uuid().optional(),
    action: z.string().optional(),
    cursor: z.string().nullable().optional(),
  })
  .strict();
interface Position {
  subject: string;
  conversation: string;
  after: number;
  epoch: number;
}
export class CommunityOwnerFixture {
  readonly messages: CommunityMessage[] = [];
  readonly denied = new Set<string>();
  readonly hidden = new Set<string>();
  readonly epochs = new Map<string, number>();
  readonly reads = new Map<string, number>();
  readonly calls: { subject: string; scope: string; conversation?: string; message?: string }[] =
    [];
  unavailable = false;
  malformed = false;
  projectionGate?: Promise<void>;
  requestGate?: Promise<void>;
  private readonly cursors = new Map<string, Position>();
  private server?: Server;
  url = '';
  constructor(private readonly key: CryptoKey) {}
  async start(): Promise<void> {
    this.server = createServer((request, response) => {
      void (async () => {
        try {
          const token = /^Bearer (\S+)$/.exec(request.headers.authorization ?? '')?.[1];
          if (!token) throw new Error('Missing auth');
          const { payload, protectedHeader } = await jwtVerify(token, this.key, {
            algorithms: ['RS256'],
            issuer: 'realtime-service',
            audience: 'community-service',
            clockTolerance: 0,
          });
          if (
            protectedHeader.kid !== 'realtime-test' ||
            !payload.sub ||
            !payload.iat ||
            !payload.exp ||
            payload.exp - payload.iat > 30
          )
            throw new Error('Bad delegation');
          z.object({ sub: z.literal('realtime-service') })
            .strict()
            .parse(payload.act);
          const chunks: Buffer[] = [];
          for await (const chunk of request)
            chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(String(chunk)));
          const text = Buffer.concat(chunks).toString();
          const body = bodySchema.parse(text ? JSON.parse(text) : {});
          const path = request.url?.split('/').at(-1);
          const subject = payload.sub;
          const expected = {
            'session-authorizations': 'community:session:authorize',
            'realtime-authorizations': 'community:subscribe',
            'message-projections': 'community:message:project',
            'replay-projections': 'community:replay:project',
          }[path ?? ''];
          if (
            payload.scope !== expected ||
            body.conversationId !== payload.conversationId ||
            body.messageId !== payload.messageId
          )
            throw new Error('Target mismatch');
          this.calls.push({
            subject,
            scope: String(payload.scope),
            ...(body.conversationId ? { conversation: body.conversationId } : {}),
            ...(body.messageId ? { message: body.messageId } : {}),
          });
          if (this.requestGate) await this.requestGate;
          if (this.unavailable) {
            response.writeHead(503).end('{}');
            return;
          }
          if (
            this.denied.has(subject) ||
            ![communityAlice, communityBob, communityPeer].includes(subject) ||
            (path !== 'session-authorizations' && body.conversationId !== communityConversation)
          ) {
            response.writeHead(404).end('{}');
            return;
          }
          response.setHeader('Cache-Control', 'no-store');
          response.setHeader('Content-Type', 'application/json');
          if (path === 'session-authorizations' || path === 'realtime-authorizations') {
            response.end(JSON.stringify({ allowed: true, decisionVersion: '0' }));
            return;
          }
          if (path === 'message-projections') {
            const message = this.messages.find((value) => value.messageId === body.messageId);
            if (!message || this.hidden.has(subject + ':' + message.messageId)) {
              response.writeHead(404).end('{}');
              return;
            }
            if (this.projectionGate) await this.projectionGate;
            response.end(
              JSON.stringify(
                this.malformed ? { ...message, conversationId: randomUUID() } : message,
              ),
            );
            return;
          }
          if (path === 'replay-projections') {
            const epoch = this.epochs.get(subject) ?? 0;
            const position = body.cursor
              ? this.cursors.get(body.cursor)
              : {
                  subject,
                  conversation: communityConversation,
                  after: this.messages.length,
                  epoch,
                };
            if (
              position?.subject !== subject ||
              position.conversation !== communityConversation ||
              position.epoch !== epoch
            ) {
              response.writeHead(404).end('{}');
              return;
            }
            const eligible = this.messages
              .map((message, index) => ({ message, index }))
              .filter(
                ({ message, index }) =>
                  index >= position.after && !this.hidden.has(subject + ':' + message.messageId),
              );
            const page = eligible.slice(0, 50);
            const after = page.at(-1)?.index;
            const cursor = randomUUID();
            this.cursors.set(cursor, {
              ...position,
              after: after === undefined ? position.after : after + 1,
            });
            const read = this.reads.get(subject) ?? 0;
            const visible = this.messages.filter(
              (message, index) =>
                index >= read &&
                message.sender.state === 'AVAILABLE' &&
                message.sender.communityProfileId !== subject &&
                !this.hidden.has(subject + ':' + message.messageId),
            );
            response.end(
              JSON.stringify({
                messageIds: page.map(({ message }) => message.messageId),
                cursor,
                hasMore: eligible.length > 50,
                readState: {
                  lastReadMessageId:
                    read === 0 ? null : (this.messages[read - 1]?.messageId ?? null),
                  unreadCount: visible.length,
                },
              }),
            );
            return;
          }
          response.writeHead(404).end('{}');
        } catch {
          response.writeHead(401).end('{}');
        }
      })();
    });
    await new Promise<void>((resolve) => this.server?.listen(0, '127.0.0.1', resolve));
    this.url = 'http://127.0.0.1:' + String((this.server.address() as AddressInfo).port);
  }
  async close(): Promise<void> {
    const server = this.server;
    if (server)
      await new Promise<void>((resolve, reject) => {
        server.close((error) => {
          if (error) reject(error);
          else resolve();
        });
        server.closeAllConnections();
      });
  }
  save(sender = communityAlice): CommunityMessage {
    const message: CommunityMessage = {
      messageId: randomUUID(),
      conversationId: communityConversation,
      clientMessageId: randomUUID(),
      sender: { state: 'AVAILABLE', communityProfileId: sender, displayName: 'Synthetic peer' },
      content: 'Private synthetic chat ' + String(this.messages.length + 1),
      createdAt: new Date().toISOString(),
    };
    this.messages.push(message);
    return message;
  }
  fact(message?: CommunityMessage): CommunityFact {
    return factSchema.parse({
      messageId: randomUUID(),
      messageType: 'community.chat.fact',
      schemaVersion: '1.0',
      producer: 'community-service',
      occurredAt: new Date().toISOString(),
      correlationId: randomUUID(),
      aggregateId: communityConversation,
      aggregateVersion: String(this.messages.length + 1),
      payload: message
        ? {
            kind: 'MESSAGE_SAVED',
            conversationId: communityConversation,
            targetId: message.messageId,
            sequence: String(this.messages.indexOf(message) + 1),
          }
        : { kind: 'AUTHORIZATION_CHANGED', conversationId: communityConversation },
    });
  }
  reset(): void {
    this.messages.splice(0);
    this.denied.clear();
    this.hidden.clear();
    this.epochs.clear();
    this.reads.clear();
    this.calls.splice(0);
    this.cursors.clear();
    this.unavailable = false;
    this.malformed = false;
    delete this.projectionGate;
    delete this.requestGate;
  }
}
