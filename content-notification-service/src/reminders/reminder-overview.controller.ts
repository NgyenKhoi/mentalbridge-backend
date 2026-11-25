import {
  Controller,
  Get,
  Headers,
  Inject,
  Query,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { Response } from 'express';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { Roles } from '../auth/roles.decorator.js';
import { RolesGuard } from '../auth/roles.guard.js';
import { ReminderOverviewService } from './reminder-overview.service.js';
import type { ReminderOverview } from './reminder-overview.types.js';

@Controller('api/v1/reminder-overview')
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles('USER')
export class ReminderOverviewController {
  constructor(@Inject(ReminderOverviewService) private readonly service: ReminderOverviewService) {}

  @Get()
  async get(
    @CurrentUser() user: AuthenticatedUser,
    @Query() query: Record<string, unknown>,
    @Headers('x-correlation-id') correlationId: string | undefined,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ReminderOverview> {
    response.setHeader('Cache-Control', 'private, no-store');
    if (Object.keys(query).length > 0)
      throw new UnprocessableEntityException({
        type: 'https://mentalbridge.io/errors/REMINDER_QUERY_INVALID',
        title: 'Query parameters are unsupported',
        status: 422,
        code: 'REMINDER_QUERY_INVALID',
      });
    return this.service.get(user.accountId, correlationId ?? randomUUID());
  }
}
