import { Controller, Get, Inject, Res, UseGuards } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { Response } from 'express';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import {
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  WELLBEING_DIGEST_SERVICE_TOKEN,
} from '../application.tokens.js';
import type { NotificationPreferenceService } from '../notification-preferences/notification-preference.service.js';
import type { WellbeingDigestService } from './wellbeing-digest.service.js';
import type { WellbeingDigestPreview } from './wellbeing-digest.types.js';

@Controller('api/v1/wellbeing-digest')
@UseGuards(JwtAuthGuard)
export class WellbeingDigestController {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly preferences: NotificationPreferenceService,
    @Inject(WELLBEING_DIGEST_SERVICE_TOKEN) private readonly digest: WellbeingDigestService,
  ) {}

  @Get('preview')
  async preview(
    @CurrentUser() user: AuthenticatedUser,
    @Res({ passthrough: true }) response: Response,
  ): Promise<WellbeingDigestPreview> {
    const preferences = await this.preferences.get(user.accountId);
    response.setHeader('Cache-Control', 'private, no-store');
    return this.digest.preview(user.accountId, preferences, randomUUID());
  }
}
