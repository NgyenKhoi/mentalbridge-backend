import {
  Body,
  Controller,
  Get,
  Inject,
  Put,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';

import { RESOURCE_REWARD_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { ResourceRewardVisibilityUpdateSchema } from './resource-reward.dto.js';
import type { ResourceRewardService } from './resource-reward.service.js';
import type { ResourceRewardReadModel } from './resource-reward.types.js';

@Controller('api/v1/resource-rewards')
@UseGuards(JwtAuthGuard)
export class ResourceRewardController {
  constructor(
    @Inject(RESOURCE_REWARD_SERVICE_TOKEN)
    private readonly service: ResourceRewardService,
  ) {}

  @Get()
  async read(
    @CurrentUser() user: AuthenticatedUser,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceRewardReadModel> {
    response.setHeader('Cache-Control', 'private, no-store');
    return this.service.read(user.accountId);
  }

  @Put()
  async setVisibility(
    @CurrentUser() user: AuthenticatedUser,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceRewardReadModel> {
    const parsed = ResourceRewardVisibilityUpdateSchema.safeParse(body);
    if (!parsed.success) {
      throw new UnprocessableEntityException({
        type: 'https://mentalbridge.io/errors/RESOURCE_REWARD_VALIDATION_FAILED',
        title: 'Resource reward preference is invalid',
        status: 422,
        code: 'RESOURCE_REWARD_VALIDATION_FAILED',
        fieldViolations: parsed.error.issues.slice(0, 32).map((issue) => ({
          field: issue.path.join('.') || 'body',
          message: issue.message.slice(0, 300),
        })),
      });
    }
    response.setHeader('Cache-Control', 'private, no-store');
    return this.service.setVisibility(user.accountId, parsed.data.visibilityState);
  }
}
