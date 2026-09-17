import {
  BadRequestException,
  ConflictException,
  Controller,
  Delete,
  Get,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Patch,
  Post,
  Query,
  Req,
  Res,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type OnApplicationShutdown,
  type Provider,
} from "@nestjs/common";
import { Binary, MongoClient, type Collection } from "mongodb";
import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { z } from "zod";

import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import { CareConsentClient, type ConsentClient } from "../analysis/analysis.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const REPOSITORY = "EMOTION_CHECK_IN_REPOSITORY";
const CRYPTO = "EMOTION_CHECK_IN_CRYPTO";
const CONSENT = "EMOTION_CHECK_IN_CONSENT";
const CLOCK = "EMOTION_CHECK_IN_CLOCK";
const MAX_REVISIONS = 32;
const TOMBSTONE_RETENTION_DAYS = 30;

export const EMOTIONS = ["GREAT", "GOOD", "OKAY", "LOW", "VERY_LOW"] as const;
export type Emotion = (typeof EMOTIONS)[number];

export interface EncryptedCheckInPayload {
  ciphertext: Buffer | Binary;
  iv: Buffer | Binary;
  tag: Buffer | Binary;
  algorithm: "AES-256-GCM";
  keyId: string;
  encryptedAt: Date;
}

export interface CheckInValue {
  emotion: Emotion;
  intensity: number;
  note: string | null;
}

export interface CheckInRevision {
  revision: number;
  recordedAt: Date;
  payload: EncryptedCheckInPayload;
}

export interface CheckInCommand {
  keyHash: string;
  fingerprint: string;
  operation: "create" | "update" | "delete";
  response: { kind: "check-in"; revision: number } | { kind: "tombstone" };
  recordedAt: Date;
}

export interface EmotionCheckInDocument {
  _id: string;
  ownerAccountId: string;
  localDate: string;
  timezone: string;
  currentRevision: number;
  revisions: CheckInRevision[];
  commands: CheckInCommand[];
  createdAt: Date;
  updatedAt: Date;
  deleted: boolean;
  deletedAt: Date | null;
  purgeAfter: Date | null;
}

export interface EmotionCheckInRepository {
  create(value: EmotionCheckInDocument): Promise<EmotionCheckInDocument>;
  find(
    ownerAccountId: string,
    localDate: string,
  ): Promise<EmotionCheckInDocument | null>;
  findByCommand(
    ownerAccountId: string,
    keyHash: string,
  ): Promise<EmotionCheckInDocument | null>;
  list(
    ownerAccountId: string,
    limit: number,
    before?: string,
  ): Promise<{ rows: EmotionCheckInDocument[]; hasMore: boolean }>;
  update(
    ownerAccountId: string,
    localDate: string,
    expectedRevision: number,
    revision: CheckInRevision,
    command: CheckInCommand,
  ): Promise<EmotionCheckInDocument | null>;
  tombstone(
    ownerAccountId: string,
    localDate: string,
    deletedAt: Date,
    purgeAfter: Date,
    command: CheckInCommand,
  ): Promise<EmotionCheckInDocument | null>;
}

export interface EmotionCheckInClock {
  now(): Date;
}

export interface EmotionCheckInDependencies {
  repository?: EmotionCheckInRepository;
  consentClient?: ConsentClient;
  clock?: EmotionCheckInClock;
}

interface CheckInRequest extends AuthenticatedRequest {
  readonly id?: string;
  body: unknown;
}

const uuid = z.uuid();
const localDateSchema = z.iso.date();
const timezoneSchema = z
  .string()
  .min(1)
  .max(64)
  .refine((value) => {
    try {
      new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
      return true;
    } catch {
      return false;
    }
  });
const noteSchema = z
  .string()
  .max(500)
  .refine((value) => value.trim().length > 0, "Note cannot be blank")
  .nullable()
  .optional();
const valueSchema = z
  .object({
    emotion: z.enum(EMOTIONS),
    intensity: z.number().int().min(1).max(5),
    note: noteSchema,
  })
  .strict();
