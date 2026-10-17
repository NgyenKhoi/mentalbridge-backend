import {
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Inject,
  Param,
  Patch,
  Post,
  Query,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';

import { NOTIFICATION_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import { NotificationIdSchema } from './notification.dto.js';
import type { NotificationService } from './notification.service.js';
import type { NotificationItem, NotificationPage } from './notification.types.js';

function id(value: string): string {
  if (NotificationIdSchema.safeParse(value).success) return value;
  throw new UnprocessableEntityException({
    type: 'https://mentalbridge.io/errors/NOTIFICATION_ID_INVALID',
    title: 'Notification identifier is invalid',
    status: 422,
    code: 'NOTIFICATION_ID_INVALID',
  });
}

@Controller('api/v1/notifications')
@UseGuards(JwtAuthGuard)
export class NotificationController {
  constructor(
    @Inject(NOTIFICATION_SERVICE_TOKEN)
    private readonly service: NotificationService,
  ) {}

  @Get()
  async list(
    @CurrentUser() user: AuthenticatedUser,
    @Query('limit') limit: string | undefined,
    @Query('cursor') cursor: string | undefined,
    @Res({ passthrough: true }) response: Response,
  ): Promise<NotificationPage> {
    response.setHeader('Cache-Control', 'private, no-store');
    return this.service.list(user.accountId, limit, cursor);
  }

  @Patch(':id/read')
  async markRead(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id') notificationId: string,
    @Res({ passthrough: true }) response: Response,
  ): Promise<NotificationItem> {
    response.setHeader('Cache-Control', 'private, no-store');
    return this.service.markRead(user.accountId, id(notificationId));
  }

  @Post('mark-all-read')
  @HttpCode(HttpStatus.OK)
  async markAllRead(
    @CurrentUser() user: AuthenticatedUser,
  ): Promise<Readonly<{ updatedCount: number }>> {
    return this.service.markAllRead(user.accountId);
  }

  @Delete(':id')
  @HttpCode(HttpStatus.NO_CONTENT)
  async delete(
    @CurrentUser() user: AuthenticatedUser,
    @Param('id') notificationId: string,
  ): Promise<void> {
    return this.service.delete(user.accountId, id(notificationId));
  }
}
