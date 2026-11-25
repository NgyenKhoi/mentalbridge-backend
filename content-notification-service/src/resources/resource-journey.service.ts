import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { RESOURCE_JOURNEY_REPOSITORY_TOKEN } from '../application.tokens.js';
import type { ResourceJourneyRepository } from './resource-journey.repository.js';
import type { ResourceJourney, ResourceJourneyInput } from './resource-journey.types.js';

@Injectable()
export class ResourceJourneyService {
  constructor(
    @Inject(RESOURCE_JOURNEY_REPOSITORY_TOKEN)
    private readonly repository: ResourceJourneyRepository,
  ) {}

  async materialize(
    ownerId: string,
    localDate: string,
    input: ResourceJourneyInput,
  ): Promise<ResourceJourney | null> {
    try {
      return await this.repository.materialize(ownerId, localDate, input);
    } catch {
      throw new ServiceUnavailableException();
    }
  }
}
