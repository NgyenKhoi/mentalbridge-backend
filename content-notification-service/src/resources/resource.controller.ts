import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Inject,
  NotFoundException,
  Param,
  Patch,
  Post,
  Query,
  ConflictException,
  UnprocessableEntityException,
} from '@nestjs/common';
import type {
  ResourceListResult,
  ResourceCategory,
  ResourceDetail,
  ResourceSummary,
} from './resource.types.js';
import { RESOURCE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ResourceService } from './resource.service.js';
import {
  CreateResourceDtoSchema,
  UpdateResourceDtoSchema,
  PublishResourceDtoSchema,
  type CreateResourceDto,
  type UpdateResourceDto,
  type PublishResourceDto,
} from './resource.dto.js';
import { ZodError } from 'zod';

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

  @Get(':id')
  async getResource(@Param('id') id: string): Promise<ResourceDetail> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }
    const resource = await this.resourceService.getById(id);
    if (!resource) {
      throw new NotFoundException('Resource not found');
    }
    return resource;
  }

  @Post()
  @HttpCode(HttpStatus.CREATED)
  async createResource(@Body() body: unknown): Promise<ResourceSummary> {
    let dto: CreateResourceDto;
    try {
      dto = CreateResourceDtoSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
          title: 'Validation failed',
          status: 422,
          code: 'VALIDATION_ERROR',
          fieldViolations: error.issues.map((e) => ({
            field: e.path.join('.'),
            message: e.message,
          })),
        });
      }
      throw error;
    }

    const resource = await this.resourceService.create({
      category: dto.category,
      locale: dto.locale,
      title: dto.title,
      summary: dto.summary,
      contentBody: dto.contentBody ?? null,
      externalUrl: dto.externalUrl ?? null,
    });

    return {
      id: resource.id,
      category: resource.category,
      locale: resource.locale,
      title: resource.title,
      summary: resource.summary,
      externalUrl: resource.externalUrl,
      status: resource.status,
      reviewedAt: resource.reviewedAt,
      createdAt: resource.createdAt,
      updatedAt: resource.updatedAt,
    };
  }

  @Patch(':id')
  async updateResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @Body() body: unknown,
  ): Promise<ResourceSummary> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    let dto: UpdateResourceDto;
    try {
      dto = UpdateResourceDtoSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
          title: 'Validation failed',
          status: 422,
          code: 'VALIDATION_ERROR',
          fieldViolations: error.issues.map((e) => ({
            field: e.path.join('.'),
            message: e.message,
          })),
        });
      }
      throw error;
    }

    const resource = await this.resourceService.update(id, {
      ...dto,
      version,
    });

    if (!resource) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/INVALID_STATE_TRANSITION',
        title: 'Cannot modify non-DRAFT resource or version mismatch',
        status: 409,
        code: 'INVALID_STATE_TRANSITION',
      });
    }

    return {
      id: resource.id,
      category: resource.category,
      locale: resource.locale,
      title: resource.title,
      summary: resource.summary,
      externalUrl: resource.externalUrl,
      status: resource.status,
      reviewedAt: resource.reviewedAt,
      createdAt: resource.createdAt,
      updatedAt: resource.updatedAt,
    };
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteResource(@Param('id') id: string): Promise<void> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const deleted = await this.resourceService.delete(id);
    if (!deleted) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/INVALID_STATE_TRANSITION',
        title: 'Cannot delete non-DRAFT resource',
        status: 409,
        code: 'INVALID_STATE_TRANSITION',
      });
    }
  }

  @Post(':id/publish')
  async publishResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @Body() body: unknown,
  ): Promise<ResourceSummary> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    let dto: PublishResourceDto;
    try {
      dto = PublishResourceDtoSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
          title: 'Validation failed',
          status: 422,
          code: 'VALIDATION_ERROR',
          fieldViolations: error.issues.map((e) => ({
            field: e.path.join('.'),
            message: e.message,
          })),
        });
      }
      throw error;
    }

    // TODO: Extract reviewedBy from JWT token when authentication is implemented
    const reviewedBy = '00000000-0000-0000-0000-000000000000'; // Placeholder

    const resource = await this.resourceService.publish(id, {
      reviewedBy,
      version,
      effectiveAt: dto.effectiveAt ?? null,
      expiresAt: dto.expiresAt ?? null,
    });

    if (!resource) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/INVALID_STATE_TRANSITION',
        title: 'Cannot publish non-DRAFT resource or version mismatch',
        status: 409,
        code: 'INVALID_STATE_TRANSITION',
      });
    }

    return {
      id: resource.id,
      category: resource.category,
      locale: resource.locale,
      title: resource.title,
      summary: resource.summary,
      externalUrl: resource.externalUrl,
      status: resource.status,
      reviewedAt: resource.reviewedAt,
      createdAt: resource.createdAt,
      updatedAt: resource.updatedAt,
    };
  }

  @Post(':id/archive')
  async archiveResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
  ): Promise<ResourceSummary> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    const resource = await this.resourceService.archive(id, version);

    if (!resource) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/INVALID_STATE_TRANSITION',
        title: 'Cannot archive non-PUBLISHED resource or version mismatch',
        status: 409,
        code: 'INVALID_STATE_TRANSITION',
      });
    }

    return {
      id: resource.id,
      category: resource.category,
      locale: resource.locale,
      title: resource.title,
      summary: resource.summary,
      externalUrl: resource.externalUrl,
      status: resource.status,
      reviewedAt: resource.reviewedAt,
      createdAt: resource.createdAt,
      updatedAt: resource.updatedAt,
    };
  }
}
