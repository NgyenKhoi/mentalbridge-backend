import {
  Controller,
  ForbiddenException,
  Get,
  Inject,
  NotFoundException,
  Param,
  ServiceUnavailableException,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';

import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { Roles } from '../auth/roles.decorator.js';
import { RolesGuard } from '../auth/roles.guard.js';
import { NotificationIdSchema } from '../notifications/notification.dto.js';
import { SpecialistNotificationLiveRepository } from './specialist-notification-live.repository.js';

@Controller('internal/v1/notifications')
@UseGuards(JwtAuthGuard, RolesGuard)
@Roles('SPECIALIST')
export class SpecialistNotificationLiveController {
  constructor(
    @Inject(SpecialistNotificationLiveRepository)
    private readonly repository: SpecialistNotificationLiveRepository,
  ) {}

  @Get(':notificationId/live-eligibility')
  async eligible(
    @Param('notificationId') notificationId: string,
    @CurrentUser() user: AuthenticatedUser,
  ): Promise<Readonly<{ notificationId: string }>> {
    if (user.roles.length !== 1 || user.roles[0] !== 'SPECIALIST') throw new ForbiddenException();
    if (!NotificationIdSchema.safeParse(notificationId).success)
      throw new UnprocessableEntityException({
        type: 'https://mentalbridge.io/errors/NOTIFICATION_ID_INVALID',
        title: 'Notification identifier is invalid',
        status: 422,
        code: 'NOTIFICATION_ID_INVALID',
      });
    let eligibleId;
    try {
      eligibleId = await this.repository.eligible(notificationId, user.accountId);
    } catch {
      throw new ServiceUnavailableException();
    }
    if (!eligibleId)
      throw new NotFoundException({
        type: 'https://mentalbridge.io/errors/NOTIFICATION_NOT_FOUND',
        title: 'Notification not found',
        status: 404,
        code: 'NOTIFICATION_NOT_FOUND',
      });
    return { notificationId: eligibleId };
  }
}
