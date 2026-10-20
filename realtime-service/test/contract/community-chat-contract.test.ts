import SwaggerParser from '@apidevtools/swagger-parser';
import Ajv2020Import from 'ajv/dist/2020.js';
import addFormatsImport from 'ajv-formats';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { z } from 'zod';

const directory = new URL('../../../contracts/proposals/', import.meta.url);
const record = z.record(z.string(), z.unknown());
const apiShape = z.object({
  openapi: z.literal('3.1.0'),
  paths: z.record(z.string(), record),
  components: z.object({ schemas: z.record(z.string(), record) }),
});
const examplesShape = z.object({
  handshake: record,
  rest: z.array(z.object({ schema: z.string(), value: record })),
  commands: z.array(record),
  events: z.array(record),
  facts: z.array(record),
});
const loadJson = async (name: string): Promise<unknown> =>
  JSON.parse(await readFile(new URL(name, directory), 'utf8')) as unknown;
const api = apiShape.parse(
  await SwaggerParser.validate(fileURLToPath(new URL('community-chat-v1.openapi.json', directory))),
);
const examples = examplesShape.parse(await loadJson('community-chat-v1.examples.json'));
const Ajv2020 = Ajv2020Import.default;
const addFormats = addFormatsImport.default;
const compile = (schema: object) => {
  const ajv = new Ajv2020({ strict: true, allErrors: true });
  addFormats(ajv);
  return ajv.compile(schema);
};
const component = (name: string) => {
  const schema = api.components.schemas[name];
  if (!schema) throw new Error(`Missing Community chat schema ${name}`);
  return compile(schema);
};
const command = compile(record.parse(await loadJson('community-chat-command-v1.schema.json')));
const handshake = compile(record.parse(await loadJson('community-chat-handshake-v1.schema.json')));
const event = compile(record.parse(await loadJson('community-chat-realtime-v1.schema.json')));
const fact = compile(record.parse(await loadJson('community-chat-fact-v1.schema.json')));
const example = (kind: 'commands' | 'events' | 'facts', index = 0) => {
  const value = examples[kind][index];
  if (!value) throw new Error('Missing synthetic contract example');
  return structuredClone(value);
};
const payload = (value: Record<string, unknown>) => record.parse(value.payload);
const uuid = '11111111-1111-4111-8111-111111111111';

