import { describe, expect, it, vi } from 'vitest';
import { randomUUID } from 'node:crypto';

import { ResourceRepository } from '../resources/resource.repository.js';
import type { ResourceDatabaseRow } from '../resources/resource.types.js';
import { SafetyDirectoryRepository } from '../safety-directory/safety-directory.repository.js';
import type { SafetyDirectoryRow } from '../safety-directory/safety-directory.types.js';
import {
  KafkaContentAdminAuditPublisher,
  type ContentAdminAuditEvent,
} from '../audit/content-admin-audit.publisher.js';
import type { DatabaseService } from '../database/database.service.js';

describe('Content Administration Audit Outbox Architecture', () => {
  const actorId = randomUUID();
  const correlationId = randomUUID();
  const commandContext = { actorId, correlationId };

  function createMockResourceDbRow(
    overrides: Partial<ResourceDatabaseRow> = {},
  ): ResourceDatabaseRow {
    return {
      id: randomUUID(),
      category: 'ARTICLE',
      resource_kind: 'GUIDE',
      interaction_type: 'READING',
      repeatability: 'UNRESTRICTED',
      completion_mode: 'IMMEDIATE',
      streak_eligible: false,
      expected_duration_minutes: 5,
      cooldown_days: 0,
      recommended_frequency_per_week: 1,
      plan_tags: ['TAG'],
      locale: 'vi-VN',
      title: 'Học cách thư giãn',
      summary: 'Tóm tắt bài viết',
      external_url: null,
      source_organization: null,
      status: 'ARCHIVED',
      reviewed_at: new Date('2026-01-01T00:00:00Z'),
      created_at: new Date('2026-01-01T00:00:00Z'),
      updated_at: new Date('2026-01-02T00:00:00Z'),
      content_body: 'Nội dung',
      source_title: null,
      source_url: null,
      source_review_note: null,
      structured_content: {},
      interaction_config: {},
      safetyNotes: [],
      safety_notes: [],
      source_retrieved_at: null,
      source_content_hash: null,
      content_version_label: 'v1',
      source_review_status: 'REVIEWED',
      reviewed_by: actorId,
      effective_at: null,
      expires_at: null,
      version: 2,
      catalogue_visibility: 'LISTED',
      ...overrides,
    };
  }

  function createMockSafetyRow(overrides: Partial<SafetyDirectoryRow> = {}): SafetyDirectoryRow {
    return {
      id: randomUUID(),
      name: 'Đường dây nóng hỗ trợ',
      entry_type: 'HOTLINE',
      phone: '19001234',
      address: 'Hà Nội',
      active: true,
      source_name: 'Bộ Y Tế',
      source_reference: 'REF-001',
      source_retrieved_at: new Date('2026-01-01T00:00:00Z'),
      source_checksum: 'checksum-123',
      reviewed_by: actorId,
      reviewed_at: new Date('2026-01-01T00:00:00Z'),
      verified_by: actorId,
      verified_at: new Date('2026-01-01T00:00:00Z'),
      seed_key: null,
      created_at: new Date('2026-01-01T00:00:00Z'),
      updated_at: new Date('2026-01-02T00:00:00Z'),
      record_version: 1,
      review_state: 'CURRENT',
      coverage: [],
      ...overrides,
    };
  }

  describe('ResourceRepository Outbox writes', () => {
    it('archive writes RESOURCE_ARCHIVED outbox row in transaction', async () => {
      const resourceId = randomUUID();
      const updatedRow = createMockResourceDbRow({ id: resourceId, version: 2 });

      const executedQueries: { sql: string; params?: any[] }[] = [];
      const client = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          executedQueries.push({ sql, params });
          if (sql.includes('UPDATE resource')) {
            return { rowCount: 1, rows: [updatedRow] };
          }
          if (sql.includes('INSERT INTO resource_audit_event')) {
            return { rowCount: 1, rows: [] };
          }
          if (sql.includes('INSERT INTO content_admin_audit_outbox')) {
            return { rowCount: 1, rows: [] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const mockDb: Partial<DatabaseService> = {
        withTransaction: vi.fn().mockImplementation(async (callback: any) => callback(client)),
      };

      const repo = new ResourceRepository(mockDb as DatabaseService);
      const result = await repo.archive(resourceId, 1, commandContext);

      expect(result).not.toBeNull();
      expect(result?.id).toBe(resourceId);

      const outboxQuery = executedQueries.find((q) => q.sql.includes('content_admin_audit_outbox'));
      expect(outboxQuery).toBeDefined();

      const [eventId, dedupKey, eventType, outboxCorrelationId, payloadJson, occurredAt] =
        outboxQuery!.params!;

      expect(dedupKey).toBe(`audit:content:resource-archived:${resourceId}:2`);
      expect(eventType).toBe('content.resource.archived');
      expect(outboxCorrelationId).toBe(correlationId);
      expect(typeof eventId).toBe('string');
      expect(typeof occurredAt).toBe('string');

      const payload = JSON.parse(payloadJson);
      expect(payload).toEqual({
        eventId,
        eventType: 'content.resource.archived',
        occurredAt,
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'RESOURCE_ARCHIVED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      });
    });

    it('archive does not write outbox row when mutation fails or version mismatch', async () => {
      const resourceId = randomUUID();
      const executedQueries: { sql: string; params?: any[] }[] = [];
      const client = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          executedQueries.push({ sql, params });
          if (sql.includes('UPDATE resource')) {
            return { rowCount: 0, rows: [] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const mockDb: Partial<DatabaseService> = {
        withTransaction: vi.fn().mockImplementation(async (callback: any) => callback(client)),
      };

      const repo = new ResourceRepository(mockDb as DatabaseService);
      const result = await repo.archive(resourceId, 999, commandContext);

      expect(result).toBeNull();
      const outboxQuery = executedQueries.find((q) => q.sql.includes('content_admin_audit_outbox'));
      expect(outboxQuery).toBeUndefined();
    });
  });

  describe('SafetyDirectoryRepository Outbox writes', () => {
    it('review writes SAFETY_DIRECTORY_REVIEWED outbox row in transaction', async () => {
      const entryId = randomUUID();
      const reviewedRow = createMockSafetyRow({ id: entryId, record_version: 2 });

      const executedQueries: { sql: string; params?: any[] }[] = [];
      const client = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          executedQueries.push({ sql, params });
          if (sql.includes('UPDATE safety_directory_entry')) {
            return {
              rowCount: 1,
              rows: [{ record_version: 2, source_reference: 'REF-001' }],
            };
          }
          if (sql.includes('INSERT INTO safety_directory_review_history')) {
            return { rowCount: 1, rows: [] };
          }
          if (sql.includes('INSERT INTO content_admin_audit_outbox')) {
            return { rowCount: 1, rows: [] };
          }
          if (sql.includes('SELECT') && sql.includes('safety_directory_entry')) {
            return { rowCount: 1, rows: [reviewedRow] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const mockDb: Partial<DatabaseService> = {
        withTransaction: vi.fn().mockImplementation(async (callback: any) => callback(client)),
      };

      const repo = new SafetyDirectoryRepository(mockDb as DatabaseService);
      const result = await repo.review(entryId, 1, commandContext);

      expect(result).not.toBeNull();

      const outboxQuery = executedQueries.find((q) => q.sql.includes('content_admin_audit_outbox'));
      expect(outboxQuery).toBeDefined();

      const [eventId, dedupKey, eventType, outboxCorrelationId, payloadJson, occurredAt] =
        outboxQuery!.params!;

      expect(dedupKey).toBe(`audit:content:safety-reviewed:${entryId}:2`);
      expect(eventType).toBe('content.safety-directory.reviewed');
      expect(outboxCorrelationId).toBe(correlationId);

      const payload = JSON.parse(payloadJson);
      expect(payload).toEqual({
        eventId,
        eventType: 'content.safety-directory.reviewed',
        occurredAt,
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'SAFETY_DIRECTORY_REVIEWED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      });
    });

    it('deactivate writes SAFETY_DIRECTORY_DEACTIVATED outbox row when active entry deactivated', async () => {
      const entryId = randomUUID();
      const initialActiveRow = createMockSafetyRow({
        id: entryId,
        active: true,
        record_version: 1,
      });
      const deactivatedRow = createMockSafetyRow({
        id: entryId,
        active: false,
        record_version: 2,
      });

      const executedQueries: { sql: string; params?: any[] }[] = [];
      const client = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          executedQueries.push({ sql, params });
          if (sql.includes('SELECT') && sql.includes('safety_directory_entry')) {
            const hasUpdated = executedQueries.some((q) =>
              q.sql.includes('UPDATE safety_directory_entry'),
            );
            return { rowCount: 1, rows: [hasUpdated ? deactivatedRow : initialActiveRow] };
          }
          if (sql.includes('UPDATE safety_directory_entry')) {
            return {
              rowCount: 1,
              rows: [{ record_version: 2, source_reference: 'REF-001' }],
            };
          }
          if (sql.includes('INSERT INTO safety_directory_review_history')) {
            return { rowCount: 1, rows: [] };
          }
          if (sql.includes('INSERT INTO content_admin_audit_outbox')) {
            return { rowCount: 1, rows: [] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const mockDb: Partial<DatabaseService> = {
        withTransaction: vi.fn().mockImplementation(async (callback: any) => callback(client)),
      };

      const repo = new SafetyDirectoryRepository(mockDb as DatabaseService);
      const result = await repo.deactivate(entryId, 1, commandContext);

      expect(result).not.toBeNull();

      const outboxQuery = executedQueries.find((q) => q.sql.includes('content_admin_audit_outbox'));
      expect(outboxQuery).toBeDefined();

      const [eventId, dedupKey, eventType, outboxCorrelationId, payloadJson, occurredAt] =
        outboxQuery!.params!;

      expect(dedupKey).toBe(`audit:content:safety-deactivated:${entryId}:2`);
      expect(eventType).toBe('content.safety-directory.deactivated');
      expect(outboxCorrelationId).toBe(correlationId);

      const payload = JSON.parse(payloadJson);
      expect(payload).toEqual({
        eventId,
        eventType: 'content.safety-directory.deactivated',
        occurredAt,
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'SAFETY_DIRECTORY_DEACTIVATED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      });
    });

    it('deactivate does NOT write outbox row when entry is already inactive (no-op)', async () => {
      const entryId = randomUUID();
      const alreadyInactiveRow = createMockSafetyRow({
        id: entryId,
        active: false,
        record_version: 1,
      });

      const executedQueries: { sql: string; params?: any[] }[] = [];
      const client = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          executedQueries.push({ sql, params });
          if (sql.includes('SELECT') && sql.includes('safety_directory_entry')) {
            return { rowCount: 1, rows: [alreadyInactiveRow] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const mockDb: Partial<DatabaseService> = {
        withTransaction: vi.fn().mockImplementation(async (callback: any) => callback(client)),
      };

      const repo = new SafetyDirectoryRepository(mockDb as DatabaseService);
      const result = await repo.deactivate(entryId, 1, commandContext);

      expect(result).not.toBeNull();
      expect(result?.active).toBe(false);

      const outboxQuery = executedQueries.find((q) => q.sql.includes('content_admin_audit_outbox'));
      expect(outboxQuery).toBeUndefined();
    });
  });

  describe('KafkaContentAdminAuditPublisher Relay Transport', () => {
    it('direct publish sends event with targetIdentifier or eventId key', async () => {
      const sendMock = vi
        .fn()
        .mockResolvedValue([
          { topicName: 'mentalbridge.admin.audit-event.v1', partition: 0, errorCode: 0 },
        ]);

      const publisher = new KafkaContentAdminAuditPublisher({
        KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092',
      } as any);
      (publisher as any).producer = { send: sendMock };

      const eventId = randomUUID();
      const event: ContentAdminAuditEvent = {
        eventId,
        eventType: 'content.resource.archived',
        occurredAt: new Date().toISOString(),
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'RESOURCE_ARCHIVED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      };

      await publisher.publish(event);

      expect(sendMock).toHaveBeenCalledTimes(1);
      expect(sendMock).toHaveBeenCalledWith({
        topic: 'mentalbridge.admin.audit-event.v1',
        messages: [
          {
            key: eventId,
            value: JSON.stringify(event),
          },
        ],
      });
    });

    it('publishDue relays pending outbox rows and marks published_at on success', async () => {
      const rowId = randomUUID();
      const eventId = randomUUID();
      const pendingEvent: ContentAdminAuditEvent = {
        eventId,
        eventType: 'content.resource.archived',
        occurredAt: new Date().toISOString(),
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'RESOURCE_ARCHIVED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      };

      const sendMock = vi.fn().mockResolvedValue([]);
      const dbExecutedQueries: { sql: string; params?: any[] }[] = [];

      const mockDb: Partial<DatabaseService> = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          dbExecutedQueries.push({ sql, params });
          if (sql.includes('SELECT id, payload')) {
            return {
              rowCount: 1,
              rows: [{ id: rowId, payload: pendingEvent }],
            };
          }
          if (
            sql.includes('UPDATE content_admin_audit_outbox') &&
            sql.includes('SET published_at')
          ) {
            return { rowCount: 1, rows: [] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const publisher = new KafkaContentAdminAuditPublisher(
        { KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092' } as any,
        mockDb as DatabaseService,
      );
      (publisher as any).producer = { send: sendMock };

      await publisher.publishDue();

      expect(sendMock).toHaveBeenCalledTimes(1);
      expect(sendMock).toHaveBeenCalledWith({
        topic: 'mentalbridge.admin.audit-event.v1',
        messages: [
          {
            key: eventId,
            value: JSON.stringify(pendingEvent),
          },
        ],
      });

      const markPublishedQuery = dbExecutedQueries.find(
        (q) => q.sql.includes('SET published_at = now()') && q.params?.[0] === rowId,
      );
      expect(markPublishedQuery).toBeDefined();
    });

    it('publishDue increments attempt_count and sets next_attempt_at on send error', async () => {
      const rowId = randomUUID();
      const eventId = randomUUID();
      const pendingEvent: ContentAdminAuditEvent = {
        eventId,
        eventType: 'content.safety-directory.reviewed',
        occurredAt: new Date().toISOString(),
        producer: 'content-notification-service',
        schemaVersion: '1.0',
        sourceService: 'CONTENT',
        domain: 'RESOURCE_MANAGEMENT',
        actorId,
        actorType: 'ADMIN',
        action: 'SAFETY_DIRECTORY_REVIEWED',
        result: 'SUCCEEDED',
        reasonCode: null,
        correlationId,
        targetAccountId: null,
        targetIdentifier: null,
      };

      const sendMock = vi.fn().mockRejectedValue(new Error('Broker connection timeout'));
      const dbExecutedQueries: { sql: string; params?: any[] }[] = [];

      const mockDb: Partial<DatabaseService> = {
        query: vi.fn().mockImplementation(async (sql: string, params?: any[]) => {
          dbExecutedQueries.push({ sql, params });
          if (sql.includes('SELECT id, payload')) {
            return {
              rowCount: 1,
              rows: [{ id: rowId, payload: pendingEvent }],
            };
          }
          if (sql.includes('SET attempt_count = attempt_count + 1')) {
            return { rowCount: 1, rows: [] };
          }
          return { rowCount: 0, rows: [] };
        }),
      };

      const publisher = new KafkaContentAdminAuditPublisher(
        { KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092' } as any,
        mockDb as DatabaseService,
      );
      (publisher as any).producer = { send: sendMock };

      await publisher.publishDue();

      expect(sendMock).toHaveBeenCalledTimes(1);
      const retryQuery = dbExecutedQueries.find((q) =>
        q.sql.includes('SET attempt_count = attempt_count + 1'),
      );
      expect(retryQuery).toBeDefined();
      expect(retryQuery?.params?.[0]).toBe(rowId);
    });

    it('publishDue handles database query error safely without rethrowing', async () => {
      const mockDb: Partial<DatabaseService> = {
        query: vi.fn().mockRejectedValue(new Error('Database unavailable')),
      };

      const publisher = new KafkaContentAdminAuditPublisher(
        { KAFKA_BOOTSTRAP_SERVERS: 'localhost:9092' } as any,
        mockDb as DatabaseService,
      );
      (publisher as any).producer = { send: vi.fn() };

      await expect(publisher.publishDue()).resolves.toBeUndefined();
    });
  });
});
