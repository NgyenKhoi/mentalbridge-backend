import { Module, type DynamicModule } from '@nestjs/common';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import type { ConversationEvidence } from '../conversations/conversation-evidence.js';
import { MessagesModule } from '../messages/messages.module.js';
import { PresenceModule } from '../presence/presence.module.js';
import { SecurityModule } from '../security/security.module.js';
import { RealtimeGateway } from './realtime.gateway.js';

@Module({})
export class WebsocketModule {
  static register(
    eligibility?: ConversationEligibility,
    evidence?: ConversationEvidence,
  ): DynamicModule {
    return {
      module: WebsocketModule,
      imports: [MessagesModule.register(eligibility, evidence), PresenceModule, SecurityModule],
      providers: [RealtimeGateway],
    };
  }
}
