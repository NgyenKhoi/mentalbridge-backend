import {
  Body,
  Controller,
  Get,
  Headers,
  HttpException,
  HttpStatus,
  Inject,
  Put,
  Query,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';

import { ACHIEVEMENT_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { AchievementPreferenceUpdateSchema, AchievementTimezoneSchema } from './achievement.dto.js';
import { AchievementPreferenceVersionMismatchError } from './achievement.repository.js';
import type { AchievementService } from './achievement.service.js';
import type { AchievementCollection } from './achievement.types.js';

const ETAG_PATTERN = /^"(0|[1-9]\d*)"$/;

function problem(status: number, code: string, title: string): Record<string, unknown> {
  return { type: `https://mentalbridge.io/errors/${code}`, title, status, code };
}

function noStore(response: Response, version: string): void {
  response.setHeader('Cache-Control', 'private, no-store');
  response.setHeader('ETag', `"${version}"`);
}

@Controller('api/v1/achievements')
@UseGuards(JwtAuthGuard)
export class AchievementController {
  constructor(
    @Inject(ACHIEVEMENT_SERVICE_TOKEN)
    private readonly service: AchievementService,
  ) {}

  @Get()
  async read(
    @CurrentUser() user: AuthenticatedUser,
    @Query('timezone') timezoneValue: string,
    @Res({ passthrough: true }) response: Response,
  ): Promise<AchievementCollection> {
    const timezone = AchievementTimezoneSchema.safeParse(timezoneValue);
    if (!timezone.success) {
      throw new UnprocessableEntityException(
        problem(422, 'ACHIEVEMENT_VALIDATION_FAILED', 'Achievement request is invalid'),
      );
    }
    const result = await this.service.read(user.accountId, timezone.data);
    noStore(response, result.preferenceVersion);
    return result;
  }

  @Put('preference')
  async setVisibility(
    @CurrentUser() user: AuthenticatedUser,
    @Query('timezone') timezoneValue: string,
    @Headers('if-match') ifMatch: string | undefined,
    @Body() bodyValue: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<AchievementCollection> {
    if (!ifMatch) {
      throw new HttpException(
        problem(428, 'ACHIEVEMENT_VERSION_REQUIRED', 'If-Match is required'),
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
    const timezone = AchievementTimezoneSchema.safeParse(timezoneValue);
    const body = AchievementPreferenceUpdateSchema.safeParse(bodyValue);
    if (!timezone.success || !body.success) {
      throw new UnprocessableEntityException(
        problem(422, 'ACHIEVEMENT_VALIDATION_FAILED', 'Achievement preference is invalid'),
      );
    }
    try {
      const result = await this.service.setVisibility(
        user.accountId,
        timezone.data,
        body.data.visibilityState,
        match[1],
      );
      noStore(response, result.preferenceVersion);
      return result;
    } catch (error) {
      if (error instanceof AchievementPreferenceVersionMismatchError) {
        throw new HttpException(
          problem(412, 'ACHIEVEMENT_VERSION_MISMATCH', 'Achievement preference changed elsewhere'),
          HttpStatus.PRECONDITION_FAILED,
        );
      }
      throw error;
    }
  }
}