describe('MB-684 Community contract candidate', () => {
  it('strict-compiles every REST component, including non-example request and error shapes', () => {
    for (const name of Object.keys(api.components.schemas))
      expect(component(name)).toBeTypeOf('function');
  });

  it('closes the Community handshake and rejects direct bearer/actor injection', () => {
    expect(handshake(examples.handshake)).toBe(true);
    expect(handshake({ ...examples.handshake, schemaVersion: 2 })).toBe(false);
    expect(handshake({ schemaVersion: 1, accessToken: 'synthetic-bearer' })).toBe(false);
    expect(handshake({ ...examples.handshake, actorId: uuid })).toBe(false);
    expect(handshake({ ...examples.handshake, credential: '' })).toBe(false);
  });
  it('resolves OpenAPI and keeps every new operation planned and authenticated', () => {
    const operations: string[] = [];
    for (const [path, item] of Object.entries(api.paths)) {
      expect(item['x-mentalbridge-status']).toBe('planned');
      expect(
        path.startsWith('/api/v1/community/chat') || path.startsWith('/internal/v1/community/'),
      ).toBe(true);
      for (const method of ['get', 'post', 'put', 'delete']) {
        if (!item[method]) continue;
        const operation = record.parse(item[method]);
        expect(z.array(record).parse(operation.security)).toHaveLength(1);
        expect(record.parse(operation.responses)).toHaveProperty('503');
        expect(record.parse(operation.responses)).toHaveProperty('404');
        expect(record.parse(operation.responses)).toHaveProperty('429');
        if (path.includes('/internal/')) {
          expect(operation['x-mentalbridge-role']).toBe('SERVICE');
        } else if (path.includes('/admin/')) {
          expect(operation['x-mentalbridge-role']).toBe('ADMIN');
        } else {
          expect(operation['x-mentalbridge-role']).toBe('USER');
        }
        operations.push(`${method} ${path}`);
      }
    }
    expect(operations).toHaveLength(26);
  });

  it('requires concurrency and idempotency at irreversible management boundaries', () => {
    for (const path of [
      '/requests/{requestId}/decision',
      '/invitations/{invitationId}/decision',
      '/groups/{conversationId}/ownership',
    ]) {
      const operation = record.parse(api.paths[`/api/v1/community/chat${path}`]?.post);
      const parameters = z.array(record).parse(operation.parameters);
      expect(parameters).toContainEqual(
        expect.objectContaining({ name: 'If-Match', required: true }),
      );
    }
    for (const path of [
      '/direct-conversations',
      '/groups',
      '/conversations/{conversationId}/messages',
      '/reports',
    ]) {
      const operation = record.parse(api.paths[`/api/v1/community/chat${path}`]?.post);
      expect(z.array(record).parse(operation.parameters)).toContainEqual(
        expect.objectContaining({ name: 'Idempotency-Key', required: true }),
      );
    }
  });

  it.each(examples.rest)('accepts the synthetic REST $schema example', ({ schema, value }) => {
    const validate = component(schema);
    expect(validate(value), JSON.stringify(validate.errors)).toBe(true);
  });

  it('requires explicit privacy choices and preserves the default example', () => {
    const defaults = record.parse(
      examples.rest.find((item) => item.schema === 'PrivacyView')?.value.preferences,
    );
    expect(defaults).toEqual({
      directMessagePolicy: 'APPROVAL_REQUIRED',
      presenceVisible: false,
      invisible: false,
      typingVisible: false,
      readReceiptsVisible: false,
    });
    const validate = component('Privacy');
    expect(validate({ ...defaults, directMessagePolicy: 'ANYONE' })).toBe(true);
    expect(validate({ ...defaults, directMessagePolicy: 'FRIENDS' })).toBe(false);
    expect(validate({ ...defaults, presenceVisible: undefined })).toBe(false);
    expect(validate({ ...defaults, ownerId: uuid })).toBe(false);
  });

  it('excludes request text, anonymous lookups, client senders and owner escalation', () => {
    expect(
      component('StartDirect')({ recipientCommunityProfileId: uuid, content: 'Unsolicited text' }),
    ).toBe(false);
    expect(component('StartDirect')({ anonymousPostId: uuid })).toBe(false);
    expect(
      component('SendMessage')({ clientMessageId: uuid, content: 'Synthetic', senderId: uuid }),
    ).toBe(false);
    expect(component('SetRole')({ role: 'OWNER' })).toBe(false);
    expect(component('RequestDecision')({ decision: 'AUTO_ACCEPT' })).toBe(false);
    expect(component('InviteDecision')({ decision: 'JOIN_WITHOUT_ACCEPTANCE' })).toBe(false);
    expect(component('Peer')({ state: 'UNAVAILABLE', communityProfileId: uuid })).toBe(false);
  });

  it('bounds plain text, pages, roles and safe sequence representation', () => {
    const validate = component('SendMessage');
    expect(validate({ clientMessageId: uuid, content: 'a'.repeat(4000) })).toBe(true);
    expect(validate({ clientMessageId: uuid, content: '' })).toBe(false);
    expect(validate({ clientMessageId: uuid, content: ' \n\t ' })).toBe(false);
    expect(validate({ clientMessageId: uuid, content: 'a'.repeat(4001) })).toBe(false);
    expect(validate({ clientMessageId: uuid, content: 'x', attachments: [] })).toBe(false);
    expect(component('ReadCursor')({ lastReadSequence: '9007199254740993' })).toBe(true);
    expect(component('ReadCursor')({ lastReadSequence: 9007199254740992 })).toBe(false);
    expect(component('ReadCursor')({ lastReadSequence: '-1' })).toBe(false);
    expect(component('AcceptedDirect')({ state: 'PENDING', requestId: uuid })).toBe(false);
    expect(component('MessagePage')({ items: [], nextCursor: null })).toBe(true);
    expect(component('MessagePage')({ items: [], nextCursor: 'x'.repeat(1025) })).toBe(false);
  });

  it.each(examples.commands)('accepts client command $commandType', (value) => {
    expect(command(value), JSON.stringify(command.errors)).toBe(true);
  });

  it('rejects appointment commands, actor spoofing and fabricated presence duration', () => {
    const value = example('commands');
    expect(command({ ...value, commandType: 'message.send' })).toBe(false);
    expect(command({ ...value, schemaVersion: 2 })).toBe(false);
    expect(command({ ...value, actorId: uuid })).toBe(false);
    expect(command({ ...value, payload: { ...payload(value), ownerId: uuid } })).toBe(false);
    expect(
      command({
        ...example('commands', 1),
        payload: { foreground: true, online: true, presentForSeconds: 1800 },
      }),
    ).toBe(false);
  });

  it.each(examples.events)('accepts authorized private event $eventType', (value) => {
    expect(event(value), JSON.stringify(event.errors)).toBe(true);
  });

  it('never exposes exact last-seen, clinical state or routing identity on sockets', () => {
    const value = example('events', 1);
    for (const extra of [
      { lastSeenAt: '2026-10-20T06:00:00Z' },
      { mood: 'sad' },
      { accountId: uuid },
      { recipientRoutingReference: uuid },
    ]) {
      expect(event({ ...value, payload: { ...payload(value), ...extra } })).toBe(false);
    }
    expect(
      event({ ...value, payload: { communityProfileId: uuid, state: 'WILLING_TO_TALK' } }),
    ).toBe(false);
  });

  it.each(examples.facts)('accepts minimized durable fact', (value) => {
    expect(fact(value), JSON.stringify(fact.errors)).toBe(true);
  });

  it('rejects text, health fields and identity metadata at every durable fact boundary', () => {
    for (const value of examples.facts) {
      for (const key of [
        'content',
        'displayName',
        'groupName',
        'email',
        'assessmentScore',
        'journal',
        'providerPayload',
        'accessToken',
        'lastSeenAt',
      ]) {
        expect(
          fact({ ...value, payload: { ...payload(value), [key]: 'private synthetic value' } }),
        ).toBe(false);
        expect(fact({ ...value, [key]: 'private synthetic value' })).toBe(false);
      }
      expect(fact({ ...value, schemaVersion: '2.0' })).toBe(false);
      expect(fact({ ...value, producer: 'realtime-service' })).toBe(false);
    }
  });

  it('distinguishes pointer facts from recipient notifications and rejects malformed variants', () => {
    const pointer = example('facts');
    expect(
      fact({ ...pointer, payload: { ...payload(pointer), recipientRoutingReference: uuid } }),
    ).toBe(false);
    const notification = example('facts', 2);
    expect(
      fact({
        ...notification,
        payload: { ...payload(notification), recipientRoutingReference: undefined },
      }),
    ).toBe(false);
    expect(
      fact({ ...notification, payload: { ...payload(notification), kind: 'PRESENCE_ONLINE' } }),
    ).toBe(false);
    expect(
      fact({ ...notification, payload: { ...payload(notification), targetId: 'not-a-uuid' } }),
    ).toBe(false);
    expect(fact({ ...notification, aggregateVersion: 1 })).toBe(false);
  });

  it('bounds moderation to explicit reported evidence rather than transcript access', () => {
    expect(component('Report')({ messageId: uuid, reasonCode: 'PRIVACY_CONCERN' })).toBe(true);
    expect(component('Report')({ messageId: uuid, reasonCode: 'OTHER', transcript: [] })).toBe(
      false,
    );
    expect(
      component('ModerateCase')({ action: 'READ_ALL_MESSAGES', reasonCode: 'ABUSE_PREVENTION' }),
    ).toBe(false);
    expect(
      component('CaseEvidence')({
        caseId: uuid,
        messageId: uuid,
        reportedContent: 'Synthetic reported text',
        capturedAt: '2026-10-20T06:00:00Z',
        version: '1',
        surroundingMessages: [],
      }),
    ).toBe(false);
  });
});