const createSchema = valueSchema
  .extend({ localDate: localDateSchema, timezone: timezoneSchema })
  .strict();
const listSchema = z
  .object({
    limit: z.coerce.number().int().min(1).max(90).default(30),
    before: localDateSchema.optional(),
  })
  .strict();
const contextQuerySchema = z
  .object({
    purpose: z.literal("AI_REFLECTION"),
    limit: z.coerce.number().int().min(1).max(30).default(14),
  })
  .strict();
const idempotencyKeySchema = z.string().min(16).max(128);
const revisionHeaderSchema = z.coerce.number().int().min(1).max(MAX_REVISIONS);

const buffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

const owner = (request: CheckInRequest): string => {
  const accountId = request.principal?.accountId;
  if (!accountId || !uuid.safeParse(accountId).success)
    throw new UnauthorizedException();
  return accountId;
};

const header = (request: CheckInRequest, name: string): string | undefined => {
  const value = request.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
};

const requiredHeader = (
  request: CheckInRequest,
  name: string,
  schema: z.ZodType,
): unknown => {
  const parsed = schema.safeParse(header(request, name));
  if (!parsed.success) throw new BadRequestException();
  return parsed.data;
};

const bearerToken = (request: CheckInRequest): string => {
  const authorization = header(request, "authorization");
  if (!authorization?.startsWith("Bearer ")) throw new UnauthorizedException();
  return authorization.slice("Bearer ".length);
};

export const localDateAt = (instant: Date, timezone: string): string => {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(instant);
  const part = (type: Intl.DateTimeFormatPartTypes): string => {
    const value = parts.find((candidate) => candidate.type === type)?.value;
    if (!value) throw new BadRequestException();
    return value;
  };
  return `${part("year")}-${part("month")}-${part("day")}`;
};

@Injectable()
export class EmotionCheckInCrypto {
  constructor(private readonly configuration: Configuration) {}

  encrypt(
    ownerAccountId: string,
    localDate: string,
    revision: number,
    value: CheckInValue,
  ): EncryptedCheckInPayload {
    const iv = randomBytes(12);
    const encryptedAt = new Date();
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.configuration.JOURNAL_ENCRYPTION_KEY,
      iv,
    );
    cipher.setAAD(
      Buffer.from(
        `${ownerAccountId}:${localDate}:${String(revision)}:emotion-check-in`,
      ),
    );
    const ciphertext = Buffer.concat([
      cipher.update(JSON.stringify(value), "utf8"),
      cipher.final(),
    ]);
    return {
      ciphertext,
      iv,
      tag: cipher.getAuthTag(),
      algorithm: "AES-256-GCM",
      keyId: this.configuration.JOURNAL_ENCRYPTION_KEY_ID,
      encryptedAt,
    };
  }

  decrypt(
    ownerAccountId: string,
    localDate: string,
    revision: CheckInRevision,
  ): CheckInValue {
    if (revision.payload.keyId !== this.configuration.JOURNAL_ENCRYPTION_KEY_ID)
      throw new ServiceUnavailableException();
    try {
      const decipher = createDecipheriv(
        "aes-256-gcm",
        this.configuration.JOURNAL_ENCRYPTION_KEY,
        buffer(revision.payload.iv),
      );
      decipher.setAAD(
        Buffer.from(
          `${ownerAccountId}:${localDate}:${String(revision.revision)}:emotion-check-in`,
        ),
      );
      decipher.setAuthTag(buffer(revision.payload.tag));
      return valueSchema.parse(
        JSON.parse(
          Buffer.concat([
            decipher.update(buffer(revision.payload.ciphertext)),
            decipher.final(),
          ]).toString("utf8"),
        ),
      ) as CheckInValue;
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }

  keyHash(value: string): string {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(value)
      .digest("base64url");
  }

  fingerprint(value: unknown): string {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(JSON.stringify(value))
      .digest("base64url");
  }
}

