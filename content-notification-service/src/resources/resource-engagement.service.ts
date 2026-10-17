import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { RESOURCE_ENGAGEMENT_REPOSITORY_TOKEN } from '../application.tokens.js';
import {
  ResourceBingoVersionMismatchError,
  type ResourceEngagementRepository,
} from './resource-engagement.repository.js';
import type {
  ResourceBingoBoardState,
  ResourceBookmark,
  ResourceBookmarkList,
} from './resource-engagement.types.js';

@Injectable()
export class ResourceEngagementService {
  constructor(
    @Inject(RESOURCE_ENGAGEMENT_REPOSITORY_TOKEN)
    private readonly repository: ResourceEngagementRepository,
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
      throw new ServiceUnavailableException();
    }
  }
}
