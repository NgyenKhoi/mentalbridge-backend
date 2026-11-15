import { Module, type DynamicModule } from '@nestjs/common';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import type { ConversationEvidence } from '../conversations/conversation-evidence.js';
import { ConversationsModule } from '../conversations/conversations.module.js';
import { ConversationRepository } from '../conversations/conversation.repository.js';
import { HistoryController } from '../history/history.controller.js';
import { SecurityModule } from '../security/security.module.js';
import { AppointmentModerationController } from '../moderation/appointment-moderation.controller.js';
import { AppointmentModerationRepository } from '../moderation/appointment-moderation.repository.js';
import { AppointmentModerationService } from '../moderation/appointment-moderation.service.js';
import { MessageEncryptionService } from './message-encryption.service.js';
import { MessageRepository } from './message.repository.js';
import { MessageService } from './message.service.js';

@Module({})
export class MessagesModule {
  static register(
    eligibility?: ConversationEligibility,
    evidence?: ConversationEvidence,
  ): DynamicModule {
    return {
      module: MessagesModule,
      imports: [ConversationsModule.register(eligibility, evidence), SecurityModule],
      controllers: [HistoryController, AppointmentModerationController],
      providers: [
        ConversationRepository,
        MessageEncryptionService,
        MessageRepository,
        MessageService,
        AppointmentModerationRepository,
        AppointmentModerationService,
      ],
      exports: [MessageService],
    };
  }
}
