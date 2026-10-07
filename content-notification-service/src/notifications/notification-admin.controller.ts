import { Controller, Get, Inject, UseGuards } from '@nestjs/common';

import { NOTIFICATION_SERVICE_TOKEN } from '../application.tokens.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import { RolesGuard } from '../auth/roles.guard.js';
import { Roles } from '../auth/roles.decorator.js';
import type { NotificationService } from './notification.service.js';
import type { NotificationOperationsSummary } from './notification.types.js';

@Controller('api/v1/admin/notifications')
@UseGuards(JwtAuthGuard, RolesGuard)
export class NotificationAdminController {
  constructor(
    @Inject(NOTIFICATION_SERVICE_TOKEN)
    private readonly service: NotificationService,
  ) {}

  @Get('summary')
  @Roles('ADMIN')
  async getSummary(): Promise<NotificationOperationsSummary> {
    return this.service.getOperationsSummary();
  }
}
