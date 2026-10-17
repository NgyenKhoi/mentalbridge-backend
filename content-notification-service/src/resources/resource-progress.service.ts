import { Inject, Injectable, ServiceUnavailableException } from '@nestjs/common';

import { RESOURCE_PROGRESS_REPOSITORY_TOKEN } from '../application.tokens.js';
import type { ResourceProgressRepository } from './resource-progress.repository.js';
import type {
  ResourceProgressItem,
  ResourceProgressList,
  ResourceProgressUpdate,
} from './resource-progress.types.js';

@Injectable()
export class ResourceProgressService {
  constructor(
    @Inject(RESOURCE_PROGRESS_REPOSITORY_TOKEN)
    private readonly repository: ResourceProgressRepository,
  ) {}

  async list(ownerId: string, from: string, to: string): Promise<ResourceProgressList> {
    try {
      return { items: await this.repository.list(ownerId, from, to) };
    } catch {
      throw new ServiceUnavailableException();
    }
  }

  async save(
    ownerId: string,
    resourceId: string,
    localDate: string,
    update: ResourceProgressUpdate,
  ): Promise<ResourceProgressItem | null> {
    try {
      return await this.repository.save(ownerId, resourceId, localDate, update);
    } catch {
      throw new ServiceUnavailableException();
    }
  }
}