@Injectable()
export class MongoEmotionCheckInRepository
  implements EmotionCheckInRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly collection: Collection<EmotionCheckInDocument>;

  constructor(configuration: Configuration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    this.collection = this.client
      .db(configuration.MONGODB_DATABASE)
      .collection<EmotionCheckInDocument>("emotion_check_ins");
  }

  private async values(): Promise<Collection<EmotionCheckInDocument>> {
    try {
      await this.client.connect();
      return this.collection;
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }

  async create(value: EmotionCheckInDocument) {
    try {
      await (await this.values()).insertOne(value);
      return value;
    } catch (error) {
      if ((error as { code?: number }).code === 11_000)
        throw new ConflictException();
      throw error;
    }
  }

  async find(ownerAccountId: string, localDate: string) {
    return (await this.values()).findOne({ ownerAccountId, localDate });
  }

  async findByCommand(ownerAccountId: string, keyHash: string) {
    return (await this.values()).findOne({
      ownerAccountId,
      "commands.keyHash": keyHash,
    });
  }

  async list(ownerAccountId: string, limit: number, before?: string) {
    const rows = await (
      await this.values()
    )
      .find({
        ownerAccountId,
        deleted: false,
        ...(before ? { localDate: { $lt: before } } : {}),
      })
      .sort({ localDate: -1 })
      .hint("emotion_check_ins_owner_history_idx")
      .limit(limit + 1)
      .toArray();
    return { rows: rows.slice(0, limit), hasMore: rows.length > limit };
  }

  async update(
    ownerAccountId: string,
    localDate: string,
    expectedRevision: number,
    revision: CheckInRevision,
    command: CheckInCommand,
  ) {
    return (await this.values()).findOneAndUpdate(
      {
        ownerAccountId,
        localDate,
        deleted: false,
        currentRevision: expectedRevision,
        "commands.keyHash": { $ne: command.keyHash },
      },
      {
        $inc: { currentRevision: 1 },
        $set: { updatedAt: command.recordedAt },
        $push: { revisions: revision, commands: command },
      },
      { returnDocument: "after" },
    );
  }

  async tombstone(
    ownerAccountId: string,
    localDate: string,
    deletedAt: Date,
    purgeAfter: Date,
    command: CheckInCommand,
  ) {
    return (await this.values()).findOneAndUpdate(
      {
        ownerAccountId,
        localDate,
        deleted: false,
        "commands.keyHash": { $ne: command.keyHash },
      },
      {
        $set: {
          deleted: true,
          deletedAt,
          purgeAfter,
          updatedAt: deletedAt,
          revisions: [],
        },
        $push: { commands: command },
      },
      { returnDocument: "after" },
    );
  }
}

@Injectable()
export class EmotionCheckInService {
  constructor(
    @Inject(REPOSITORY)
    private readonly repository: EmotionCheckInRepository,
    @Inject(CRYPTO) private readonly crypto: EmotionCheckInCrypto,
    @Inject(CONSENT) private readonly consent: ConsentClient,
    @Inject(CLOCK) private readonly clock: EmotionCheckInClock,
  ) {}

  private command(
    request: CheckInRequest,
    operation: CheckInCommand["operation"],
    value: unknown,
    recordedAt = this.clock.now(),
  ): CheckInCommand {
    const key = String(
      requiredHeader(request, "idempotency-key", idempotencyKeySchema),
    );
    return {
      keyHash: this.crypto.keyHash(key),
      fingerprint: this.crypto.fingerprint({ operation, value }),
      operation,
      response: { kind: "tombstone" },
      recordedAt,
    };
  }

  private replay(
    document: EmotionCheckInDocument,
    command: CheckInCommand,
  ): CheckInCommand | undefined {
    const previous = document.commands.find(
      (candidate) => candidate.keyHash === command.keyHash,
    );
    if (!previous) return undefined;
    if (
      previous.operation !== command.operation ||
      previous.fingerprint !== command.fingerprint
    )
      throw new ConflictException();
    return previous;
  }

