import {
  Body,
  Controller,
  Get,
  Inject,
  NotFoundException,
  Param,
  Put,
  Query,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { ZodError } from 'zod';

import { RESOURCE_PROGRESS_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import {
  ResourceProgressDateSchema,
  ResourceProgressResourceIdSchema,
  ResourceProgressUpdateSchema,
} from './resource-progress.dto.js';
import type { ResourceProgressService } from './resource-progress.service.js';
import type { ResourceProgressItem, ResourceProgressList } from './resource-progress.types.js';

function invalid(field: string, message: string): UnprocessableEntityException {
  return new UnprocessableEntityException({
    type: 'https://mentalbridge.io/errors/RESOURCE_PROGRESS_VALIDATION_FAILED',
    title: 'Resource progress is invalid',
    status: 422,
    code: 'RESOURCE_PROGRESS_VALIDATION_FAILED',
    fieldViolations: [{ field, message }],
  });
}

function localDate(value: string | undefined, field: string): string {
  const result = ResourceProgressDateSchema.safeParse(value);
  if (!result.success) throw invalid(field, 'must be a valid calendar date');
  return result.data;
}

@Controller('api/v1/resource-progress')
@UseGuards(JwtAuthGuard)
export class ResourceProgressController {
  constructor(
    @Inject(RESOURCE_PROGRESS_SERVICE_TOKEN)
    private readonly service: ResourceProgressService,
  ) {}

  @Get()
  async list(
    @CurrentUser() user: AuthenticatedUser,
    @Query('from') fromValue: string | undefined,
    @Query('to') toValue: string | undefined,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceProgressList> {
    const from = localDate(fromValue, 'from');
    const to = localDate(toValue, 'to');
    const dayCount = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
    if (dayCount < 0 || dayCount > 31)
      throw invalid('to', 'must be on or within 31 days after from');
    response.setHeader('Cache-Control', 'private, no-store');
    return this.service.list(user.accountId, from, to);
  }

  @Put(':resourceId/:localDate')
  async save(
    @CurrentUser() user: AuthenticatedUser,
    @Param('resourceId') resourceIdValue: string,
    @Param('localDate') localDateValue: string,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceProgressItem> {
    const resourceId = ResourceProgressResourceIdSchema.safeParse(resourceIdValue);
    if (!resourceId.success) throw invalid('resourceId', 'must be a valid UUID');
    const activityDate = localDate(localDateValue, 'localDate');
    let update;
    try {
      update = ResourceProgressUpdateSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          type: 'https://mentalbridge.io/errors/RESOURCE_PROGRESS_VALIDATION_FAILED',
          title: 'Resource progress is invalid',
          status: 422,
          code: 'RESOURCE_PROGRESS_VALIDATION_FAILED',
          fieldViolations: error.issues.slice(0, 32).map((issue) => ({
            field: issue.path.join('.') || 'body',
            message: issue.message.slice(0, 300),
          })),
        });
      }
      throw error;
    }
    const saved = await this.service.save(user.accountId, resourceId.data, activityDate, update);
    if (!saved) throw new NotFoundException('Resource not found');
    response.setHeader('Cache-Control', 'private, no-store');
    return saved;
  }
}
