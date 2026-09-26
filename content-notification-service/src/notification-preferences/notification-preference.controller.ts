import {
  Body,
  Controller,
  Get,
  Headers,
  HttpException,
  HttpStatus,
  Inject,
  Patch,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { ZodError } from 'zod';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { NOTIFICATION_PREFERENCE_SERVICE_TOKEN } from '../application.tokens.js';
import { NotificationPreferencePatchSchema } from './notification-preference.dto.js';
import { NotificationPreferenceVersionMismatchError } from './notification-preference.repository.js';
import {
  InvalidNotificationPreferenceError,
  type NotificationPreferenceService,
} from './notification-preference.service.js';
import type { NotificationPreferences } from './notification-preference.types.js';

const ETAG_PATTERN = /^"(0|[1-9]\d*)"$/;

function problem(status: number, code: string, title: string): Record<string, unknown> {
  return { type: `https://mentalbridge.io/errors/${code}`, title, status, code };
}

function setHeaders(response: Response, preferences: NotificationPreferences): void {
  response.setHeader('ETag', `"${String(preferences.version)}"`);
  response.setHeader('Cache-Control', 'private, no-store');
}

@Controller('api/v1/notification-preferences')
@UseGuards(JwtAuthGuard)
export class NotificationPreferenceController {
  constructor(
    @Inject(NOTIFICATION_PREFERENCE_SERVICE_TOKEN)
    private readonly service: NotificationPreferenceService,
  ) {}

  @Get()
  async get(
    @CurrentUser() user: AuthenticatedUser,
    @Res({ passthrough: true }) response: Response,
  ): Promise<NotificationPreferences> {
    const preferences = await this.service.get(user.accountId);
    setHeaders(response, preferences);
    return preferences;
  }

  @Patch()
  async update(
    @CurrentUser() user: AuthenticatedUser,
    @Headers('if-match') ifMatch: string | undefined,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<NotificationPreferences> {
    if (!ifMatch) {
      throw new HttpException(
        problem(428, 'NOTIFICATION_PREFERENCE_VERSION_REQUIRED', 'If-Match is required'),
        428,
      );
    }
    const match = ETAG_PATTERN.exec(ifMatch);
    if (!match) {
      throw new HttpException(
        problem(400, 'INVALID_IF_MATCH', 'If-Match must be a quoted non-negative integer'),
        HttpStatus.BAD_REQUEST,
      );
    }

    let patch;
    try {
      patch = NotificationPreferencePatchSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          ...problem(422, 'NOTIFICATION_PREFERENCE_VALIDATION_FAILED', 'Preferences are invalid'),
          fieldViolations: error.issues.slice(0, 32).map((issue) => ({
            field: issue.path.join('.') || 'body',
            message: issue.message.slice(0, 300),
          })),
        });
      }
      throw error;
    }

    try {
      const preferences = await this.service.update(user.accountId, Number(match[1]), patch);
      setHeaders(response, preferences);
      return preferences;
    } catch (error) {
      if (error instanceof InvalidNotificationPreferenceError) {
        throw new UnprocessableEntityException({
          ...problem(422, 'NOTIFICATION_PREFERENCE_VALIDATION_FAILED', 'Preferences are invalid'),
          fieldViolations: [{ field: error.field, message: error.message }],
        });
      }
      if (error instanceof NotificationPreferenceVersionMismatchError) {
        throw new HttpException(
          problem(412, 'NOTIFICATION_PREFERENCE_VERSION_MISMATCH', 'Preferences changed elsewhere'),
          HttpStatus.PRECONDITION_FAILED,
        );
      }
      throw error;
    }
  }
}
