import { z } from 'zod';

export function communityPushPayload(notificationId: string) {
  return {
    notificationId: z.uuid().parse(notificationId),
    body: 'Bạn có thông báo mới trên MentalBridge.',
  };
}
