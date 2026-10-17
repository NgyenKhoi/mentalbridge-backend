import { z } from 'zod';

const trimmed = (maximum: number) => z.string().trim().min(1).max(maximum);
const nullableTrimmed = (maximum: number) => z.string().trim().min(1).max(maximum).nullable();

export const SafetyDirectoryCoverageSchema = z
  .object({
    level: z.enum(['NATIONWIDE', 'PROVINCE', 'DISTRICT']),
    provinceCode: nullableTrimmed(32),
    provinceName: nullableTrimmed(120),
    districtCode: nullableTrimmed(32),
    districtName: nullableTrimmed(120),
  })
  .strict()
  .superRefine((coverage, context) => {
    const provinceEmpty = coverage.provinceCode === null && coverage.provinceName === null;
    const provinceComplete = coverage.provinceCode !== null && coverage.provinceName !== null;
    const districtEmpty = coverage.districtCode === null && coverage.districtName === null;
    const districtComplete = coverage.districtCode !== null && coverage.districtName !== null;
    const valid =
      (coverage.level === 'NATIONWIDE' && provinceEmpty && districtEmpty) ||
      (coverage.level === 'PROVINCE' && provinceComplete && districtEmpty) ||
      (coverage.level === 'DISTRICT' && provinceComplete && districtComplete);
    if (!valid) {
      context.addIssue({ code: 'custom', message: 'coverage fields do not match level' });
    }
  });

export const SafetyDirectoryEntryWriteSchema = z
  .object({
    name: trimmed(200),
    type: z.enum(['FACILITY', 'HOTLINE']),
    phone: trimmed(64),
    address: nullableTrimmed(500),
    coverage: z.array(SafetyDirectoryCoverageSchema).min(1).max(100),
    sourceName: trimmed(200),
    sourceReference: trimmed(2000),
    sourceRetrievedAt: z.iso.datetime({ offset: true }).transform((value) => new Date(value)),
    sourceChecksum: z
      .string()
      .regex(/^[0-9a-f]{64}$/)
      .nullable()
      .optional(),
  })
  .strict()
  .superRefine((entry, context) => {
    if (entry.type === 'FACILITY' && entry.address === null) {
      context.addIssue({ code: 'custom', path: ['address'], message: 'address is required' });
    }
  });

export const SafetyDirectoryLookupSchema = z
  .object({
    provinceCode: trimmed(32).optional(),
    districtCode: trimmed(32).optional(),
    manualLocation: trimmed(120).optional(),
  })
  .strict()
  .superRefine((lookup, context) => {
    const selected = lookup.provinceCode !== undefined;
    const manual = lookup.manualLocation !== undefined;
    if (selected === manual) {
      context.addIssue({
        code: 'custom',
        message: 'provide exactly one of provinceCode or manualLocation',
      });
    }
    if (lookup.districtCode !== undefined && !selected) {
      context.addIssue({
        code: 'custom',
        path: ['districtCode'],
        message: 'districtCode requires provinceCode',
      });
    }
  });

export type SafetyDirectoryEntryWrite = z.infer<typeof SafetyDirectoryEntryWriteSchema>;
export type SafetyDirectoryLookup = z.infer<typeof SafetyDirectoryLookupSchema>;
