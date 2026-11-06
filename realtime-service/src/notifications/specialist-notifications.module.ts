import { Global, Module } from '@nestjs/common';
import { ContentNotificationClient } from './content-notification.client.js';
import { SpecialistNotificationFanout } from './specialist-notification-fanout.js';
import { SpecialistNotificationDelivery } from './specialist-notification-delivery.js';

@Global()
@Module({
  providers: [
    ContentNotificationClient,
    SpecialistNotificationFanout,
    SpecialistNotificationDelivery,
  ],
  exports: [SpecialistNotificationFanout, SpecialistNotificationDelivery],
})
export class SpecialistNotificationsModule {}
