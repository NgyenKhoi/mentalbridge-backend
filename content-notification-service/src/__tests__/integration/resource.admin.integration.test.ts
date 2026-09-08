import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Pool } from 'pg';
import { GenericContainer, Wait, type StartedTestContainer } from 'testcontainers';
import { readFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

import { ResourceRepository } from '../../resources/resource.repository.js';
import type { DatabaseService } from '../../database/database.service.js';

const __dirname = dirname(fileURLToPath(import.meta.url));

async function waitForPool(pool: Pool, retries = 20, delayMs = 1000): Promise<void> {
  for (let i = 0; i < retries; i++) {
    try {
      await pool.query('SELECT 1');
      return;
    } catch {
      await new Promise((r) => setTimeout(r, delayMs));
    }
  }
  throw new Error('PostgreSQL not ready after retries');
}

describe('ResourceRepository Admin Operations Integration', () => {
  let container: StartedTestContainer;
  let pool: Pool;
  let repository: ResourceRepository;

  beforeAll(async () => {
    container = await new GenericContainer('postgres:16-alpine')
      .withEnvironment({
        POSTGRES_USER: 'test_user',
        POSTGRES_PASSWORD: 'test_password',
        POSTGRES_DB: 'test_db',
      })
      .withExposedPorts(5432)
      .withWaitStrategy(Wait.forLogMessage('database system is ready to accept connections', 2))
      .start();

    pool = new Pool({
      host: container.getHost(),
      port: container.getMappedPort(5432),
      user: 'test_user',
      password: 'test_password',
      database: 'test_db',
      connectionTimeoutMillis: 10_000,
    });

    await waitForPool(pool);

    // Run migrations
    const migrations = [
      '1_initial_schema.sql',
      '2_remove_hotline_catalogue.sql',
      '3_add_review_provenance_fields.sql',
    ];
    for (const migration of migrations) {
      const sql = readFileSync(join(__dirname, '../../../migrations', migration), 'utf8');
      await pool.query(sql);
    }

    const dbService: Pick<DatabaseService, 'query'> = {
      query: <T extends Record<string, unknown>>(text: string, params?: unknown[]) =>
        pool.query<T>(text, params),
    };
    repository = new ResourceRepository(dbService as unknown as DatabaseService);
  }, 120_000);

  afterAll(async () => {
    if (pool) await pool.end();
    if (container) await container.stop();
  });

  describe('Create Operation', () => {
    it('creates a new DRAFT resource with all fields', async () => {
      const resource = await repository.create({
        category: 'BREATHING',
        locale: 'vi-VN',
        title: 'Test Breathing Exercise',
        summary: 'A calming breathing technique',
        contentBody: 'Breathe in for 4 seconds, hold for 7, exhale for 8',
        externalUrl: null,
      });

      expect(resource.id).toBeDefined();
      expect(resource.status).toBe('DRAFT');
      expect(resource.category).toBe('BREATHING');
      expect(resource.title).toBe('Test Breathing Exercise');
      expect(resource.reviewed_by).toBeNull();
      expect(resource.reviewed_at).toBeNull();
      expect(resource.version).toBe(0);
    });

    it('creates resource with external URL instead of content body', async () => {
      const resource = await repository.create({
        category: 'VIDEO',
        locale: 'en-US',
        title: 'Meditation Video',
        summary: 'Guided meditation',
        contentBody: null,
        externalUrl: 'https://example.com/video',
      });

      expect(resource.external_url).toBe('https://example.com/video');
      expect(resource.content_body).toBeNull();
    });
  });

  describe('Update Operation', () => {
    it('updates a DRAFT resource successfully', async () => {
      const created = await repository.create({
        category: 'ARTICLE',
        locale: 'vi-VN',
        title: 'Original Title',
        summary: 'Original summary',
        contentBody: 'Original content',
        externalUrl: null,
      });

      const updated = await repository.update(created.id, {
        title: 'Updated Title',
        summary: 'Updated summary',
        version: 0,
      });

      expect(updated).not.toBeNull();
      expect(updated!.title).toBe('Updated Title');
      expect(updated!.summary).toBe('Updated summary');
      expect(updated!.version).toBe(1);
      expect(updated!.content_body).toBe('Original content');
    });

    it('fails to update with wrong version (optimistic lock)', async () => {
      const created = await repository.create({
        category: 'MEDITATION',
        locale: 'vi-VN',
        title: 'Test',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      const updated = await repository.update(created.id, {
        title: 'New Title',
        version: 999, // Wrong version
      });

      expect(updated).toBeNull();
    });

    it('fails to update PUBLISHED resource', async () => {
      const created = await repository.create({
        category: 'JOURNALING',
        locale: 'vi-VN',
        title: 'Journal Prompt',
        summary: 'Daily reflection',
        contentBody: 'What are you grateful for?',
        externalUrl: null,
      });

      // Publish it
      await repository.publish(created.id, {
        reviewedBy: 'b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      // Try to update
      const updated = await repository.update(created.id, {
        title: 'Should Fail',
        version: 1,
      });

      expect(updated).toBeNull();
    });
  });

  describe('Delete Operation', () => {
    it('deletes a DRAFT resource', async () => {
      const created = await repository.create({
        category: 'COMMUNITY',
        locale: 'vi-VN',
        title: 'To be deleted',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      const deleted = await repository.delete(created.id);
      expect(deleted).toBe(true);

      const found = await repository.findById(created.id);
      expect(found).toBeNull();
    });

    it('fails to delete PUBLISHED resource', async () => {
      const created = await repository.create({
        category: 'BREATHING',
        locale: 'vi-VN',
        title: 'Published Resource',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      await repository.publish(created.id, {
        reviewedBy: 'c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      const deleted = await repository.delete(created.id);
      expect(deleted).toBe(false);
    });
  });

  describe('Publish Operation', () => {
    it('publishes a DRAFT resource with review metadata', async () => {
      const created = await repository.create({
        category: 'MEDITATION',
        locale: 'vi-VN',
        title: 'Ready to Publish',
        summary: 'Meditation guide',
        contentBody: 'Sit comfortably...',
        externalUrl: null,
      });

      const adminId = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';
      const published = await repository.publish(created.id, {
        reviewedBy: adminId,
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(published).not.toBeNull();
      expect(published!.status).toBe('PUBLISHED');
      expect(published!.reviewed_by).toBe(adminId);
      expect(published!.reviewed_at).not.toBeNull();
      expect(published!.version).toBe(1);
    });

    it('publishes with future effective date', async () => {
      const created = await repository.create({
        category: 'VIDEO',
        locale: 'en-US',
        title: 'Future Release',
        summary: 'Coming soon',
        contentBody: null,
        externalUrl: 'https://example.com/video',
      });

      const futureDate = new Date(Date.now() + 24 * 60 * 60 * 1000);
      const published = await repository.publish(created.id, {
        reviewedBy: 'd0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: futureDate,
        expiresAt: null,
      });

      expect(published!.effective_at).not.toBeNull();
      expect(new Date(published!.effective_at!).getTime()).toBeGreaterThan(Date.now());
    });

    it('fails to publish with wrong version', async () => {
      const created = await repository.create({
        category: 'ARTICLE',
        locale: 'vi-VN',
        title: 'Version Mismatch',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      const published = await repository.publish(created.id, {
        reviewedBy: '00000000-0000-0000-0000-000000000000',
        version: 99,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(published).toBeNull();
    });

    it('fails to publish already PUBLISHED resource', async () => {
      const created = await repository.create({
        category: 'BREATHING',
        locale: 'vi-VN',
        title: 'Already Published',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      // First publish
      await repository.publish(created.id, {
        reviewedBy: 'e0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      // Try to publish again
      const secondPublish = await repository.publish(created.id, {
        reviewedBy: 'f0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 1,
        effectiveAt: null,
        expiresAt: null,
      });

      expect(secondPublish).toBeNull();
    });
  });

  describe('Archive Operation', () => {
    it('archives a PUBLISHED resource', async () => {
      const created = await repository.create({
        category: 'JOURNALING',
        locale: 'vi-VN',
        title: 'To be Archived',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      const adminId = '10eebc99-9c0b-4ef8-bb6d-6bb9bd380a11';
      const published = await repository.publish(created.id, {
        reviewedBy: adminId,
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      const archived = await repository.archive(created.id, published!.version);

      expect(archived).not.toBeNull();
      expect(archived!.status).toBe('ARCHIVED');
      expect(archived!.version).toBe(2);
      expect(archived!.reviewed_by).toBe(adminId);
      expect(archived!.reviewed_at).not.toBeNull();
    });

    it('fails to archive with wrong version', async () => {
      const created = await repository.create({
        category: 'COMMUNITY',
        locale: 'vi-VN',
        title: 'Archive Version Test',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      await repository.publish(created.id, {
        reviewedBy: '11eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      const archived = await repository.archive(created.id, 99);
      expect(archived).toBeNull();
    });

    it('fails to archive DRAFT resource', async () => {
      const created = await repository.create({
        category: 'MEDITATION',
        locale: 'vi-VN',
        title: 'Still Draft',
        summary: 'Test',
        contentBody: 'Test',
        externalUrl: null,
      });

      const archived = await repository.archive(created.id, 0);
      expect(archived).toBeNull();
    });
  });

  describe('State Transition Rules', () => {
    it('enforces DRAFT → PUBLISHED → ARCHIVED workflow', async () => {
      const draft = await repository.create({
        category: 'ARTICLE',
        locale: 'vi-VN',
        title: 'Workflow Test',
        summary: 'Test complete workflow',
        contentBody: 'Content',
        externalUrl: null,
      });

      expect(draft.status).toBe('DRAFT');

      // Update allowed on DRAFT
      const updated = await repository.update(draft.id, {
        title: 'Updated Draft',
        version: 0,
      });
      expect(updated).not.toBeNull();

      // Publish
      const published = await repository.publish(draft.id, {
        reviewedBy: '12eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 1,
        effectiveAt: null,
        expiresAt: null,
      });
      expect(published!.status).toBe('PUBLISHED');

      // Update not allowed on PUBLISHED
      const updateFailed = await repository.update(draft.id, {
        title: 'Should Fail',
        version: 2,
      });
      expect(updateFailed).toBeNull();

      // Archive
      const archived = await repository.archive(draft.id, 2);
      expect(archived!.status).toBe('ARCHIVED');

      // Archive is immutable
      const updateArchived = await repository.update(draft.id, {
        title: 'Should Fail',
        version: 3,
      });
      expect(updateArchived).toBeNull();
    });
  });

  describe('Publication Filtering Safety', () => {
    it('PUBLIC endpoint never returns DRAFT resources', async () => {
      await repository.create({
        category: 'BREATHING',
        locale: 'vi-VN',
        title: 'Draft Resource',
        summary: 'Should not appear',
        contentBody: 'Test',
        externalUrl: null,
      });

      const published = await repository.listPublished({ limit: 100 });
      const hasDraft = published.some((r) => r.status === 'DRAFT');

      expect(hasDraft).toBe(false);
    });

    it('PUBLIC endpoint never returns resources without review', async () => {
      const published = await repository.listPublished({ limit: 100 });
      const hasUnreviewed = published.some((r) => !r.reviewed_by || !r.reviewed_at);

      expect(hasUnreviewed).toBe(false);
    });

    it('PUBLIC endpoint never returns future-effective resources', async () => {
      const created = await repository.create({
        category: 'VIDEO',
        locale: 'vi-VN',
        title: 'Future Content',
        summary: 'Not yet active',
        contentBody: null,
        externalUrl: 'https://example.com',
      });

      const futureDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
      await repository.publish(created.id, {
        reviewedBy: '13eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: futureDate,
        expiresAt: null,
      });

      const published = await repository.listPublished({ limit: 100 });
      const hasFuture = published.some((r) => r.id === created.id);

      expect(hasFuture).toBe(false);
    });

    it('PUBLIC endpoint never returns expired resources', async () => {
      const created = await repository.create({
        category: 'ARTICLE',
        locale: 'vi-VN',
        title: 'Expired Content',
        summary: 'Already expired',
        contentBody: 'Old content',
        externalUrl: null,
      });

      const pastDate = new Date(Date.now() - 24 * 60 * 60 * 1000);
      await repository.publish(created.id, {
        reviewedBy: '14eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: pastDate,
      });

      const published = await repository.listPublished({ limit: 100 });
      const hasExpired = published.some((r) => r.id === created.id);

      expect(hasExpired).toBe(false);
    });

    it('PUBLIC endpoint never returns ARCHIVED resources', async () => {
      const created = await repository.create({
        category: 'MEDITATION',
        locale: 'vi-VN',
        title: 'Archived Content',
        summary: 'Archived',
        contentBody: 'Test',
        externalUrl: null,
      });

      const published = await repository.publish(created.id, {
        reviewedBy: '15eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
        version: 0,
        effectiveAt: null,
        expiresAt: null,
      });

      await repository.archive(created.id, published!.version);

      const result = await repository.listPublished({ limit: 100 });
      const hasArchived = result.some((r) => r.id === created.id);

      expect(hasArchived).toBe(false);
    });
  });
});
