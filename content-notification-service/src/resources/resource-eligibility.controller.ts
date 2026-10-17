import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  HttpStatus,
  Inject,
  Param,
  Post,
  Req,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Request } from 'express';
import { ZodError, type ZodType } from 'zod';

import { RESOURCE_ELIGIBILITY_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import { Roles } from '../auth/roles.decorator.js';
import { RolesGuard } from '../auth/roles.guard.js';
import type {
  PublishResourceEligibilityRequest,
  ResourceEligibilityBatchRequest,
  ResourceEligibilityBatchResponse,
  ResourceEligibilityPublication,
  WithdrawResourceEligibilityRequest,
} from '../generated/resource-eligibility.contract.js';
import {
  PublishResourceEligibilitySchema,
  ResourceEligibilityBatchSchema,
  WithdrawResourceEligibilitySchema,
} from './resource-eligibility.dto.js';
import { ResourceIdSchema } from './resource.dto.js';
import type { ResourceEligibilityService } from './resource-eligibility.service.js';

const CONTENT_VERSION_RE = /^(0|[1-9][0-9]{0,18})$/;
const IDEMPOTENCY_KEY_RE = /^[A-Za-z0-9_-]{1,128}$/;

@Controller()
export class ResourceEligibilityController {
  constructor(
    @Inject(RESOURCE_ELIGIBILITY_SERVICE_TOKEN)
    private readonly service: ResourceEligibilityService,
  ) {}

  @Post('api/v1/resources/:id/versions/:contentVersion/eligibility-publications')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  @HttpCode(HttpStatus.CREATED)
  async publish(
    @Param('id') resourceId: string,
    @Param('contentVersion') contentVersion: string,
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @Body() body: unknown,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<ResourceEligibilityPublication> {
    validatePath(resourceId, contentVersion);
    const key = validateIdempotencyKey(idempotencyKey);
    const command = parseBody<PublishResourceEligibilityRequest>(
      PublishResourceEligibilitySchema,
      body,
    );
    return this.service.publish(resourceId, contentVersion, command, key, {
      actorId: user.accountId,
      correlationId: correlationId(request),
    });
  }

  @Post('api/v1/resources/:id/versions/:contentVersion/eligibility-publications/withdrawal')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  @HttpCode(HttpStatus.OK)
  async withdraw(
    @Param('id') resourceId: string,
    @Param('contentVersion') contentVersion: string,
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @Body() body: unknown,
    @CurrentUser() user: AuthenticatedUser,
    @Req() request: Request,
  ): Promise<ResourceEligibilityPublication> {
    validatePath(resourceId, contentVersion);
    const key = validateIdempotencyKey(idempotencyKey);
    const command = parseBody<WithdrawResourceEligibilityRequest>(
      WithdrawResourceEligibilitySchema,
      body,
    );
    return this.service.withdraw(resourceId, contentVersion, command, key, {
      actorId: user.accountId,
      correlationId: correlationId(request),
    });
  }

  @Post('internal/v1/resource-eligibility:resolve')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('USER')
  @HttpCode(HttpStatus.OK)
  async resolve(@Body() body: unknown): Promise<ResourceEligibilityBatchResponse> {
    const request = parseBody<ResourceEligibilityBatchRequest>(
      ResourceEligibilityBatchSchema,
      body,
    );
    return this.service.resolve(request);
  }
}

function validatePath(resourceId: string, contentVersion: string): void {
  if (!ResourceIdSchema.safeParse(resourceId).success) {
    throw new BadRequestException('Invalid resource ID');
  }
  if (
    !CONTENT_VERSION_RE.test(contentVersion) ||
    BigInt(contentVersion) > 9_223_372_036_854_775_807n
  ) {
    throw new BadRequestException('Invalid content version');
  }
}

function validateIdempotencyKey(value: string | undefined): string {
  if (!value || !IDEMPOTENCY_KEY_RE.test(value)) {
    throw new BadRequestException('Idempotency-Key header is required and must be opaque ASCII');
  }
  return value;
}

function parseBody<T>(schema: ZodType<T>, body: unknown): T {
  try {
    return schema.parse(body);
  } catch (error) {
    if (!(error instanceof ZodError)) throw error;
    throw new UnprocessableEntityException({
      type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
      title: 'Validation failed',
      status: 422,
      code: 'VALIDATION_ERROR',
      fieldViolations: error.issues.map((issue) => ({
        field: issue.path.join('.'),
        message: issue.message,
      })),
    });
  }
}

function correlationId(request: Request): string {
  return String(request.headers['x-correlation-id']);
}