  private assertCurrentLocalDay(localDate: string, timezone: string): void {
    if (localDateAt(this.clock.now(), timezone) !== localDate)
      throw new BadRequestException();
  }

  private revision(
    document: EmotionCheckInDocument,
    revisionNumber = document.currentRevision,
  ): CheckInRevision {
    const revision = document.revisions.find(
      (candidate) => candidate.revision === revisionNumber,
    );
    if (!revision) throw new ServiceUnavailableException();
    return revision;
  }

  private output(
    document: EmotionCheckInDocument,
    revisionNumber = document.currentRevision,
  ) {
    const revision = this.revision(document, revisionNumber);
    const value = this.crypto.decrypt(
      document.ownerAccountId,
      document.localDate,
      revision,
    );
    return {
      id: document._id,
      localDate: document.localDate,
      timezone: document.timezone,
      emotion: value.emotion,
      intensity: value.intensity,
      note: value.note,
      sourceLabel: "SELF_REPORTED_EMOTION" as const,
      clinicalUse: "NOT_A_DIAGNOSIS_OR_SAFETY_CLASSIFIER" as const,
      revision: revision.revision,
      recordedAt: revision.recordedAt.toISOString(),
      createdAt: document.createdAt.toISOString(),
      updatedAt: revision.recordedAt.toISOString(),
    };
  }

  async create(request: CheckInRequest) {
    const parsed = createSchema.safeParse(request.body);
    if (!parsed.success) throw new BadRequestException();
    const ownerAccountId = owner(request);
    const value: CheckInValue = {
      emotion: parsed.data.emotion,
      intensity: parsed.data.intensity,
      note: parsed.data.note ?? null,
    };
    const command = this.command(request, "create", parsed.data);
    const previousByCommand = await this.repository.findByCommand(
      ownerAccountId,
      command.keyHash,
    );
    if (previousByCommand) {
      const replay = this.replay(previousByCommand, command);
      if (replay?.response.kind !== "check-in") throw new ConflictException();
      return this.output(previousByCommand, replay.response.revision);
    }
    this.assertCurrentLocalDay(parsed.data.localDate, parsed.data.timezone);
    const now = command.recordedAt;
    const revision: CheckInRevision = {
      revision: 1,
      recordedAt: now,
      payload: this.crypto.encrypt(
        ownerAccountId,
        parsed.data.localDate,
        1,
        value,
      ),
    };
    const document: EmotionCheckInDocument = {
      _id: randomUUID(),
      ownerAccountId,
      localDate: parsed.data.localDate,
      timezone: parsed.data.timezone,
      currentRevision: 1,
      revisions: [revision],
      commands: [
        {
          ...command,
          response: { kind: "check-in", revision: 1 },
        },
      ],
      createdAt: now,
      updatedAt: now,
      deleted: false,
      deletedAt: null,
      purgeAfter: null,
    };
    try {
      return this.output(await this.repository.create(document));
    } catch (error) {
      if (!(error instanceof ConflictException)) throw error;
      const raced = await this.repository.findByCommand(
        ownerAccountId,
        command.keyHash,
      );
      if (!raced) throw error;
      const replay = this.replay(raced, command);
      if (replay?.response.kind !== "check-in") throw error;
      return this.output(raced, replay.response.revision);
    }
  }

  async get(request: CheckInRequest, localDate: string) {
    if (!localDateSchema.safeParse(localDate).success)
      throw new BadRequestException();
    const document = await this.repository.find(owner(request), localDate);
    if (!document || document.deleted) throw new NotFoundException();
    return this.output(document);
  }

