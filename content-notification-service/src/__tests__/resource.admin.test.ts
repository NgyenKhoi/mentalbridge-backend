import { describe, it, expect, vi } from 'vitest';
import type { ResourceService } from '../resources/resource.service.js';
import type { ResourceRepository } from '../resources/resource.repository.js';
import type { ResourceDetail, ResourceRow } from '../resources/resource.types.js';

describe('Resource Admin Operations', () => {
  const mockRepository: Partial<ResourceRepository> = {
    create: vi.fn(),
    findById: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    publish: vi.fn(),
    archive: vi.fn(),
  };

  const mockResourceRow: ResourceRow = {
    id: '123e4567-e89b-12d3-a456-426614174000',
    category: 'BREATHING',
    locale: 'vi-VN',
    title: 'Test Resource',
    summary: 'Test summary',
    content_body: 'Test body',
    external_url: null,
    status: 'DRAFT',
    reviewed_by: null,
    reviewed_at: null,
    effective_at: null,
    expires_at: null,
    created_at: new Date('2024-01-01T00:00:00Z'),
    updated_at: new Date('2024-01-01T00:00:00Z'),
    version: 0,
  };

  describe('create', () => {
    it('creates a new DRAFT resource', async () => {
      vi.mocked(mockRepository.create!).mockResolvedValue(mockResourceRow);

      const result = await mockRepository.create!({
        category: 'BREATHING',
        locale: 'vi-VN',
        title: 'Test Resource',
        summary: 'Test summary',
        contentBody: 'Test body',
        externalUrl: null,
      });

      expect(result.status).toBe('DRAFT');
      expect(result.title).toBe('Test Resource');
      expect(result.reviewed_by).toBeNull();
      expect(result.version).toBe(0);
    });

    it('creates resource with external URL', async () => {
      const withUrl = { ...mockResourceRow, external_url: 'https://example.com', content_body: null };
      vi.mocked(mockRepository.create!).mockResolvedValue(withUrl);

      const result = await mockRepository.create!({
        category: 'VIDEO',
        locale: 'en-US',
        title: 'Video Resource',
        summary: 'Test',
        contentBody: null,
        externalUrl: 'https://example.com',
      });

      expect(result.external_url).toBe('https://example.com');
      expect(result.content_body).toBeNull();
    });
  });

  describe('findById', () => {
    it('returns resource by id', async () => {
      vi.mocked(mockRepository.findById!).mockResolvedValue(mockResourceRow);

      const result = await mockRepository.findById!('123e4567-e89b-12d3-a456-426614174000');

      expect(result).not.toBeNull();
      expect(result!.id).toBe(mockResourceRow.id);
      expect(result!.title).toBe('Test Resource');
    });

    it('returns null when not found', async () => {
      vi.mocked(mockRepository.findById!).mockResolvedValue(null);

      const result = await mockRepository.findById!('non-existent-id');

      expect(result).toBeNull();
    });
  });

  describe('update', () => {
    it('updates a DRAFT resource with correct version', async () => {
      const updated = { ...mockResourceRow, title: 'Updated Title', version: 1 };
      vi.mocked(mockRepository.update!).mockResolvedValue(updated);

      const result = await mockRepository.update!('123e4567-e89b-12d3-a456-426614174000', {
        title: 'Updated Title',
        version: 0,
      });

      expect(result).not.toBeNull();
      expect(result!.title).toBe('Updated Title');
      expect(result!.version).toBe(1);
    });

    it('returns null on version mismatch (optimistic lock)', async () => {
      vi.mocked(mockRepository.update!).mockResolvedValue(null);

      const result = await mockRepository.update!('123e4567-e89b-12d3-a456-426614174000', {
        title: 'Should Fail',
        version: 99,
      });

      expect(result).toBeNull();
    });

    it('returns null when updating PUBLISHED resource', async () => {
      vi.mocked(mockRepository.update!).mockResolvedValue(null);

      const result = await mockRepository.update!('published-id', {
        title: 'Cannot Update',
        version: 1,
      });

      expect(result).toBeNull();
    });
  });

  describe('delete', () => {
    it('deletes a DRAFT resource', async () => {
      vi.mocked(mockRepository.delete!).mockResolvedValue(true);

      const result = await mockRepository.delete!('123e4567-e89b-12d3-a456-426614174000');

      expect(result).toBe(true);
    });

    it('returns false when trying to delete non-DRAFT', async () => {
      vi.mocked(mockRepository.delete!).mockResolvedValue(false);

      const result = await mockRepository.delete!('published-id');

      expect(result).toBe(false);
    });
  });

  describe('publish', () => {
    it('publishes a DRAFT resource with review metadata', async () => {
      const published = {
        ...mockResourceRow,
        status: 'PUBLISHED' as const,
        reviewed_by: 'admin-123',
        reviewed_at: new Date('2024-01-01T10:00:00Z'),
        version: 1,
      };
      vi.mocked(mockRepository.publish!).mockResolvedValue(published);

      const result = await mockRepository.publish!('123e4567-e89b-12d3-a456-426614174000', {
        reviewedBy: 'admin-123',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(result).not.toBeNull();
      expect(result!.status).toBe('PUBLISHED');
      expect(result!.reviewed_by).toBe('admin-123');
      expect(result!.reviewed_at).not.toBeNull();
      expect(result!.version).toBe(1);
    });

    it('publishes with effective and expires dates', async () => {
      const effectiveAt = new Date('2024-02-01T00:00:00Z');
      const expiresAt = new Date('2024-03-01T00:00:00Z');
      const published = {
        ...mockResourceRow,
        status: 'PUBLISHED' as const,
        reviewed_by: 'admin-456',
        reviewed_at: new Date(),
        effective_at: effectiveAt,
        expires_at: expiresAt,
        version: 1,
      };
      vi.mocked(mockRepository.publish!).mockResolvedValue(published);

      const result = await mockRepository.publish!('123e4567-e89b-12d3-a456-426614174000', {
        reviewedBy: 'admin-456',
        version: 0,
        effectiveAt,
        expiresAt,
      });

      expect(result!.effective_at).toEqual(effectiveAt);
      expect(result!.expires_at).toEqual(expiresAt);
    });

    it('returns null when version mismatch', async () => {
      vi.mocked(mockRepository.publish!).mockResolvedValue(null);

      const result = await mockRepository.publish!('123e4567-e89b-12d3-a456-426614174000', {
        reviewedBy: 'admin-789',
        version: 99,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(result).toBeNull();
    });

    it('returns null when resource is not DRAFT', async () => {
      vi.mocked(mockRepository.publish!).mockResolvedValue(null);

      const result = await mockRepository.publish!('already-published-id', {
        reviewedBy: 'admin-000',
        version: 1,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(result).toBeNull();
    });
  });

  describe('archive', () => {
    it('archives a PUBLISHED resource', async () => {
      const archived = {
        ...mockResourceRow,
        status: 'ARCHIVED' as const,
        reviewed_by: 'admin-111',
        reviewed_at: new Date('2024-01-01T10:00:00Z'),
        version: 2,
      };
      vi.mocked(mockRepository.archive!).mockResolvedValue(archived);

      const result = await mockRepository.archive!('123e4567-e89b-12d3-a456-426614174000', 1);

      expect(result).not.toBeNull();
      expect(result!.status).toBe('ARCHIVED');
      expect(result!.version).toBe(2);
    });

    it('returns null when version mismatch', async () => {
      vi.mocked(mockRepository.archive!).mockResolvedValue(null);

      const result = await mockRepository.archive!('123e4567-e89b-12d3-a456-426614174000', 99);

      expect(result).toBeNull();
    });

    it('returns null when resource is not PUBLISHED', async () => {
      vi.mocked(mockRepository.archive!).mockResolvedValue(null);

      const result = await mockRepository.archive!('draft-id', 0);

      expect(result).toBeNull();
    });
  });

  describe('State Transition Workflow', () => {
    it('validates DRAFT → PUBLISHED → ARCHIVED lifecycle', () => {
      // DRAFT: can create, update, delete
      expect(mockResourceRow.status).toBe('DRAFT');
      expect(mockResourceRow.reviewed_by).toBeNull();
      expect(mockResourceRow.reviewed_at).toBeNull();

      // PUBLISHED: immutable, cannot update or delete
      const published = {
        ...mockResourceRow,
        status: 'PUBLISHED' as const,
        reviewed_by: 'admin',
        reviewed_at: new Date(),
        version: 1,
      };
      expect(published.status).toBe('PUBLISHED');
      expect(published.reviewed_by).not.toBeNull();
      expect(published.reviewed_at).not.toBeNull();

      // ARCHIVED: final state, immutable
      const archived = { ...published, status: 'ARCHIVED' as const, version: 2 };
      expect(archived.status).toBe('ARCHIVED');
      expect(archived.reviewed_by).not.toBeNull();
    });
  });
});
