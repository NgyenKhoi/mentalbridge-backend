import {
  Body,
  Catch,
  Controller,
  Get,
  HttpCode,
  HttpException,
  Inject,
  Injectable,
  Param,
  Post,
  Req,
  Res,
  UseFilters,
  UseGuards,
  type ArgumentsHost,
  type CanActivate,
  type ExceptionFilter,
  type ExecutionContext,
} from "@nestjs/common";
import { randomUUID, timingSafeEqual } from "node:crypto";
import { z } from "zod";
import type { Logger } from "pino";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import { ProblemDetailsFilter } from "../http/problem-details.filter.js";
import { CONFIGURATION_TOKEN, LOGGER_TOKEN } from "../observability/tokens.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";
import { IdentityJwtVerifier } from "../security/identity-jwt-verifier.js";
import { Public } from "../security/public.decorator.js";
import { VoiceAccessError, VoiceAccessService } from "./voice-access.js";
import { VoiceLedgerError } from "./voice-ledger.js";
import {
  VoiceContextHandoffs,
  voiceStartCommandSchema,
} from "./voice-context-handoff.js";
import {
  VoiceSessionsService,
  voiceWorkerSignalSchema,
} from "./voice-sessions.js";
import {
  voiceExpressionCapability,
  voiceSpeechUnitsCapability,
  voiceTurnSchema,
  VoiceTurnError,
  type VoiceTurnService,
} from "./voice-turn.js";

export const VOICE_SESSIONS = "COMPANION_VOICE_SESSIONS";
export const VOICE_TURNS = "COMPANION_VOICE_TURNS";
export type VoiceSessionOperations = Pick<
  VoiceSessionsService,
  "start" | "status" | "connection" | "end" | "claim" | "control"
>;
interface VoiceRequest extends AuthenticatedRequest {
  once?(event: string, listener: () => void): void;
  off?(event: string, listener: () => void): void;
  id?: string;
  url?: string;
  originalUrl?: string;
  rawHeaders?: string[];
}
interface VoiceResponse {
  once?(event: string, listener: () => void): void;
  off?(event: string, listener: () => void): void;
  writableEnded?: boolean;
  destroyed?: boolean;
  write?(chunk: string): boolean;
  end?(chunk?: string): void;
  setHeader(name: string, value: string): void;
  status(code: number): VoiceResponse;
  json(body: unknown): void;
}
class VoiceBoundaryError extends Error {
  constructor(
    readonly code:
      "UNAUTHENTICATED" | "FORBIDDEN" | "INVALID_REQUEST" | "SESSION_NOT_FOUND",
  ) {
    super(code);
  }
}
const prepare = (request: VoiceRequest, response: VoiceResponse) => {
  const id = z.uuid().safeParse(request.id);
  request.id = id.success ? id.data : randomUUID();
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Correlation-Id", request.id);
};

@Injectable()
export class VoiceUserGuard implements CanActivate {
  constructor(private readonly verifier: IdentityJwtVerifier) {}
  async canActivate(context: ExecutionContext) {
    const request = context.switchToHttp().getRequest<VoiceRequest>();
    prepare(request, context.switchToHttp().getResponse<VoiceResponse>());
    const authorization = request.headers.authorization;
    const token =
      typeof authorization === "string"
        ? /^Bearer ([^\s]+)$/.exec(authorization)?.[1]
        : undefined;
    if (!token) throw new VoiceBoundaryError("UNAUTHENTICATED");
    try {
      request.principal = await this.verifier.verify(token);
    } catch {
      throw new VoiceBoundaryError("UNAUTHENTICATED");
    }
    if (!request.principal.roles.includes("USER"))
      throw new VoiceBoundaryError("FORBIDDEN");
    return true;
  }
}