  async list(
    request: CheckInRequest,
    query: Record<string, string | undefined>,
  ) {
    const parsed = listSchema.safeParse(query);
    if (!parsed.success) throw new BadRequestException();
    const result = await this.repository.list(
      owner(request),
      parsed.data.limit,
      parsed.data.before,
    );
    const last = result.rows.at(-1);
    return {
      items: result.rows.map((document) => this.output(document)),
      page: {
        limit: parsed.data.limit,
        hasMore: result.hasMore,
        ...(result.hasMore && last ? { nextBefore: last.localDate } : {}),
      },
      label: "SELF_REPORTED_EMOTION" as const,
      interpretation: "NOT_DIAGNOSIS_OR_RECOVERY" as const,
    };
  }

  async update(request: CheckInRequest, localDate: string) {
    if (!localDateSchema.safeParse(localDate).success)
      throw new BadRequestException();
    const parsed = valueSchema.safeParse(request.body);
    if (!parsed.success) throw new BadRequestException();
    const ownerAccountId = owner(request);
    const expectedRevision = Number(
      requiredHeader(request, "if-match-revision", revisionHeaderSchema),
    );
    const command = this.command(request, "update", {
      localDate,
      expectedRevision,
      body: parsed.data,
    });
    const document = await this.repository.find(ownerAccountId, localDate);
    if (!document || document.deleted) throw new NotFoundException();
    const replay = this.replay(document, command);
    if (replay) {
      if (replay.response.kind !== "check-in") throw new ConflictException();
      return this.output(document, replay.response.revision);
    }
    this.assertCurrentLocalDay(localDate, document.timezone);
    if (document.currentRevision !== expectedRevision)
      throw new HttpException("", HttpStatus.PRECONDITION_FAILED);
    if (document.currentRevision >= MAX_REVISIONS)
      throw new ConflictException();
    const nextRevision = expectedRevision + 1;
    const revision: CheckInRevision = {
      revision: nextRevision,
      recordedAt: command.recordedAt,
      payload: this.crypto.encrypt(ownerAccountId, localDate, nextRevision, {
        emotion: parsed.data.emotion,
        intensity: parsed.data.intensity,
        note: parsed.data.note ?? null,
      }),
    };
    const updated = await this.repository.update(
      ownerAccountId,
      localDate,
      expectedRevision,
      revision,
      {
        ...command,
        response: { kind: "check-in", revision: nextRevision },
      },
    );
    if (updated) return this.output(updated);
    const raced = await this.repository.find(ownerAccountId, localDate);
    if (!raced || raced.deleted) throw new NotFoundException();
    const racedReplay = this.replay(raced, command);
    if (racedReplay?.response.kind === "check-in")
      return this.output(raced, racedReplay.response.revision);
    throw new HttpException("", HttpStatus.PRECONDITION_FAILED);
  }

  async remove(request: CheckInRequest, localDate: string) {
    if (!localDateSchema.safeParse(localDate).success)
      throw new BadRequestException();
    const ownerAccountId = owner(request);
    const command = this.command(request, "delete", { localDate });
    const document = await this.repository.find(ownerAccountId, localDate);
    if (!document) throw new NotFoundException();
    const replay = this.replay(document, command);
    if (replay?.response.kind === "tombstone")
      return {
        localDate,
        deleted: true as const,
        deletedAt: replay.recordedAt.toISOString(),
      };
    if (document.deleted) throw new NotFoundException();
    const purgeAfter = new Date(command.recordedAt);
    purgeAfter.setUTCDate(purgeAfter.getUTCDate() + TOMBSTONE_RETENTION_DAYS);
    const deleted = await this.repository.tombstone(
      ownerAccountId,
      localDate,
      command.recordedAt,
      purgeAfter,
      { ...command, response: { kind: "tombstone" } },
    );
    if (!deleted) {
      const raced = await this.repository.find(ownerAccountId, localDate);
      const racedReplay = raced ? this.replay(raced, command) : undefined;
      if (racedReplay?.response.kind === "tombstone")
        return {
          localDate,
          deleted: true as const,
          deletedAt: racedReplay.recordedAt.toISOString(),
        };
      throw new NotFoundException();
    }
    return {
      localDate,
      deleted: true as const,
      deletedAt: command.recordedAt.toISOString(),
    };
  }

