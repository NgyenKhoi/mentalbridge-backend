import {
  BadRequestException,
  Body,
  ConflictException,
  Controller,
  Get,
  Headers,
  HttpCode,
  HttpStatus,
  Inject,
  Param,
  Patch,
  Post,
  Query,
  Res,
  UnprocessableEntityException,
  UseGuards,
} from '@nestjs/common';
import type { Response } from 'express';
import { ZodError } from 'zod';

import { SAFETY_DIRECTORY_SERVICE_TOKEN } from '../application.tokens.js';
import { CurrentUser } from '../auth/current-user.decorator.js';
import { JwtAuthGuard } from '../auth/jwt-auth.guard.js';
import { Public } from '../auth/public.decorator.js';
import { Roles } from '../auth/roles.decorator.js';
import { RolesGuard } from '../auth/roles.guard.js';
import type { AuthenticatedUser } from '../auth/jwt.strategy.js';
import {
  SafetyDirectoryEntryWriteSchema,
  SafetyDirectoryLookupSchema,
  type SafetyDirectoryEntryWrite,
} from './safety-directory.dto.js';
import type { SafetyDirectoryService } from './safety-directory.service.js';
import type {
  SafetyDirectoryAdminEntry,
  SafetyDirectoryLookupResponse,
} from './safety-directory.types.js';

const UUID_RE = /^[\da-f]{8}-[\da-f]{4}-[1-5][\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i;

@Controller('api/v1/safety-directory')
export class SafetyDirectoryController {
  constructor(
    @Inject(SAFETY_DIRECTORY_SERVICE_TOKEN)
    private readonly service: SafetyDirectoryService,
  ) {}

  @Get('admin/entries')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  listAdmin(): Promise<SafetyDirectoryAdminEntry[]> {
    return this.service.listAdmin();
  }

  @Post('admin/entries')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  @HttpCode(HttpStatus.CREATED)
  create(
    @Body() body: unknown,
    @Headers('idempotency-key') idempotencyKey: string | undefined,
    @CurrentUser() user: AuthenticatedUser,
  ): Promise<SafetyDirectoryAdminEntry> {
    if (!idempotencyKey || !/^[A-Za-z0-9_-]{1,128}$/.test(idempotencyKey)) {
      throw new BadRequestException('Idempotency-Key header is required');
    }
    return this.service.create(this.parseWrite(body), idempotencyKey, { actorId: user.accountId });
  }

  @Patch('admin/entries/:entryId')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async update(
    @Param('entryId') entryId: string,
    @Query('version') versionValue: string | undefined,
    @Body() body: unknown,
  ): Promise<SafetyDirectoryAdminEntry> {
    const result = await this.service.update(
      this.entryId(entryId),
      this.version(versionValue),
      this.parseWrite(body),
    );
    return this.requireMutation(result);
  }

  @Post('admin/entries/:entryId/review')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async review(
    @Param('entryId') entryId: string,
    @Query('version') versionValue: string | undefined,
    @CurrentUser() user: AuthenticatedUser,
  ): Promise<SafetyDirectoryAdminEntry> {
    const result = await this.service.review(this.entryId(entryId), this.version(versionValue), {
      actorId: user.accountId,
    });
    return this.requireMutation(result);
  }

  @Post('admin/entries/:entryId/deactivate')
  @UseGuards(JwtAuthGuard, RolesGuard)
  @Roles('ADMIN')
  async deactivate(
    @Param('entryId') entryId: string,
    @Query('version') versionValue: string | undefined,
    @CurrentUser() user: AuthenticatedUser,
  ): Promise<SafetyDirectoryAdminEntry> {
    const result = await this.service.deactivate(
      this.entryId(entryId),
      this.version(versionValue),
      {
        actorId: user.accountId,
      },
    );
    return this.requireMutation(result);
  }

  private parseWrite(body: unknown): SafetyDirectoryEntryWrite {
    try {
      const parsed = SafetyDirectoryEntryWriteSchema.parse(body);
      if (parsed.sourceRetrievedAt.getTime() > Date.now()) {
        throw new UnprocessableEntityException('sourceRetrievedAt cannot be in the future');
      }
      return parsed;
    } catch (error) {
      this.validation(error);
    }
  }

  private validation(error: unknown): never {
    if (error instanceof ZodError) {
      throw new UnprocessableEntityException({
        type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
        title: 'Validation failed',
        status: 422,
        code: 'VALIDATION_ERROR',
        fieldViolations: error.issues.map((issue) => ({
          field: issue.path.join('.') || 'request',
          message: issue.message,
        })),
      });
    }
    throw error;
  }

  private entryId(value: string): string {
    if (!UUID_RE.test(value)) throw new BadRequestException('entryId must be a UUID');
    return value;
  }

  private version(value: string | undefined): number {
    if (!value || !/^\d+$/.test(value)) {
      throw new BadRequestException('version must be a non-negative integer');
    }
    const version = Number(value);
    if (!Number.isSafeInteger(version)) {
      throw new BadRequestException('version must be a safe integer');
    }
    return version;
  }

  private requireMutation(result: SafetyDirectoryAdminEntry | null): SafetyDirectoryAdminEntry {
    if (!result) {
      throw new ConflictException({
        type: 'https://mentalbridge.io/errors/DIRECTORY_VERSION_CONFLICT',
        title: 'Directory record version or state changed',
        status: 409,
        code: 'DIRECTORY_VERSION_CONFLICT',
      });
    }
    return result;
  }
}

@Controller('api/v1/safety-directory:lookup')
export class SafetyDirectoryLookupController {
  constructor(
    @Inject(SAFETY_DIRECTORY_SERVICE_TOKEN)
    private readonly service: SafetyDirectoryService,
  ) {}

  @Post()
  @Public()
  @HttpCode(HttpStatus.OK)
  async lookup(
    @Body() body: unknown,
    @Res({ passthrough: true }) response: Response,
  ): Promise<SafetyDirectoryLookupResponse> {
    response.setHeader('Cache-Control', 'no-store');
    try {
      return await this.service.lookup(SafetyDirectoryLookupSchema.parse(body));
    } catch (error) {
      if (error instanceof ZodError) {
        throw new UnprocessableEntityException({
          type: 'https://mentalbridge.io/errors/VALIDATION_ERROR',
          title: 'Validation failed',
          status: 422,
          code: 'VALIDATION_ERROR',
          fieldViolations: error.issues.map((issue) => ({
            field: issue.path.join('.') || 'request',
            message: issue.message,
          })),
        });
      }
      throw error;
    }
  }
}
