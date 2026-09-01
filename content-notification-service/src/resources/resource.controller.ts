/**
 * MB-197 / MB-198: Content resource BFF endpoint.
 *
 * - Anonymous and authenticated callers may list published resources without
 *   exposing service credentials (no upstream token forwarded in this BFF layer).
 * - Only published resources returned by the repository are rendered.
 * - Fallback (empty / unavailable) states are explicit — the UI must not invent content.
 * - No hotline endpoint or data model is recreated (ADR 0009).
 */

import { Controller, Get, Query } from '@nestjs/common';
import type { ResourceListResult, ResourceCategory } from './resource.types.js';
import type { ResourceService } from './resource.service.js';
import { Inject } from '@nestjs/common';
import { RESOURCE_SERVICE_TOKEN } from '../application.tokens.js';

@Controller('api/v1/resources')
export class ResourceController {
  constructor(
    @Inject(RESOURCE_SERVICE_TOKEN)
    private readonly resourceService: ResourceService,
  ) {}

  /**
   * MB-198: List only published resources relevant to a result context.
   * Anonymous and authenticated result pages can call this without service credentials.
   */
  @Get()
  async listResources(
    @Query('locale') locale?: string,
    @Query('category') category?: string,
    @Query('limit') limit?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ResourceListResult> {
    return this.resourceService.listPublished({
      locale: locale ?? undefined,
      category: category as ResourceCategory | undefined,
      limit: limit ? parseInt(limit, 10) : undefined,
      cursor: cursor ?? undefined,
    });
  }
}
