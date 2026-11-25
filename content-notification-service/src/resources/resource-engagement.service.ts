import { ConflictException, Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { RESOURCE_ENGAGEMENT_REPOSITORY_TOKEN } from '../application.tokens.js';
import {
  ResourceBingoVersionMismatchError,
  ResourceBingoItemRetiredError,
  type ResourceEngagementRepository,
} from './resource-engagement.repository.js';
import type {
  ResourceBingoBoardState,
  ResourceBookmark,
  ResourceBookmarkList,
  ResourceBingoPlanSync,
  ResourceCompletionEvidence,
} from './resource-engagement.types.js';
import { CareResourceActionsClient } from './care-resource-actions.client.js';
import { localDateInTimeZone, mondayOf } from './resource-journey.planner.js';

@Injectable()
export class ResourceEngagementService {
  constructor(
    @Inject(RESOURCE_ENGAGEMENT_REPOSITORY_TOKEN)
    private readonly repository: ResourceEngagementRepository,
    @Inject(CareResourceActionsClient) private readonly care: CareResourceActionsClient,
  ) {}

  async listBookmarks(ownerId: string): Promise<ResourceBookmarkList> {
    try {
      return { items: await this.repository.listBookmarks(ownerId) };
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async saveBookmark(ownerId: string, resourceId: string): Promise<ResourceBookmark | null> {
    try {
      return await this.repository.saveBookmark(ownerId, resourceId);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async deleteBookmark(ownerId: string, resourceId: string): Promise<void> {
    try {
      await this.repository.deleteBookmark(ownerId, resourceId);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async readBingo(ownerId: string, weekStart: string): Promise<ResourceBingoBoardState | null> {
    try {
      return await this.repository.readBingo(ownerId, weekStart);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async syncPlanBingo(
    ownerId: string,
    weekStart: string,
    timeZone: string,
    authorization: string,
    correlationId?: string,
  ): Promise<ResourceBingoPlanSync> {
    const localDate = localDateInTimeZone(new Date().toISOString(), timeZone);
    if (mondayOf(localDate) !== weekStart) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/RESOURCE_BINGO_WEEK_NOT_CURRENT',
        title: 'Only the current local week can be synced',
        status: 409,
        code: 'RESOURCE_BINGO_WEEK_NOT_CURRENT',
      });
    }
    const projection = await this.care.get(authorization, localDate, correlationId);
    try {
      return await this.repository.syncPlanBingo(ownerId, weekStart, timeZone, projection);
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async completionEvidence(
    ownerId: string,
    resourceId: string,
    resourceVersion: string,
    localDate: string,
    timeZone: string,
    excludedFactIds: readonly string[] = [],
  ): Promise<ResourceCompletionEvidence | null> {
    try {
      return await this.repository.completionEvidence(
        ownerId,
        resourceId,
        resourceVersion,
        localDate,
        timeZone,
        excludedFactIds,
      );
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async setBingoSelfConfirmed(
    ownerId: string,
    weekStart: string,
    position: number,
    expectedVersion: string,
    selfConfirmed: boolean,
  ): Promise<ResourceBingoBoardState | null> {
    try {
      return await this.repository.setBingoSelfConfirmed(
        ownerId,
        weekStart,
        position,
        expectedVersion,
        selfConfirmed,
      );
    } catch (error) {
      if (error instanceof ResourceBingoVersionMismatchError) throw error;
      if (error instanceof ResourceBingoItemRetiredError) throw error;
      throw new ServiceUnavailableException();
    }
  }
}
