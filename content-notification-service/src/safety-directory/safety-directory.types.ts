export type SafetyDirectoryEntryType = 'FACILITY' | 'HOTLINE';
export type SafetyDirectoryCoverageLevel = 'NATIONWIDE' | 'PROVINCE' | 'DISTRICT';
export type SafetyDirectoryReviewState = 'UNREVIEWED' | 'CURRENT' | 'STALE' | 'INACTIVE';
export type SafetyDirectoryLookupState = 'RESULTS' | 'EMPTY' | 'INVALID_AREA';

export interface SafetyDirectoryCoverage {
  readonly level: SafetyDirectoryCoverageLevel;
  readonly provinceCode: string | null;
  readonly provinceName: string | null;
  readonly districtCode: string | null;
  readonly districtName: string | null;
}

export interface SafetyDirectoryRow {
  readonly id: string;
  readonly name: string;
  readonly entry_type: SafetyDirectoryEntryType;
  readonly phone: string;
  readonly address: string | null;
  readonly active: boolean;
  readonly source_name: string;
  readonly source_reference: string;
  readonly source_retrieved_at: Date;
  readonly source_checksum: string | null;
  readonly reviewed_by: string | null;
  readonly reviewed_at: Date | null;
  readonly verified_by: string | null;
  readonly verified_at: Date | null;
  readonly seed_key: string | null;
  readonly created_at: Date;
  readonly updated_at: Date;
  readonly record_version: number;
  readonly review_state: SafetyDirectoryReviewState;
  readonly coverage: SafetyDirectoryCoverage[];
}

export interface SafetyDirectoryPublicEntry {
  readonly directoryEntryId: string;
  readonly name: string;
  readonly type: SafetyDirectoryEntryType;
  readonly phone: string;
  readonly address: string | null;
  readonly coverage: SafetyDirectoryCoverage[];
  readonly sourceName: string;
  readonly sourceReference: string;
  readonly reviewedAt: string;
  readonly verifiedAt: string;
}

export interface SafetyDirectoryAdminEntry {
  readonly directoryEntryId: string;
  readonly recordVersion: number;
  readonly name: string;
  readonly type: SafetyDirectoryEntryType;
  readonly phone: string;
  readonly address: string | null;
  readonly coverage: SafetyDirectoryCoverage[];
  readonly active: boolean;
  readonly reviewState: SafetyDirectoryReviewState;
  readonly sourceName: string;
  readonly sourceReference: string;
  readonly sourceRetrievedAt: string;
  readonly sourceChecksum: string | null;
  readonly reviewedBy: string | null;
  readonly reviewedAt: string | null;
  readonly verifiedBy: string | null;
  readonly verifiedAt: string | null;
  readonly seedKey: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface SafetyDirectoryLookupResponse {
  readonly state: SafetyDirectoryLookupState;
  readonly wording: 'Cơ sở trong khu vực đã chọn';
  readonly resolvedProvinceCode: string | null;
  readonly resolvedDistrictCode: string | null;
  readonly entries: SafetyDirectoryPublicEntry[];
}
