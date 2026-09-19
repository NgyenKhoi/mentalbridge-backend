import { ServiceUnavailableException } from '@nestjs/common';
import { describe, expect, it, vi } from 'vitest';

import type { SafetyDirectoryRepository } from '../safety-directory/safety-directory.repository.js';
import { SafetyDirectoryService } from '../safety-directory/safety-directory.service.js';
import type { SafetyDirectoryRow } from '../safety-directory/safety-directory.types.js';

const currentRow: SafetyDirectoryRow = {
  id: '123e4567-e89b-42d3-a456-426614174000',
  name: 'Cơ sở kiểm thử',
  entry_type: 'FACILITY',
  phone: 'DEMO-NOT-DIALABLE',
  address: 'Địa chỉ dữ liệu kiểm thử',
  coverage: [
    {
      level: 'PROVINCE',
      provinceCode: '79',
      provinceName: 'Hồ Chí Minh',
      districtCode: null,
      districtName: null,
    },
  ],
  active: true,
  review_state: 'CURRENT',
  source_name: 'Nguồn kiểm thử',
  source_reference: 'https://example.invalid/review',
  source_retrieved_at: new Date('2026-09-01T00:00:00Z'),
  source_checksum: null,
  reviewed_by: '223e4567-e89b-42d3-a456-426614174000',
  reviewed_at: new Date('2026-09-01T00:00:00Z'),
  verified_by: '223e4567-e89b-42d3-a456-426614174000',
  verified_at: new Date('2026-09-01T00:00:00Z'),
  seed_key: null,
  created_at: new Date('2026-09-01T00:00:00Z'),
  updated_at: new Date('2026-09-01T00:00:00Z'),
  record_version: 1,
};

function serviceWithLookup(lookup: SafetyDirectoryRepository['lookup']) {
  return new SafetyDirectoryService({ lookup } as SafetyDirectoryRepository);
}

describe('SafetyDirectoryService', () => {
  it('returns only provider-filtered reviewed records with truthful area wording', async () => {
    const service = serviceWithLookup(
      vi.fn(async () => ({
        area: { provinceCode: '79', districtCode: null },
        entries: [currentRow],
      })),
    );
    const result = await service.lookup({ provinceCode: '79' });
    expect(result.state).toBe('RESULTS');
    expect(result.wording).toBe('Cơ sở trong khu vực đã chọn');
    expect(result.entries[0]).toMatchObject({
      phone: 'DEMO-NOT-DIALABLE',
      sourceName: 'Nguồn kiểm thử',
    });
    expect(JSON.stringify(result)).not.toContain('nearest');
  });

  it('distinguishes invalid, empty, and unavailable lookup states', async () => {
    await expect(
      serviceWithLookup(vi.fn(async () => ({ area: null, entries: [] }))).lookup({
        manualLocation: 'không rõ',
      }),
    ).resolves.toMatchObject({ state: 'INVALID_AREA', entries: [] });
    await expect(
      serviceWithLookup(
        vi.fn(async () => ({ area: { provinceCode: '01', districtCode: null }, entries: [] })),
      ).lookup({ provinceCode: '01' }),
    ).resolves.toMatchObject({ state: 'EMPTY', entries: [] });
    await expect(
      serviceWithLookup(
        vi.fn(async () => {
          throw new Error('database down');
        }),
      ).lookup({ provinceCode: '01' }),
    ).rejects.toBeInstanceOf(ServiceUnavailableException);
  });

  it('passes manualLocation through to repository lookup so alias resolution is delegated', async () => {
    // The service must forward manualLocation to the repository unchanged.
    // The repository resolves it via safety_directory_area_alias — not coverage.
    // A province with many coverage rows must still resolve to RESULTS here
    // because the mock returns area deterministically (simulating alias lookup).
    const lookup = vi.fn(async () => ({
      area: { provinceCode: '79', districtCode: null },
      entries: [currentRow],
    }));
    const service = serviceWithLookup(lookup);
    const result = await service.lookup({ manualLocation: 'Hồ Chí Minh' });
    expect(lookup).toHaveBeenCalledWith({ manualLocation: 'Hồ Chí Minh' });
    expect(result.state).toBe('RESULTS');
    expect(result.resolvedProvinceCode).toBe('79');
    expect(result.resolvedDistrictCode).toBeNull();
  });

  it('returns INVALID_AREA when alias vocabulary has no match for the manual text', async () => {
    // Repository returns area: null when alias_text is not found in the vocabulary.
    const service = serviceWithLookup(vi.fn(async () => ({ area: null, entries: [] })));
    const result = await service.lookup({ manualLocation: 'Khu vực không tồn tại' });
    expect(result.state).toBe('INVALID_AREA');
    expect(result.entries).toHaveLength(0);
    // resolvedProvinceCode must be null — no false area claim
    expect(result.resolvedProvinceCode).toBeNull();
  });
});
