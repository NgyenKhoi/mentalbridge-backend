import { BadRequestException, Controller, Get, Param, Query, Req, UseGuards } from '@nestjs/common';
import { z } from 'zod';

import { MessageService } from '../messages/message.service.js';
import { HttpJwtGuard } from '../security/http-jwt.guard.js';
import type { AuthenticatedRequest } from '../security/principal.js';

const conversationIdSchema = z.uuid();
const limitSchema = z.coerce.number().int().min(1).max(100).default(50);
const cursorSchema = z.string().min(1).max(512).optional();

@Controller('api/v1/conversations')
@UseGuards(HttpJwtGuard)
export class HistoryController {
  constructor(private readonly messages: MessageService) {}

  @Get(':conversationId/messages')
  history(
    @Req() request: AuthenticatedRequest,
    @Param('conversationId') conversationIdInput: string,
    @Query('limit') limitInput?: string,
    @Query('cursor') cursorInput?: string,
  ) {
    const conversationId = this.parse(conversationIdSchema, conversationIdInput);
    const limit = this.parse(limitSchema, limitInput);
    const cursor = this.parse(cursorSchema, cursorInput);
    const accountId = request.principal?.accountId;
    if (!accountId) throw new BadRequestException('Authenticated actor is missing');
    return this.messages.history(accountId, conversationId, limit, cursor);
  }

  private parse<T>(schema: z.ZodType<T>, value: unknown): T {
    const result = schema.safeParse(value);
    if (!result.success) throw new BadRequestException('Request validation failed');
    return result.data;
  }
}
