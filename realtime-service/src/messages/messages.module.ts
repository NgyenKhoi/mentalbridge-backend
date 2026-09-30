import { Module, type DynamicModule } from '@nestjs/common';

import type { ConversationEligibility } from '../conversations/conversation-eligibility.js';
import { ConversationsModule } from '../conversations/conversations.module.js';
import { ConversationRepository } from '../conversations/conversation.repository.js';
import { HistoryController } from '../history/history.controller.js';
import { SecurityModule } from '../security/security.module.js';
import { MessageEncryptionService } from './message-encryption.service.js';
import { MessageRepository } from './message.repository.js';
import { MessageService } from './message.service.js';

@Module({})
export class MessagesModule {
  static register(eligibility?: ConversationEligibility): DynamicModule {
    return {
      module: MessagesModule,
      imports: [ConversationsModule.register(eligibility), SecurityModule],
      controllers: [HistoryController],
      providers: [
        ConversationRepository,
        MessageEncryptionService,
        MessageRepository,
        MessageService,
      ],
      exports: [MessageService],
    };
  }
}
