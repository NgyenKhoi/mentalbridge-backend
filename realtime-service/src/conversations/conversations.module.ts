import { Module, type DynamicModule, type Provider } from '@nestjs/common';

import {
  ConsultationConversationEligibility,
  type ConversationEligibility,
} from './conversation-eligibility.js';
import { ELIGIBILITY_TOKEN } from '../shared/tokens.js';

@Module({})
export class ConversationsModule {
  static register(eligibility?: ConversationEligibility): DynamicModule {
    const eligibilityProvider: Provider = eligibility
      ? { provide: ELIGIBILITY_TOKEN, useValue: eligibility }
      : { provide: ELIGIBILITY_TOKEN, useClass: ConsultationConversationEligibility };
    return {
      module: ConversationsModule,
      providers: [eligibilityProvider],
      exports: [ELIGIBILITY_TOKEN],
    };
  }
}
