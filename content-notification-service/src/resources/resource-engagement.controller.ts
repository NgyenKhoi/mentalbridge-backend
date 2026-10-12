import {
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  HttpException,
  HttpStatus,
  Inject,
  NotFoundException,
  Param,
  Put,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { ZodError } from 'zod';

import { RESOURCE_ENGAGEMENT_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import {
  ResourceBingoPositionSchema,
  ResourceBingoSelfConfirmationSchema,
  ResourceBingoWeekSchema,
} from './resource-engagement.dto.js';
import { ResourceBingoVersionMismatchError } from './resource-engagement.repository.js';
import type { ResourceEngagementService } from './resource-engagement.service.js';
import type {
  ResourceBingoBoardState,
  ResourceBookmark,
  ResourceBookmarkList,
} from './resource-engagement.types.js';
import { ResourceIdSchema } from './resource.dto.js';

const ETAG_PATTERN = /^"(0|[1-9]\d*)"$/;

function problem(status: number, code: string, title: string): Record<string, unknown> {
  return { type: `https://mentalbridge.io/errors/${code}`, title, status, code };
}

function invalid(error: ZodError): UnprocessableEntityException {
  return new UnprocessableEntityException({
    ...problem(422, 'RESOURCE_ENGAGEMENT_VALIDATION_FAILED', 'Resource engagement is invalid'),
    fieldViolations: error.issues.slice(0, 32).map((issue) => ({
      field: issue.path.join('.') || 'request',
      message: issue.message.slice(0, 300),
    })),
  });
}

function noStore(response: Response): void {
  response.setHeader('Cache-Control', 'private, no-store');
}

@Controller('api/v1/resource-bookmarks')
@UseGuards(JwtAuthGuard)
export class ResourceBookmarkController {
  constructor(
    @Inject(RESOURCE_ENGAGEMENT_SERVICE_TOKEN)
    private readonly service: ResourceEngagementService,
  ) {}

  @Get()
  async list(
    @CurrentUser() user: AuthenticatedUser,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceBookmarkList> {
    const result = await this.service.listBookmarks(user.accountId);
    noStore(response);
    return result;
  }

  @Put(':resourceId')
  async save(
    @CurrentUser() user: AuthenticatedUser,
    @Param('resourceId') resourceIdValue: string,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceBookmark> {
    const resourceId = ResourceIdSchema.safeParse(resourceIdValue);
    if (!resourceId.success) throw invalid(resourceId.error);
    const result = await this.service.saveBookmark(user.accountId, resourceId.data);
    if (!result) throw new NotFoundException('Published reviewed resource not found');
    noStore(response);
    return result;
  }

  @Delete(':resourceId')
  @HttpCode(204)
  async remove(
    @CurrentUser() user: AuthenticatedUser,
    @Param('resourceId') resourceIdValue: string,
    @Res({ passthrough: true }) response: Response,
  ): Promise<void> {
    const resourceId = ResourceIdSchema.safeParse(resourceIdValue);
    if (!resourceId.success) throw invalid(resourceId.error);
    await this.service.deleteBookmark(user.accountId, resourceId.data);
    noStore(response);
  }
}

@Controller('api/v1/resource-bingo')
@UseGuards(JwtAuthGuard)
export class ResourceBingoController {
  constructor(
    @Inject(RESOURCE_ENGAGEMENT_SERVICE_TOKEN)
    private readonly service: ResourceEngagementService,
  ) {}

  @Get(':weekStart')
  async get(
    @CurrentUser() user: AuthenticatedUser,
    @Param('weekStart') weekStartValue: string,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceBingoBoardState> {
    const weekStart = ResourceBingoWeekSchema.safeParse(weekStartValue);
    if (!weekStart.success) throw invalid(weekStart.error);
    const result = await this.service.readBingo(user.accountId, weekStart.data);
    if (!result) throw new NotFoundException('Resource bingo board not found');
    response.setHeader('ETag', `"${result.version}"`);
    noStore(response);
    return result;
  }

  @Put(':weekStart/items/:position/self-confirmation')
  async setSelfConfirmation(
    @CurrentUser() user: AuthenticatedUser,
    @Param('weekStart') weekStartValue: string,
    @Param('position') positionValue: string,
    @Headers('if-match') ifMatch: string | undefined,
    @Body() bodyValue: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceBingoBoardState> {
    if (!ifMatch) {
      throw new HttpException(
        problem(428, 'RESOURCE_BINGO_VERSION_REQUIRED', 'If-Match is required'),
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
    const weekStart = ResourceBingoWeekSchema.safeParse(weekStartValue);
    const position = ResourceBingoPositionSchema.safeParse(positionValue);
    let body;
    try {
      if (!weekStart.success) throw weekStart.error;
      if (!position.success) throw position.error;
      body = ResourceBingoSelfConfirmationSchema.parse(bodyValue);
    } catch (error) {
      if (error instanceof ZodError) throw invalid(error);
      throw error;
    }
    try {
      const result = await this.service.setBingoSelfConfirmed(
        user.accountId,
        weekStart.data,
        position.data,
        match[1],
        body.selfConfirmed,
      );
      if (!result) throw new NotFoundException('Resource bingo item not found');
      response.setHeader('ETag', `"${result.version}"`);
      noStore(response);
      return result;
    } catch (error) {
      if (error instanceof ResourceBingoVersionMismatchError) {
        throw new HttpException(
          problem(412, 'RESOURCE_BINGO_VERSION_MISMATCH', 'Resource bingo changed elsewhere'),
          HttpStatus.PRECONDITION_FAILED,
        );
      }
      throw error;
    }
  }
}
