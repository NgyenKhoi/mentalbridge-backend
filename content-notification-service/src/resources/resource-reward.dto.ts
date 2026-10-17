import { z } from 'zod';

export const ResourceRewardVisibilityUpdateSchema = z
  .object({ visibilityState: z.enum(['VISIBLE', 'HIDDEN']) })
  .strict();
