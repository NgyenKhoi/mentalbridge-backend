import { HttpStatus, Injectable } from '@nestjs/common';

import { ApplicationException } from '../http/application.exception.js';

export type ConversationOperation = 'subscribe' | 'send' | 'history';

export interface ConversationEligibility {
  assertEligible(
    accountId: string,
    conversationId: string,
    operation: ConversationOperation,
  ): Promise<void>;
}

@Injectable()
export class UnavailableConversationEligibility implements ConversationEligibility {
  assertEligible(): Promise<void> {
    throw new ApplicationException(
      HttpStatus.SERVICE_UNAVAILABLE,
      'CHAT_ELIGIBILITY_UNAVAILABLE',
      'Conversation eligibility is unavailable',
      true,
    );
  }
}
