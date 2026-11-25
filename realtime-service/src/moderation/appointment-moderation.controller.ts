import { randomUUID } from 'node:crypto';

import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  Param,
  Patch,
  Post,
  Query,
  Req,
  UseGuards,
} from '@nestjs/common';
import { z } from 'zod';

import { HttpJwtGuard } from '../security/http-jwt.guard.js';
import type { AuthenticatedRequest } from '../security/principal.js';
import { AppointmentModerationService } from './appointment-moderation.service.js';

const uuid = z.uuid();
const key = z
  .string()
  .min(16)
  .max(128)
  .regex(/^[!-~]+$/);
const reportBody = z.strictObject({
  reason: z.enum([
    'HARASSMENT',
    'PRIVACY_CONCERN',
    'SELF_HARM_OR_CRISIS_CONCERN',
    'SPAM',
    'SEXUAL_OR_VIOLENT_CONTENT',
    'OTHER',
  ]),
  context: z.string().trim().min(1).max(1000).optional(),
});
const resolutionBody = z.strictObject({
  resolution: z.enum(['NO_ACTION', 'MESSAGE_REMOVED']),
  reason: z.string().trim().min(1).max(500),
});

@Controller('api/v1')
@UseGuards(HttpJwtGuard)
export class AppointmentModerationController {
  constructor(private readonly moderation: AppointmentModerationService) {}

  @Delete('conversations/:conversationId/messages/:messageId')
  removeOwn(
    @Req() request: AuthenticatedRequest,
    @Param('conversationId') conversationIdInput: string,
    @Param('messageId') messageIdInput: string,
    @Headers('idempotency-key') keyInput?: string,
  ) {
    const { principal, bearerToken } = this.authenticated(request);
    return this.moderation.removeOwn(
      bearerToken,
      principal,
      this.parse(uuid, conversationIdInput),
      this.parse(uuid, messageIdInput),
      this.parse(key, keyInput),
      request.id ?? randomUUID(),
    );
  }

  @Post('conversations/:conversationId/messages/:messageId/reports')
  report(
    @Req() request: AuthenticatedRequest,
    @Param('conversationId') conversationIdInput: string,
    @Param('messageId') messageIdInput: string,
    @Headers('idempotency-key') keyInput: string | undefined,
    @Body() bodyInput: unknown,
  ) {
    const { principal, bearerToken } = this.authenticated(request);
    const body = this.parse(reportBody, bodyInput);
    return this.moderation.report(
      bearerToken,
      principal,
      this.parse(uuid, conversationIdInput),
      this.parse(uuid, messageIdInput),
      this.parse(key, keyInput),
      body.reason,
      body.context,
      request.id ?? randomUUID(),
    );
  }

  @Get('admin/appointment-chat/reports')
  list(
    @Req() request: AuthenticatedRequest,
    @Query('state') stateInput?: string,
    @Query('limit') limitInput?: string,
  ) {
    const { principal } = this.authenticated(request);
    return this.moderation.list(
      principal,
      this.parse(z.enum(['OPEN', 'RESOLVED']).optional(), stateInput),
      this.parse(z.coerce.number().int().min(1).max(100).default(50), limitInput),
    );
  }

  @Get('admin/appointment-chat/reports/:reportId')
  detail(@Req() request: AuthenticatedRequest, @Param('reportId') reportIdInput: string) {
    const { principal } = this.authenticated(request);
    return this.moderation.detail(principal, this.parse(uuid, reportIdInput));
  }

  @Patch('admin/appointment-chat/reports/:reportId')
  resolve(
    @Req() request: AuthenticatedRequest,
    @Param('reportId') reportIdInput: string,
    @Headers('idempotency-key') keyInput: string | undefined,
    @Body() bodyInput: unknown,
  ) {
    const { principal } = this.authenticated(request);
    const body = this.parse(resolutionBody, bodyInput);
    return this.moderation.resolve(
      principal,
      this.parse(uuid, reportIdInput),
      this.parse(key, keyInput),
      body.resolution,
      body.reason,
      request.id ?? randomUUID(),
    );
  }

  private authenticated(request: AuthenticatedRequest) {
    if (!request.principal || !request.bearerToken)
      throw new BadRequestException('Authenticated actor is missing');
    return { principal: request.principal, bearerToken: request.bearerToken };
  }

  private parse<T>(schema: z.ZodType<T>, value: unknown): T {
    const result = schema.safeParse(value);
    if (!result.success) throw new BadRequestException('Request validation failed');
    return result.data;
  }
}
