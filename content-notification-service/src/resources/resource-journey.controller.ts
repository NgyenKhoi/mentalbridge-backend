import {
  Body,
  Controller,
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

import { RESOURCE_JOURNEY_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import { ResourceJourneyDateSchema, ResourceJourneyRequestSchema } from './resource-journey.dto.js';
import type { ResourceJourneyService } from './resource-journey.service.js';
import type { ResourceJourney } from './resource-journey.types.js';

function validation(error: ZodError): UnprocessableEntityException {
  return new UnprocessableEntityException({
    type: 'https://mentalbridge.io/errors/RESOURCE_JOURNEY_VALIDATION_FAILED',
    title: 'Resource journey is invalid',
    status: 422,
    code: 'RESOURCE_JOURNEY_VALIDATION_FAILED',
    fieldViolations: error.issues.slice(0, 32).map((issue) => ({
      field: issue.path.join('.') || 'body',
      message: issue.message.slice(0, 300),
    })),
  });
}

@Controller('api/v1/resource-journeys')
@UseGuards(JwtAuthGuard)
export class ResourceJourneyController {
  constructor(
    @Inject(RESOURCE_JOURNEY_SERVICE_TOKEN)
    private readonly service: ResourceJourneyService,
  ) {}

  @Put(':localDate')
  async materialize(
    @CurrentUser() user: AuthenticatedUser,
    @Param('localDate') localDateValue: string,
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<ResourceJourney> {
    const date = ResourceJourneyDateSchema.safeParse(localDateValue);
    if (!date.success) throw validation(date.error);
    let input;
    try {
      input = ResourceJourneyRequestSchema.parse(body);
    } catch (error) {
      if (error instanceof ZodError) throw validation(error);
      throw error;
    }
    const journey = await this.service.materialize(user.accountId, date.data, input);
    if (!journey)
      throw new NotFoundException('No reviewed resources match the active support plan');
    response.setHeader('Cache-Control', 'private, no-store');
    return journey;
  }
}
