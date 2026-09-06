import { Module, type DynamicModule } from '@nestjs/common';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import { MessagesModule } from '../messages/messages.module.js';
import { PresenceModule } from '../presence/presence.module.js';
import { SecurityModule } from '../security/security.module.js';
import { RealtimeGateway } from './realtime.gateway.js';

@Module({})
export class WebsocketModule {
  static register(eligibility?: ConversationEligibility): DynamicModule {
    return {
      module: WebsocketModule,
      imports: [MessagesModule.register(eligibility), PresenceModule, SecurityModule],
      providers: [RealtimeGateway],
    };
  }
}
