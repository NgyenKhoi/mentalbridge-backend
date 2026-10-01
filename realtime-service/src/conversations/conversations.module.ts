import { Module, type DynamicModule, type Provider } from '@nestjs/common';

import {
  ConsultationConversationEligibility,
  type ConversationEligibility,
} from './conversation-eligibility.js';
import {
  ConsultationConversationEvidence,
  type ConversationEvidence,
} from './conversation-evidence.js';
import { ELIGIBILITY_TOKEN, EVIDENCE_TOKEN } from '../shared/tokens.js';

@Module({})
export class ConversationsModule {
  static register(
    eligibility?: ConversationEligibility,
    evidence?: ConversationEvidence,
  ): DynamicModule {
    const eligibilityProvider: Provider = eligibility
      ? { provide: ELIGIBILITY_TOKEN, useValue: eligibility }
      : { provide: ELIGIBILITY_TOKEN, useClass: ConsultationConversationEligibility };
    const evidenceProvider: Provider = evidence
      ? { provide: EVIDENCE_TOKEN, useValue: evidence }
      : { provide: EVIDENCE_TOKEN, useClass: ConsultationConversationEvidence };
    return {
      module: ConversationsModule,
      providers: [eligibilityProvider, evidenceProvider],
      exports: [ELIGIBILITY_TOKEN, EVIDENCE_TOKEN],
    };
  }
}
