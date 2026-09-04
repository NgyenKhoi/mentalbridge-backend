import { randomUUID } from 'node:crypto';

const pattern = /^[A-Za-z0-9._:-]{1,128}$/;

export const normalizeCorrelationId = (value: unknown): string =>
  typeof value === 'string' && pattern.test(value) ? value : randomUUID();