  async context(
    request: CheckInRequest,
    query: Record<string, string | undefined>,
  ) {
    const parsed = contextQuerySchema.safeParse(query);
    if (!parsed.success) throw new BadRequestException();
    let decision: Awaited<ReturnType<ConsentClient["check"]>>;
    try {
      decision = await this.consent.check(
        bearerToken(request),
        request.id ?? randomUUID(),
      );
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
    if (!decision.authorized) throw new HttpException("", HttpStatus.FORBIDDEN);
    const result = await this.repository.list(
      owner(request),
      parsed.data.limit,
    );
    return {
      purpose: parsed.data.purpose,
      consent: {
        type: "AI_PROCESSING" as const,
        policyVersion: decision.policyVersion ?? null,
        decidedAt: decision.decidedAt ?? null,
      },
      items: result.rows.map((document) => {
        const output = this.output(document);
        return {
          localDate: output.localDate,
          timezone: output.timezone,
          emotion: output.emotion,
          intensity: output.intensity,
          sourceLabel: output.sourceLabel,
          revision: output.revision,
          recordedAt: output.recordedAt,
        };
      }),
    };
  }
}

@Controller("api/v1/emotion-check-ins")
export class EmotionCheckInController {
  constructor(private readonly service: EmotionCheckInService) {}

  @Post()
  async create(
    @Req() request: CheckInRequest,
    @Res({ passthrough: true })
    response: { setHeader(name: string, value: string): void },
  ) {
    const value = await this.service.create(request);
    response.setHeader(
      "Location",
      `/api/v1/emotion-check-ins/${value.localDate}`,
    );
    return value;
  }

  @Get()
  list(
    @Req() request: CheckInRequest,
    @Query() query: Record<string, string | undefined>,
  ) {
    return this.service.list(request, query);
  }

  @Get(":localDate")
  get(@Req() request: CheckInRequest, @Param("localDate") localDate: string) {
    return this.service.get(request, localDate);
  }

  @Patch(":localDate")
  update(
    @Req() request: CheckInRequest,
    @Param("localDate") localDate: string,
  ) {
    return this.service.update(request, localDate);
  }

  @Delete(":localDate")
  remove(
    @Req() request: CheckInRequest,
    @Param("localDate") localDate: string,
  ) {
    return this.service.remove(request, localDate);
  }
}

@Controller("api/v1/emotion-check-in-context")
export class EmotionCheckInContextController {
  constructor(private readonly service: EmotionCheckInService) {}

  @Get()
  context(
    @Req() request: CheckInRequest,
    @Query() query: Record<string, string | undefined>,
  ) {
    return this.service.context(request, query);
  }
}

export const registerEmotionCheckInModule = (
  configuration: Configuration,
  dependencies: EmotionCheckInDependencies = {},
): DynamicModule => {
  const repository: Provider = dependencies.repository
    ? { provide: REPOSITORY, useValue: dependencies.repository }
    : {
        provide: REPOSITORY,
        useFactory: () => new MongoEmotionCheckInRepository(configuration),
      };
  const consent: Provider = dependencies.consentClient
    ? { provide: CONSENT, useValue: dependencies.consentClient }
    : {
        provide: CONSENT,
        useFactory: () => new CareConsentClient(configuration),
      };
  return {
    module: EmotionCheckInModule,
    controllers: [EmotionCheckInController, EmotionCheckInContextController],
    providers: [
      EmotionCheckInService,
      repository,
      consent,
      {
        provide: CRYPTO,
        useFactory: () => new EmotionCheckInCrypto(configuration),
      },
      {
        provide: CLOCK,
        useValue: dependencies.clock ?? { now: () => new Date() },
      },
    ],
  };
};

@Module({})
// Nest uses this class as declarative module metadata.
// eslint-disable-next-line @typescript-eslint/no-extraneous-class
export class EmotionCheckInModule {}
