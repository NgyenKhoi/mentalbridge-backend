import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { RESOURCE_REWARD_REPOSITORY_TOKEN } from '../application.tokens.js';
import { calculateResourceRewards } from './resource-reward.policy.js';
import type { ResourceRewardRepository } from './resource-reward.repository.js';
import type { ResourceRewardReadModel, ResourceRewardVisibility } from './resource-reward.types.js';

@Injectable()
export class ResourceRewardService {
  constructor(
    @Inject(RESOURCE_REWARD_REPOSITORY_TOKEN)
    private readonly repository: ResourceRewardRepository,
  ) {}

  async read(ownerId: string): Promise<ResourceRewardReadModel> {
    try {
      const source = await this.repository.read(ownerId);
      const calculated = calculateResourceRewards(source.facts, ownerId);
      return {
        totalXp: calculated.totalXp,
        level: calculated.level,
        nextLevelXp: calculated.nextLevelXp,
        visibilityState: source.preference.visibilityState,
        preferenceVersion: source.preference.version,
        policyVersion: calculated.policyVersion,
        sourceAvailability: 'AVAILABLE',
        calculatedAt: new Date().toISOString(),
        recentGrants: calculated.grants.slice(0, 10),
      };
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async setVisibility(
    ownerId: string,
    visibilityState: ResourceRewardVisibility,
  ): Promise<ResourceRewardReadModel> {
    try {
      await this.repository.setVisibility(ownerId, visibilityState);
    } catch {
      throw new ServiceUnavailableException();
    }
    return this.read(ownerId);
  }
}
