import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  HttpStatus,
  Inject,
  NotFoundException,
  Param,
  Patch,
  Post,
  Query,
  Req,
  ConflictException,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type {
  ResourceListResult,
  ResourceCategory,
  ResourceDetail,
  PublicResourceDetail,
  ResourceSummary,
} from './resource.types.js';
import { RESOURCE_SERVICE_TOKEN } from '../application.tokens.js';
import type { ResourceService } from './resource.service.js';
import { Public } from '../auth/public.decorator.js';
import {
  CreateResourceDtoSchema,
  UpdateResourceDtoSchema,
  ResourceLocaleSchema,
  type CreateResourceDto,
  type UpdateResourceDto,
} from './resource.dto.js';
import { ZodError } from 'zod';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import { Roles } from '../auth/roles.decorator.js';
import { RolesGuard } from '../auth/roles.guard.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import type { Request } from 'express';

const UUID_RE = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;
const VALID_CATEGORIES = new Set<ResourceCategory>([
  'BREATHING',
  'MEDITATION',
  'ARTICLE',
  'VIDEO',
  'JOURNALING',
  'COMMUNITY',
]);

function requestCorrelationId(request: Request): string {
  return String(request.headers['x-correlation-id']);
}

@Controller('api/v1/resources')
export class ResourceController {
  constructor(
    @Inject(RESOURCE_SERVICE_TOKEN)
    private readonly resourceService: ResourceService,
  ) {}

  @Get()
  @Public()
  async listResources(
    @Query('locale') locale?: string,
    @Query('category') category?: string,
    @Query('limit') limitParam?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ResourceListResult> {
    const parsedLocale = locale === undefined ? undefined : ResourceLocaleSchema.safeParse(locale);
    if (parsedLocale !== undefined && !parsedLocale.success) {
      throw new BadRequestException('locale must be a valid BCP 47 tag');
    }
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
      locale: parsedLocale?.data,
      category: category as ResourceCategory | undefined,
      limit,
      cursor: cursor ?? undefined,
    });
  }

  @Get('admin/list')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async listAdminResources(
    @Query('locale') locale?: string,
    @Query('category') category?: string,
    @Query('status') status?: string,
    @Query('limit') limitParam?: string,
    @Query('cursor') cursor?: string,
  ): Promise<ResourceListResult> {
    const parsedLocale = locale === undefined ? undefined : ResourceLocaleSchema.safeParse(locale);
    if (parsedLocale !== undefined && !parsedLocale.success) {
      throw new BadRequestException('locale must be a valid BCP 47 tag');
    }
    if (category !== undefined && !VALID_CATEGORIES.has(category as ResourceCategory)) {
      throw new BadRequestException(`Invalid category: ${category}`);
    }

    if (status !== undefined && !['DRAFT', 'PUBLISHED', 'ARCHIVED'].includes(status)) {
      throw new BadRequestException(`Invalid status: ${status}`);
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

    return this.resourceService.listAdmin({
      locale: parsedLocale?.data,
      category: category as ResourceCategory | undefined,
      status: status as 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | undefined,
      limit,
      cursor: cursor ?? undefined,
    });
  }

  @Get('admin/:id')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async getAdminResource(@Param('id') id: string): Promise<ResourceDetail> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }
    const resource = await this.resourceService.getAdminById(id);
    if (!resource) {
      throw new NotFoundException('Resource not found');
    }
    return resource;
  }

  @Get(':id')
  @Public()
  async getResource(
    @Param('id') id: string,
    @Query('locale') locale = 'vi-VN',
  ): Promise<PublicResourceDetail> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }
    const parsedLocale = ResourceLocaleSchema.safeParse(locale);
    if (!parsedLocale.success) {
      throw new BadRequestException('locale must be a valid BCP 47 tag');
    }
    const resource = await this.resourceService.getPublishedById(id, parsedLocale.data);
    if (!resource) {
      throw new NotFoundException('Resource not found');
    }
    return resource;
  }

  @Post()
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  @HttpCode(HttpStatus.CREATED)
  async createResource(
    @Body() body: unknown,
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<ResourceSummary> {
    if (!idempotencyKey || !/^[A-Za-z0-9_-]{1,128}$/.test(idempotencyKey)) {
      throw new BadRequestException('Idempotency-Key header is required and must be opaque ASCII');
    }

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

    const resource = await this.resourceService.create(
      {
        category: dto.category,
        locale: dto.locale,
        title: dto.title,
        summary: dto.summary,
        contentBody: dto.contentBody ?? null,
        externalUrl: dto.externalUrl ?? null,
        effectiveAt: dto.effectiveAt ?? null,
        expiresAt: dto.expiresAt ?? null,
      },
      idempotencyKey,
      { actorId: user.accountId, correlationId: requestCorrelationId(request) },
    );

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
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async updateResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @Body() body: unknown,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
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

    const resource = await this.resourceService.update(
      id,
      {
        ...dto,
        version,
      },
      { actorId: user.accountId, correlationId: requestCorrelationId(request) },
    );

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
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<void> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    const deleted = await this.resourceService.delete(id, version, {
      actorId: user.accountId,
      correlationId: requestCorrelationId(request),
    });
    if (!deleted) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/INVALID_STATE_TRANSITION',
        title: 'Cannot delete non-DRAFT resource or version mismatch',
        status: 409,
        code: 'INVALID_STATE_TRANSITION',
      });
    }
  }

  @Post(':id/publish')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async publishResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<never> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    await this.resourceService.auditPublishBlocked(id, version, {
      actorId: user.accountId,
      correlationId: requestCorrelationId(request),
    });

    throw new ConflictException({
      type: 'https://mentalbridge.io/errors/REVIEW_APPROVAL_REQUIRED',
      title: 'An approved review decision is required before publication',
      status: 409,
      code: 'REVIEW_APPROVAL_REQUIRED',
    });
  }

  @Post(':id/archive')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async archiveResource(
    @Param('id') id: string,
    @Query('version') versionParam: string,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<ResourceSummary> {
    if (!UUID_RE.test(id)) {
      throw new BadRequestException('Invalid resource ID');
    }

    const version = parseInt(versionParam, 10);
    if (isNaN(version) || version < 0) {
      throw new BadRequestException('Valid version query parameter is required');
    }

    const resource = await this.resourceService.archive(id, version, {
      actorId: user.accountId,
      correlationId: requestCorrelationId(request),
    });

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