@Injectable()
export class VoiceWorkerGuard implements CanActivate {
  constructor(
    @Inject(CONFIGURATION_TOKEN)
    private readonly configuration: ServiceConfiguration,
  ) {}
  canActivate(context: ExecutionContext) {
    const request = context.switchToHttp().getRequest<VoiceRequest>();
    prepare(request, context.switchToHttp().getResponse<VoiceResponse>());
    const expected = this.configuration.VOICE_MEDIA?.workerToken;
    const actual = request.headers["x-mentalbridge-voice-worker-token"];
    const occurrences =
      request.rawHeaders?.filter(
        (value, index) =>
          index % 2 === 0 &&
          value.toLowerCase() === "x-mentalbridge-voice-worker-token",
      ).length ?? 1;
    if (
      !expected ||
      typeof actual !== "string" ||
      request.headers.authorization !== undefined ||
      occurrences !== 1 ||
      Buffer.byteLength(actual) !== Buffer.byteLength(expected) ||
      !timingSafeEqual(Buffer.from(actual), Buffer.from(expected))
    )
      throw new VoiceBoundaryError("UNAUTHENTICATED");
    return true;
  }
}

const statuses: Readonly<Record<string, number>> = {
  UNAUTHENTICATED: 401,
  FORBIDDEN: 403,
  FREE_LOCKED: 403,
  CONSENT_REQUIRED: 403,
  VOICE_CONSENT_REQUIRED: 403,
  SESSION_NOT_FOUND: 404,
  SESSION_LIMIT: 409,
  IDEMPOTENCY_CONFLICT: 409,
  STALE_ATTEMPT: 409,
  INVALID_SESSION_STATE: 409,
  SESSION_EXPIRED: 410,
  INVALID_REQUEST: 422,
  INVALID_FINAL_TRANSCRIPT: 422,
  PROVIDER_TIMEOUT: 504,
  GLOBAL_CAPACITY_FULL: 429,
  QUOTA_EXHAUSTED: 429,
  RATE_LIMITED: 429,
  CONSENT_UNAVAILABLE: 503,
  ENTITLEMENT_UNAVAILABLE: 503,
  PROVIDER_UNAVAILABLE: 503,
};

@Catch()
export class VoiceBoundaryFilter implements ExceptionFilter {
  catch(error: unknown, host: ArgumentsHost): void {
    const request = host.switchToHttp().getRequest<VoiceRequest>();
    const response = host.switchToHttp().getResponse<VoiceResponse>();
    prepare(request, response);
    const code =
      error instanceof VoiceBoundaryError ||
      error instanceof VoiceAccessError ||
      error instanceof VoiceLedgerError ||
      error instanceof VoiceTurnError
        ? error.code
        : error instanceof HttpException &&
            [400, 413, 415].includes(error.getStatus())
          ? "INVALID_REQUEST"
          : error instanceof HttpException && error.getStatus() === 404
            ? "SESSION_NOT_FOUND"
            : "PROVIDER_UNAVAILABLE";
    const status = statuses[code] ?? 503;
    response.setHeader("Content-Type", "application/problem+json");
    response.status(status).json({
      type: `https://mentalbridge.dev/problems/voice/${code.toLowerCase().replaceAll("_", "-")}`,
      title:
        status === 401
          ? "Authentication is required"
          : status === 403
            ? "Access is denied"
            : status === 422
              ? "Invalid Voice request"
              : status === 503
                ? "Voice authorization is unavailable"
                : "Voice session request cannot proceed",
      status,
      code,
      correlationId: request.id,
    });
  }
}

@Catch()
export class VoiceGlobalFilter implements ExceptionFilter {
  private readonly voice = new VoiceBoundaryFilter();
  private readonly legacy = new ProblemDetailsFilter();
  catch(error: unknown, host: ArgumentsHost): void {
    const request = host.switchToHttp().getRequest<VoiceRequest>();
    const path = (request.originalUrl ?? request.url ?? "").split("?")[0] ?? "";
    if (
      path === "/api/v1/ai-companion/voice-eligibility" ||
      path === "/api/v1/ai-companion/voice-sessions" ||
      path === "/api/v1/ai-companion/voice-context-handoffs" ||
      path.startsWith("/api/v1/ai-companion/voice-sessions/") ||
      path.startsWith("/internal/v1/ai-companion/voice-sessions/")
    ) {
      this.voice.catch(error, host);
      return;
    }
    this.legacy.catch(error, host);
  }
}

