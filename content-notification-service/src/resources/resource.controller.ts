import { BadRequestException, Controller, Get, Inject, Query } from '@nestjs/common';
import type { ResourceListResult, ResourceCategory } from './resource.types.js';
import { RESOURCE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ResourceService } from './resource.service.js';

const UUID_RE = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;
const VALID_CATEGORIES = new Set<ResourceCategory>([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

@Controller('api/v1/resources')
export class ResourceController {
  constructor(
    @Inject(RESOURCE_SERVICE_TOKEN)
    private readonly resourceService: ResourceService,
  ) {}

  @Get()
  async listResources(
    @Query('locale') locale?: string,
    @Query('category') category?: string,
    @Query('limit') limitParam?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ResourceListResult> {
    if (category !== undefined && !VALID_CATEGORIES.has(category as ResourceCategory)) {
      throw new BadRequestException(`Invalid category: ${category}`);
    }

    let limit: number | undefined;
    if (limitParam !== undefined) {
      if (!/^\d+$/.test(limitParam)) {
        throw new BadRequestException('limit must be an integer between 1 and 100');
      }
      const parsed = parseInt(limitParam, 10);
      if (parsed < 1 || parsed > 100) {
        throw new BadRequestException('limit must be an integer between 1 and 100');
      }
      limit = parsed;
    }

    if (cursor !== undefined && !UUID_RE.test(cursor)) {
      throw new BadRequestException('cursor must be a valid UUID');
    }

    return this.resourceService.listPublished({
      locale: locale ?? undefined,
      category: category as ResourceCategory | undefined,
      limit,
      cursor: cursor ?? undefined,
    });
  }
}
