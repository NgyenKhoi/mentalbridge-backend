import { z } from 'zod';

import { NOTIFICATION_KINDS } from './notification.types.js';

const UUID = z.uuid();
const TargetlessActionSchema = z
  .object({
    type: z.enum([
      'OPEN_JOURNAL',
      'OPEN_MESSAGES',
      'OPEN_APPOINTMENTS',
      'OPEN_RESOURCES',
      'OPEN_ASSESSMENTS',
    ]),
  })
  .strict();
const TargetedActionSchema = z
  .object({
    type: z.literal('OPEN_RESOURCE'),
    targetId: UUID,
  })
  .strict();

export const NotificationCreateSchema = z
  .object({
    ownerId: UUID,
    kind: z.enum(NOTIFICATION_KINDS),
    title: z.string().trim().min(1).max(255),
    body: z.string().trim().min(1).max(1000),
    occurredAt: z.iso.datetime({ offset: true }),
    action: z.union([TargetlessActionSchema, TargetedActionSchema]).optional(),
    source: z.string().regex(/^[A-Z][A-Z0-9_]{0,63}$/),
    sourceIdentity: z.string().regex(/^[A-Za-z0-9][A-Za-z0-9:._/-]{0,159}$/),
    priority: z.enum(['LOW', 'NORMAL', 'HIGH']).default('NORMAL'),
    expiresAt: z.iso.datetime({ offset: true }).optional(),
  })
  .strict();

export const NotificationIdSchema = UUID;
