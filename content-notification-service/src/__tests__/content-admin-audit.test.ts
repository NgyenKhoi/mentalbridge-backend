import { describe, expect, it, vi } from 'vitest';
import { randomUUID } from 'node:crypto';

import { ResourceService } from '../resources/resource.service.js';
import type { ResourceRepository } from '../resources/resource.repository.js';
import { SafetyDirectoryService } from '../safety-directory/safety-directory.service.js';
import type { SafetyDirectoryRepository } from '../safety-directory/safety-directory.repository.js';
import {
  KafkaContentAdminAuditPublisher,
  type ContentAdminAuditEvent,
  type ContentAdminAuditPublisher,
} from '../audit/content-admin-audit.publisher.js';

describe('Content Administration Audit Producer', () => {
  const actorId = randomUUID();
  const correlationId = randomUUID();
  const commandContext = { actorId, correlationId };

  it('resource archive produces content.resource.archived audit fact', async () => {
    const resourceId = randomUUID();
    const publishedRow = {
      id: resourceId,
      category: 'ARTICLE' as const,
      resource_kind: 'GUIDE' as const,
      interaction_type: 'READING' as const,
      repeatability: 'UNRESTRICTED' as const,
      completionMode: 'IMMEDIATE',
      completion_mode: 'IMMEDIATE' as const,
      streak_eligible: false,
      expected_duration_minutes: 5,
      cooldown_days: 0,
      recommended_frequency_per_week: 1,
      plan_tags: [],
      locale: 'vi-VN',
      title: 'Tài liệu hướng dẫn',
      summary: 'Tóm tắt',
      external_url: null,
      source_organization: null,
      status: 'ARCHIVED' as const,
      reviewed_at: new Date(),
      created_at: new Date(),
      updated_at: new Date(),
      version: 2,
    };

    const mockRepo: Partial<ResourceRepository> = {
      archive: vi.fn().mockResolvedValue(publishedRow),
    };

    const auditPublisher: ContentAdminAuditPublisher = {
      publish: vi.fn().mockResolvedValue(undefined),
    };

    const service = new ResourceService(
      mockRepo as ResourceRepository,
      { enabled: false },
      auditPublisher,
    );

    const result = await service.archive(resourceId, 1, commandContext);
    expect(result).not.toBeNull();
    expect(auditPublisher.publish).toHaveBeenCalledTimes(1);

    const event = vi.mocked(auditPublisher.publish).mock.calls[0][0];
    expect(event.eventType).toBe('content.resource.archived');
    expect(event.action).toBe('RESOURCE_ARCHIVED');
    expect(event.sourceService).toBe('CONTENT');
    expect(event.domain).toBe('RESOURCE_MANAGEMENT');
    expect(event.actorId).toBe(actorId);
    expect(event.actorType).toBe('ADMIN');
    expect(event.result).toBe('SUCCEEDED');
    expect(event.reasonCode).toBe('RESOURCE_ARCHIVED');
    expect(event.correlationId).toBe(correlationId);
    expect(event.targetIdentifier).toBeNull();
    expect(event.targetAccountId).toBeNull();
    expect(event.schemaVersion).toBe('1.0');
    expect(event.producer).toBe('content-notification-service');
  });

  it('safety directory review produces content.safety-directory.reviewed fact', async () => {
    const entryId = randomUUID();
    const mockRow = {
      id: entryId,
      record_version: 2,
      name: 'Trung tâm hỗ trợ',
      entry_type: 'HOTLINE',
      phone: '19001234',
      address: 'Hà Nội',
      coverage: 'NATIONAL',
      active: true,
      review_state: 'VERIFIED',
      source_name: 'Bộ Y Tế',
      source_reference: 'REF-001',
      source_retrieved_at: new Date(),
      source_checksum: 'hash',
      reviewed_by: actorId,
      reviewed_at: new Date(),
      verified_by: actorId,
      verified_at: new Date(),
      seed_key: null,
      created_at: new Date(),
      updated_at: new Date(),
    };

    const mockRepo: Partial<SafetyDirectoryRepository> = {
      review: vi.fn().mockResolvedValue(mockRow),
    };

    const auditPublisher: ContentAdminAuditPublisher = {
      publish: vi.fn().mockResolvedValue(undefined),
    };

    const service = new SafetyDirectoryService(
      mockRepo as SafetyDirectoryRepository,
      auditPublisher,
    );

    const result = await service.review(entryId, 1, commandContext);
    expect(result).not.toBeNull();
    expect(auditPublisher.publish).toHaveBeenCalledTimes(1);

    const event = vi.mocked(auditPublisher.publish).mock.calls[0][0];
    expect(event.eventType).toBe('content.safety-directory.reviewed');
    expect(event.action).toBe('SAFETY_DIRECTORY_REVIEWED');
    expect(event.sourceService).toBe('CONTENT');
    expect(event.domain).toBe('RESOURCE_MANAGEMENT');
    expect(event.actorId).toBe(actorId);
    expect(event.actorType).toBe('ADMIN');
    expect(event.result).toBe('SUCCEEDED');
    expect(event.reasonCode).toBeNull();
    expect(event.correlationId).toBe(correlationId);
    expect(event.targetIdentifier).toBeNull();
    expect(event.targetAccountId).toBeNull();
  });

  it('safety directory deactivate produces content.safety-directory.deactivated fact', async () => {
    const entryId = randomUUID();
    const mockRow = {
      id: entryId,
      record_version: 2,
      name: 'Trung tâm hỗ trợ',
      entry_type: 'HOTLINE',
      phone: '19001234',
      address: 'Hà Nội',
      coverage: 'NATIONAL',
      active: false,
      review_state: 'FLAGGED',
      source_name: 'Bộ Y Tế',
      source_reference: 'REF-001',
      source_retrieved_at: new Date(),
      source_checksum: 'hash',
      reviewed_by: actorId,
      reviewed_at: new Date(),
      verified_by: actorId,
      verified_at: new Date(),
      seed_key: null,
      created_at: new Date(),
      updated_at: new Date(),
    };

    const mockRepo: Partial<SafetyDirectoryRepository> = {
      deactivate: vi.fn().mockResolvedValue(mockRow),
    };

    const auditPublisher: ContentAdminAuditPublisher = {
      publish: vi.fn().mockResolvedValue(undefined),
    };

    const service = new SafetyDirectoryService(
      mockRepo as SafetyDirectoryRepository,
      auditPublisher,
    );

    const result = await service.deactivate(entryId, 1, commandContext);
    expect(result).not.toBeNull();
    expect(auditPublisher.publish).toHaveBeenCalledTimes(1);

    const event = vi.mocked(auditPublisher.publish).mock.calls[0][0];
    expect(event.eventType).toBe('content.safety-directory.deactivated');
    expect(event.action).toBe('SAFETY_DIRECTORY_DEACTIVATED');
    expect(event.sourceService).toBe('CONTENT');
    expect(event.domain).toBe('RESOURCE_MANAGEMENT');
    expect(event.actorId).toBe(actorId);
    expect(event.actorType).toBe('ADMIN');
    expect(event.result).toBe('SUCCEEDED');
    expect(event.correlationId).toBe(correlationId);
    expect(event.targetIdentifier).toBeNull();
    expect(event.targetAccountId).toBeNull();
  });

  it('failed mutation or version mismatch does not emit audit fact', async () => {
    const mockRepo: Partial<ResourceRepository> = {
      archive: vi.fn().mockResolvedValue(null),
    };

    const auditPublisher: ContentAdminAuditPublisher = {
      publish: vi.fn().mockResolvedValue(undefined),
    };

    const service = new ResourceService(
      mockRepo as ResourceRepository,
      { enabled: false },
      auditPublisher,
    );

    const result = await service.archive(randomUUID(), 999, commandContext);
    expect(result).toBeNull();
    expect(auditPublisher.publish).not.toHaveBeenCalled();
  });

  it('kafka publisher sends formatted event to topic', async () => {
    const sendMock = vi
      .fn()
      .mockResolvedValue([{ topicName: 'topic', partition: 0, errorCode: 0 }]);
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
      actorId: randomUUID(),
      actorType: 'ADMIN',
      action: 'RESOURCE_ARCHIVED',
      result: 'SUCCEEDED',
      reasonCode: 'RESOURCE_ARCHIVED',
      correlationId: randomUUID(),
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
});