const subject = (request: VoiceRequest) => {
  if (!request.principal) throw new VoiceBoundaryError("UNAUTHENTICATED");
  return request.principal.accountId;
};
const uuid = (value: unknown, missing = false) => {
  const parsed = z.uuid().safeParse(value);
  if (!parsed.success)
    throw new VoiceBoundaryError(
      missing ? "SESSION_NOT_FOUND" : "INVALID_REQUEST",
    );
  return parsed.data;
};
const empty = (request: VoiceRequest, body: unknown) => {
  const contentType = request.headers["content-type"];
  if (
    typeof contentType !== "string" ||
    contentType.split(";")[0]?.trim().toLowerCase() !== "application/json" ||
    !z.object({}).strict().safeParse(body).success
  )
    throw new VoiceBoundaryError("INVALID_REQUEST");
  return uuid(request.headers["idempotency-key"]);
};

@Public()
@UseGuards(VoiceUserGuard)
@UseFilters(VoiceBoundaryFilter)
@Controller("api/v1/ai-companion")
export class VoiceUserController {
  constructor(
    private readonly access: VoiceAccessService,
    @Inject(VOICE_SESSIONS) private readonly sessions: VoiceSessionOperations,
    @Inject(LOGGER_TOKEN) private readonly logger: Logger,
    private readonly handoffs: VoiceContextHandoffs,
  ) {}
  @Get("voice-eligibility")
  async eligibility(@Req() request: VoiceRequest) {
    const value = await this.access.eligibility(
      subject(request),
      request.id ?? randomUUID(),
    );
    return this.handoffs.available
      ? { ...value, textContextHandoffEnabled: true }
      : value;
  }
  @Post("voice-context-handoffs")
  @HttpCode(200)
  async contextHandoff(@Req() request: VoiceRequest, @Body() body: unknown) {
    const parsed = z
      .object({ conversationId: z.uuid() })
      .strict()
      .safeParse(body);
    if (
      !parsed.success ||
      typeof request.headers["content-type"] !== "string" ||
      request.headers["content-type"].split(";")[0]?.trim().toLowerCase() !==
        "application/json"
    )
      throw new VoiceBoundaryError("INVALID_REQUEST");
    if (!this.handoffs.available)
      throw new VoiceAccessError("PROVIDER_UNAVAILABLE");
    const owner = subject(request),
      eligibility = await this.access.eligibility(
        owner,
        request.id ?? randomUUID(),
      );
    if (eligibility.state !== "ELIGIBLE")
      throw new VoiceAccessError(eligibility.state);
    return this.handoffs.prepare(owner, parsed.data.conversationId);
  }
  @Post("voice-sessions")
  @HttpCode(202)
  async start(@Req() request: VoiceRequest, @Body() body: unknown) {
    const parsed = voiceStartCommandSchema.safeParse(body);
    if (
      !parsed.success ||
      typeof request.headers["content-type"] !== "string" ||
      request.headers["content-type"].split(";")[0]?.trim().toLowerCase() !==
        "application/json"
    )
      throw new VoiceBoundaryError("INVALID_REQUEST");
    const key = uuid(request.headers["idempotency-key"]);
    const owner = subject(request);
    const parsedId = z.uuid().safeParse(request.id);
    const correlationId = parsedId.success ? parsedId.data : randomUUID();
    this.logger.info({
      event: "voice_admission_command",
      source: "AUTHENTICATED_USER_REST",
      command: "START",
      correlationId,
      outcome: "RECEIVED",
    });
    try {
      const result = await this.sessions.start(
        owner,
        key,
        correlationId,
        parsed.data,
      );
      this.logger.info({
        event: "voice_admission_command",
        source: "AUTHENTICATED_USER_REST",
        command: "START",
        correlationId,
        outcome: "ACKNOWLEDGED",
      });
      return result;
    } catch (error) {
      this.logger.info({
        event: "voice_admission_command",
        source: "AUTHENTICATED_USER_REST",
        command: "START",
        correlationId,
        outcome: "REJECTED",
      });
      throw error;
    }
  }
  @Get("voice-sessions/:voiceSessionId")
  status(@Req() request: VoiceRequest, @Param("voiceSessionId") id: string) {
    return this.sessions.status(subject(request), uuid(id, true));
  }
  @Post("voice-sessions/:voiceSessionId/connection")
  @HttpCode(200)
  connection(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    const key = empty(request, body);
    return this.sessions.connection(
      subject(request),
      uuid(id),
      key,
      request.id ?? randomUUID(),
    );
  }
  @Post("voice-sessions/:voiceSessionId/end")
  @HttpCode(202)
  end(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    const key = empty(request, body);
    return this.sessions.end(subject(request), uuid(id), key);
  }
}

