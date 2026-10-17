import { ConflictException, Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { SAFETY_DIRECTORY_REPOSITORY_TOKEN } from '../application.tokens.js';
import type { SafetyDirectoryEntryWrite, SafetyDirectoryLookup } from './safety-directory.dto.js';
import { SafetyDirectoryIdempotencyConflictError } from './safety-directory.repository.js';
import type {
  SafetyDirectoryCommandContext,
  SafetyDirectoryRepository,
} from './safety-directory.repository.js';
import type {
  SafetyDirectoryAdminEntry,
  SafetyDirectoryLookupResponse,
  SafetyDirectoryPublicEntry,
  SafetyDirectoryRow,
} from './safety-directory.types.js';

function toAdmin(row: SafetyDirectoryRow): SafetyDirectoryAdminEntry {
  return {
    directoryEntryId: row.id,
    recordVersion: row.record_version,
    name: row.name,
    type: row.entry_type,
    phone: row.phone,
    address: row.address,
    coverage: row.coverage,
    active: row.active,
    reviewState: row.review_state,
    sourceName: row.source_name,
    sourceReference: row.source_reference,
    sourceRetrievedAt: new Date(row.source_retrieved_at).toISOString(),
    sourceChecksum: row.source_checksum,
    reviewedBy: row.reviewed_by,
    reviewedAt: row.reviewed_at ? new Date(row.reviewed_at).toISOString() : null,
    verifiedBy: row.verified_by,
    verifiedAt: row.verified_at ? new Date(row.verified_at).toISOString() : null,
    seedKey: row.seed_key,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

function toPublic(row: SafetyDirectoryRow): SafetyDirectoryPublicEntry {
  if (!row.reviewed_at || !row.verified_at) {
    throw new Error('Current directory entry is missing review evidence');
  }
  return {
    directoryEntryId: row.id,
    name: row.name,
    type: row.entry_type,
    phone: row.phone,
    address: row.address,
    coverage: row.coverage,
    sourceName: row.source_name,
    sourceReference: row.source_reference,
    reviewedAt: new Date(row.reviewed_at).toISOString(),
    verifiedAt: new Date(row.verified_at).toISOString(),
  };
}

@Injectable()
export class SafetyDirectoryService {
  constructor(
    @Inject(SAFETY_DIRECTORY_REPOSITORY_TOKEN)
    private readonly repository: SafetyDirectoryRepository,
  ) {}

  async listAdmin(): Promise<SafetyDirectoryAdminEntry[]> {
    return this.wrap(async () => (await this.repository.listAdmin()).map(toAdmin));
  }

  async create(
    data: SafetyDirectoryEntryWrite,
    idempotencyKey: string,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryAdminEntry> {
    try {
      return toAdmin(await this.repository.create(data, idempotencyKey, context));
    } catch (error) {
      if (error instanceof SafetyDirectoryIdempotencyConflictError) {
        throw new ConflictException({
          type: 'https://mentalbridge.io/errors/IDEMPOTENCY_KEY_REUSED',
          title: 'Idempotency key was reused with different content',
          status: 409,
          code: 'IDEMPOTENCY_KEY_REUSED',
        });
      }
      return this.unavailable(error);
    }
  }

  async update(
    id: string,
    version: number,
    data: SafetyDirectoryEntryWrite,
  ): Promise<SafetyDirectoryAdminEntry | null> {
    return this.wrap(async () => {
      const row = await this.repository.update(id, version, data);
      return row ? toAdmin(row) : null;
    });
  }

  async review(
    id: string,
    version: number,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryAdminEntry | null> {
    return this.wrap(async () => {
      const row = await this.repository.review(id, version, context);
      return row ? toAdmin(row) : null;
    });
  }

  async deactivate(
    id: string,
    version: number,
    context: SafetyDirectoryCommandContext,
  ): Promise<SafetyDirectoryAdminEntry | null> {
    return this.wrap(async () => {
      const row = await this.repository.deactivate(id, version, context);
      return row ? toAdmin(row) : null;
    });
  }

  async lookup(query: SafetyDirectoryLookup): Promise<SafetyDirectoryLookupResponse> {
    return this.wrap(async () => {
      const result = await this.repository.lookup(query);
      if (!result.area) {
        return {
          state: 'INVALID_AREA',
          wording: 'Cơ sở trong khu vực đã chọn',
          resolvedProvinceCode: null,
          resolvedDistrictCode: null,
          entries: [],
        };
      }
      const entries = result.entries.map(toPublic);
      return {
        state: entries.length > 0 ? 'RESULTS' : 'EMPTY',
        wording: 'Cơ sở trong khu vực đã chọn',
        resolvedProvinceCode: result.area.provinceCode,
        resolvedDistrictCode: result.area.districtCode,
        entries,
      };
    });
  }

  private async wrap<T>(operation: () => Promise<T>): Promise<T> {
    try {
      return await operation();
    } catch (error) {
      if (error instanceof ServiceUnavailableException) throw error;
      return this.unavailable(error);
    }
  }

  private unavailable(error: unknown): never {
    if (error instanceof ServiceUnavailableException) throw error;
    throw new ServiceUnavailableException({
      type: 'https://mentalbridge.io/errors/DEPENDENCY_UNAVAILABLE',
      title: 'Safety directory is temporarily unavailable',
      status: 503,
      code: 'DEPENDENCY_UNAVAILABLE',
    });
  }
}