@Public()
@UseGuards(VoiceWorkerGuard)
@UseFilters(VoiceBoundaryFilter)
@Controller("internal/v1/ai-companion/voice-sessions")
export class VoiceWorkerController {
  constructor(
    @Inject(VOICE_SESSIONS) private readonly sessions: VoiceSessionOperations,
    @Inject(VOICE_TURNS)
    private readonly turns: Pick<VoiceTurnService, "generate"> &
      Partial<
        Pick<
          VoiceTurnService,
          "speechUnitsEnabled" | "cancelSpeechTurn" | "acknowledgeSpeechUnit"
        >
      >,
  ) {}
  @Post(":voiceSessionId/turns")
  @HttpCode(200)
  async turn(
    @Req() request: VoiceRequest,
    @Res({ passthrough: true }) response: VoiceResponse,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    const parsed = voiceTurnSchema.safeParse(body);
    if (!parsed.success) throw new VoiceBoundaryError("INVALID_REQUEST");
    const key = uuid(request.headers["idempotency-key"]);
    const controller = new AbortController();
    const abort = () => {
      if (!response.writableEnded) controller.abort();
    };
    request.once?.("aborted", abort);
    response.once?.("close", abort);
    try {
      const reply = {
        ...(await this.turns.generate(
          uuid(id),
          key,
          parsed.data,
          request.id ?? randomUUID(),
          controller.signal,
        )),
      };
      if (
        request.headers["x-mentalbridge-voice-capabilities"] !==
        voiceExpressionCapability
      )
        delete reply.expression;
      return reply;
    } finally {
      request.off?.("aborted", abort);
      response.off?.("close", abort);
    }
  }
  private json(request: VoiceRequest) {
    const value = request.headers["content-type"];
    if (
      typeof value !== "string" ||
      value.split(";")[0]?.trim().toLowerCase() !== "application/json"
    )
      throw new VoiceBoundaryError("INVALID_REQUEST");
  }
  @Post(":voiceSessionId/turns/speech-units")
  async speechUnits(
    @Req() request: VoiceRequest,
    @Res() response: VoiceResponse,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    if (
      !this.turns.speechUnitsEnabled?.() ||
      request.headers["x-mentalbridge-voice-capabilities"] !==
        voiceSpeechUnitsCapability ||
      !response.write ||
      !response.end
    )
      throw new VoiceBoundaryError("FORBIDDEN");
    const parsed = voiceTurnSchema.safeParse(body);
    if (!parsed.success) throw new VoiceBoundaryError("INVALID_REQUEST");
    const key = uuid(request.headers["idempotency-key"]);
    const controller = new AbortController();
    const abort = () => {
      if (!response.writableEnded) controller.abort();
    };
    request.once?.("aborted", abort);
    response.once?.("close", abort);
    let count = 0;
    const send = async (event: object) => {
      if (controller.signal.aborted || response.destroyed)
        throw new VoiceLedgerError("SESSION_EXPIRED");
      if (count === 0) {
        response.status(200);
        response.setHeader("Content-Type", "application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(
          "X-MentalBridge-Voice-Protocol",
          voiceSpeechUnitsCapability,
        );
        response.write?.("[");
      }
      const writable = response.write?.(
        `${count++ === 0 ? "" : ","}${JSON.stringify(event)}`,
      );
      if (writable === false)
        await new Promise<void>((resolve, reject) => {
          const stopped = () => {
            response.off?.("drain", drained);
            reject(new VoiceLedgerError("SESSION_EXPIRED"));
          };
          const drained = () => {
            controller.signal.removeEventListener("abort", stopped);
            resolve();
          };
          response.once?.("drain", drained);
          controller.signal.addEventListener("abort", stopped, { once: true });
          if (controller.signal.aborted) stopped();
        });
    };
    try {
      await this.turns.generate(
        uuid(id),
        key,
        parsed.data,
        request.id ?? randomUUID(),
        controller.signal,
        send,
      );
      if (count === 0) throw new VoiceLedgerError("INVALID_SESSION_STATE");
      await send({ type: "COMPLETED", stopReason: "END_TURN" });
    } catch (error) {
      if (count === 0) throw error;
      if (!controller.signal.aborted && !response.destroyed)
        await send({ type: "FAILED", code: "TURN_FAILED" });
    } finally {
      if (count > 0 && !response.destroyed) response.end("]");
      request.off?.("aborted", abort);
      response.off?.("close", abort);
    }
  }
  @Post(":voiceSessionId/turns/:turnId/cancel")
  @HttpCode(200)
  async cancelSpeechTurn(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Param("turnId") turnId: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    const parsed = z
      .object({ attemptId: z.uuid(), leaseId: z.uuid() })
      .strict()
      .safeParse(body);
    if (
      !parsed.success ||
      !this.turns.cancelSpeechTurn ||
      request.headers["x-mentalbridge-voice-capabilities"] !==
        voiceSpeechUnitsCapability
    )
      throw new VoiceBoundaryError("INVALID_REQUEST");
    await this.turns.cancelSpeechTurn(
      uuid(id),
      uuid(turnId),
      parsed.data.attemptId,
      parsed.data.leaseId,
    );
    return { voiceSessionId: id, turnId, status: "CANCELLED" };
  }
  @Post(":voiceSessionId/turns/:turnId/speech-unit-ack")
  @HttpCode(200)
  async acknowledgeSpeechUnit(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Param("turnId") turnId: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    const parsed = z
      .object({
        attemptId: z.uuid(),
        leaseId: z.uuid(),
        unitSequence: z.number().int().min(1).max(2),
        replyId: z.uuid(),
      })
      .strict()
      .safeParse(body);
    if (
      !parsed.success ||
      !this.turns.acknowledgeSpeechUnit ||
      request.headers["x-mentalbridge-voice-capabilities"] !==
        voiceSpeechUnitsCapability
    )
      throw new VoiceBoundaryError("INVALID_REQUEST");
    await this.turns.acknowledgeSpeechUnit(
      uuid(id),
      uuid(turnId),
      parsed.data.attemptId,
      parsed.data.leaseId,
      parsed.data.unitSequence,
      parsed.data.replyId,
    );
    return {
      voiceSessionId: id,
      turnId,
      unitSequence: parsed.data.unitSequence,
      status: "ACKNOWLEDGED",
    };
  }
  @Post(":voiceSessionId/agent-claim")
  @HttpCode(200)
  claim(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    if (
      (request.headers["x-mentalbridge-voice-capabilities"] ===
        voiceSpeechUnitsCapability) !==
      (this.turns.speechUnitsEnabled?.() === true)
    )
      throw new VoiceBoundaryError("FORBIDDEN");
    const parsed = z
      .object({
        attemptId: z.uuid(),
        dispatchId: z.uuid(),
        measurementHash: z
          .string()
          .regex(/^[A-Za-z0-9_-]{43}$/)
          .optional(),
      })
      .strict()
      .safeParse(body);
    if (!parsed.success) throw new VoiceBoundaryError("INVALID_REQUEST");
    return this.sessions.claim(
      uuid(id),
      parsed.data.attemptId,
      parsed.data.dispatchId,
      request.id ?? randomUUID(),
      parsed.data.measurementHash,
    );
  }
  @Post(":voiceSessionId/agent-control")
  @HttpCode(200)
  control(
    @Req() request: VoiceRequest,
    @Param("voiceSessionId") id: string,
    @Body() body: unknown,
  ) {
    this.json(request);
    const parsed = voiceWorkerSignalSchema.safeParse(body);
    if (!parsed.success) throw new VoiceBoundaryError("INVALID_REQUEST");
    return this.sessions.control(
      uuid(id),
      parsed.data,
      request.id ?? randomUUID(),
    );
  }
}
