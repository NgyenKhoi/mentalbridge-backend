import {
  BadRequestException,
  ConflictException,
  Controller,
  Get,
  Inject,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Patch,
  Post,
  Req,
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
  randomBytes,
  randomUUID,
} from "node:crypto";
import { z } from "zod";

import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const REPOSITORY = "COMPANION_STATE_REPOSITORY";
const CRYPTO = "COMPANION_STATE_CRYPTO";
const CLOCK = "COMPANION_STATE_CLOCK";
const MAX_CHAPTERS = 12;
const MAX_COMMITMENTS = 30;
const MAX_OPEN_COMMITMENTS = 3;
const MAX_SEEN = 300;
const DAY_MS = 86_400_000;

export const FOCUS_GOALS = [
  "SLEEP",
  "STUDY_WORK_PRESSURE",
  "LONELINESS",
  "FAMILY",
  "RELATIONSHIPS",
  "SELF_WORTH",
  "NOT_SURE",
] as const;
export const CHAPTER_RATINGS = [
  "LIGHTER",
  "SAME",
  "HEAVIER",
  "UNSURE",
] as const;
export const CHAPTER_OBSTACLES = [
  "BUSY",
  "FORGOT",
  "LOW_ENERGY",
  "NOT_A_FIT",
  "OTHER",
] as const;
export const CHAPTER_CONNECTED = ["YES", "NOT_YET", "PREFER_NOT_SAY"] as const;
export const COMMITMENT_KINDS = [
  "STUDY_WORK_STEP",
  "REST",
  "MOVE",
  "CONNECT_PERSON",
  "JOURNAL",
  "RELAX",
  "PLAN_ACTIVITY",
  "SPECIALIST_STEP",
] as const;
export const COMMITMENT_MINUTES = [5, 10, 15, 25, 30] as const;
export const COMMITMENT_SOURCES = [
  "CHAT",
  "PLAN_SKIP",
  "SESSION",
  "LOOK_BACK",
  "GUIDE",
  "HOME",
] as const;
export const COMMITMENT_STATUSES = [
  "OPEN",
  "DONE",
  "NOT_YET",
  "SWAPPED",
] as const;

const uuid = z.uuid();
const localDate = z.iso.date();
const instant = z.iso.datetime();
const seenKey = z.string().regex(/^[a-z0-9:_-]{1,80}$/);
const guide = z.string().max(6144).nullable();
const focusSchema = z
  .object({ goal: z.enum(FOCUS_GOALS), chosenOn: localDate })
  .strict();
const chapterFields = {
  startedOn: localDate,
  goal: z.enum(FOCUS_GOALS).optional(),
  rating: z.enum(CHAPTER_RATINGS).optional(),
  obstacle: z.enum(CHAPTER_OBSTACLES).optional(),
  connected: z.enum(CHAPTER_CONNECTED).optional(),
};
const chapterSchema = z
  .object({ ...chapterFields, updatedAt: instant })
  .strict();
const commitmentFields = {
  kind: z.enum(COMMITMENT_KINDS),
  minutes: z.literal(COMMITMENT_MINUTES).nullable(),
  dueOn: localDate,
  source: z.enum(COMMITMENT_SOURCES),
};
const commitmentSchema = z
  .object({
    id: uuid,
    ...commitmentFields,
    status: z.enum(COMMITMENT_STATUSES),
    createdAt: instant,
    resolvedAt: instant.nullable(),
  })
  .strict();
const stateSchema = z
  .object({
    focus: focusSchema.nullable(),
    chapters: z.array(chapterSchema).max(MAX_CHAPTERS),
    commitments: z.array(commitmentSchema).max(MAX_COMMITMENTS),
    ui: z.object({ guide, seen: z.array(seenKey).max(MAX_SEEN) }).strict(),
  })
  .strict();
const patchSchema = z
  .object({
    focus: focusSchema.nullable().optional(),
    chapter: z.object(chapterFields).strict().optional(),
    guide: guide.optional(),
    seenAdd: z.array(seenKey).min(1).max(50).optional(),
  })
  .strict()
  .refine((value) => Object.values(value).some((item) => item !== undefined));
const createCommitmentSchema = z.object(commitmentFields).strict();
const resolveCommitmentSchema = z
  .object({ status: z.enum(["DONE", "NOT_YET", "SWAPPED"]) })
  .strict();

export type CompanionStateValue = z.infer<typeof stateSchema>;
export type Commitment = z.infer<typeof commitmentSchema>;

export interface CompanionStateDocument {
  _id: string;
  ownerAccountId: string;
  revision: number;
  encryptedPayload: Buffer | Binary;
  iv: Buffer | Binary;
  tag: Buffer | Binary;
  keyId: string;
  updatedAt: Date;
}

export interface CompanionStateRepository {
  find(ownerAccountId: string): Promise<CompanionStateDocument | null>;
  save(
    document: CompanionStateDocument,
    expectedRevision: number,
  ): Promise<boolean>;
}

export interface CompanionStateClock {
  now(): Date;
}

export interface CompanionStateDependencies {
  repository?: CompanionStateRepository;
  clock?: CompanionStateClock;
}

interface CompanionStateRequest extends AuthenticatedRequest {
  body: unknown;
}

const buffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

const owner = (request: CompanionStateRequest): string => {
  const accountId = request.principal?.accountId;
  if (!accountId || !uuid.safeParse(accountId).success)
    throw new UnauthorizedException();
  return accountId;
};

const parse = <T>(schema: z.ZodType<T>, value: unknown): T => {
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw new BadRequestException();
  return parsed.data;
};

const emptyState = (): CompanionStateValue => ({
  focus: null,
  chapters: [],
  commitments: [],
  ui: { guide: null, seen: [] },
});

const daysFromUtcToday = (date: string, now: Date): number =>
  (Date.parse(`${date}T00:00:00Z`) -
    Date.parse(`${now.toISOString().slice(0, 10)}T00:00:00Z`)) /
  DAY_MS;

@Injectable()
export class CompanionStateCrypto {
  constructor(private readonly configuration: Configuration) {}

  private aad(ownerAccountId: string, revision: number): Buffer {
    return Buffer.from(`${ownerAccountId}:${String(revision)}:companion-state`);
  }

  encrypt(
    ownerAccountId: string,
    revision: number,
    value: CompanionStateValue,
  ): Pick<CompanionStateDocument, "encryptedPayload" | "iv" | "tag" | "keyId"> {
    const iv = randomBytes(12);
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.configuration.JOURNAL_ENCRYPTION_KEY,
      iv,
    );
    cipher.setAAD(this.aad(ownerAccountId, revision));
    const encryptedPayload = Buffer.concat([
      cipher.update(JSON.stringify(stateSchema.parse(value)), "utf8"),
      cipher.final(),
    ]);
    return {
      encryptedPayload,
      iv,
      tag: cipher.getAuthTag(),
      keyId: this.configuration.JOURNAL_ENCRYPTION_KEY_ID,
    };
  }

  decrypt(document: CompanionStateDocument): CompanionStateValue {
    try {
      const decipher = createDecipheriv(
        "aes-256-gcm",
        this.configuration.JOURNAL_ENCRYPTION_KEY,
        buffer(document.iv),
      );
      decipher.setAAD(this.aad(document.ownerAccountId, document.revision));
      decipher.setAuthTag(buffer(document.tag));
      return stateSchema.parse(
        JSON.parse(
          Buffer.concat([
            decipher.update(buffer(document.encryptedPayload)),
            decipher.final(),
          ]).toString("utf8"),
        ),
      );
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }
}

@Injectable()
export class MongoCompanionStateRepository
  implements CompanionStateRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly collection: Collection<CompanionStateDocument>;

  constructor(configuration: Configuration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    this.collection = this.client
      .db(configuration.MONGODB_DATABASE)
      .collection<CompanionStateDocument>("companion_states");
  }

  private async values(): Promise<Collection<CompanionStateDocument>> {
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

  async find(ownerAccountId: string) {
    return (await this.values()).findOne({ ownerAccountId });
  }

  async save(document: CompanionStateDocument, expectedRevision: number) {
    const values = await this.values();
    if (expectedRevision === 0) {
      try {
        await values.insertOne(document);
        return true;
      } catch (error) {
        if ((error as { code?: number }).code === 11_000) return false;
        throw error;
      }
    }
    const result = await values.updateOne(
      { ownerAccountId: document.ownerAccountId, revision: expectedRevision },
      {
        $set: {
          revision: document.revision,
          encryptedPayload: document.encryptedPayload,
          iv: document.iv,
          tag: document.tag,
          keyId: document.keyId,
          updatedAt: document.updatedAt,
        },
      },
    );
    return result.matchedCount === 1;
  }
}

@Injectable()
export class CompanionStateService {
  constructor(
    @Inject(REPOSITORY)
    private readonly repository: CompanionStateRepository,
    @Inject(CRYPTO) private readonly crypto: CompanionStateCrypto,
    @Inject(CLOCK) private readonly clock: CompanionStateClock,
  ) {}

  private async mutate<T>(
    ownerAccountId: string,
    change: (state: CompanionStateValue, now: Date) => T,
  ): Promise<{ state: CompanionStateValue; revision: number; result: T }> {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const document = await this.repository.find(ownerAccountId);
      const state = document ? this.crypto.decrypt(document) : emptyState();
      const now = this.clock.now();
      const result = change(state, now);
      const revision = (document?.revision ?? 0) + 1;
      const saved = await this.repository.save(
        {
          _id: document?._id ?? randomUUID(),
          ownerAccountId,
          revision,
          ...this.crypto.encrypt(ownerAccountId, revision, state),
          updatedAt: now,
        },
        revision - 1,
      );
      if (saved) return { state, revision, result };
    }
    throw new ConflictException();
  }

  async get(request: CompanionStateRequest) {
    const ownerAccountId = owner(request);
    const document = await this.repository.find(ownerAccountId);
    return document
      ? { ...this.crypto.decrypt(document), revision: document.revision }
      : { ...emptyState(), revision: 0 };
  }

  async update(request: CompanionStateRequest) {
    const ownerAccountId = owner(request);
    const patch = parse(patchSchema, request.body);
    const today = this.clock.now();
    for (const date of [patch.focus?.chosenOn, patch.chapter?.startedOn]) {
      if (date === undefined) continue;
      const offset = daysFromUtcToday(date, today);
      if (offset < -400 || offset > 2) throw new BadRequestException();
    }
    const { state, revision } = await this.mutate(
      ownerAccountId,
      (current, now) => {
        if (patch.focus !== undefined) current.focus = patch.focus;
        if (patch.guide !== undefined) current.ui.guide = patch.guide;
        if (patch.chapter) {
          const previous = current.chapters.find(
            (chapter) => chapter.startedOn === patch.chapter?.startedOn,
          );
          const merged = {
            ...previous,
            ...patch.chapter,
            updatedAt: now.toISOString(),
          };
          current.chapters = [
            ...current.chapters.filter((chapter) => chapter !== previous),
            merged,
          ]
            .sort((left, right) =>
              left.startedOn.localeCompare(right.startedOn),
            )
            .slice(-MAX_CHAPTERS);
        }
        if (patch.seenAdd) {
          const seen = new Set(current.ui.seen);
          for (const key of patch.seenAdd) {
            seen.delete(key);
            seen.add(key);
          }
          current.ui.seen = [...seen].slice(-MAX_SEEN);
        }
      },
    );
    return { ...state, revision };
  }

  async createCommitment(request: CompanionStateRequest) {
    const ownerAccountId = owner(request);
    const body = parse(createCommitmentSchema, request.body);
    const offset = daysFromUtcToday(body.dueOn, this.clock.now());
    if (offset < -2 || offset > 8) throw new BadRequestException();
    const { result } = await this.mutate(ownerAccountId, (current, now) => {
      if (
        current.commitments.filter((commitment) => commitment.status === "OPEN")
          .length >= MAX_OPEN_COMMITMENTS
      )
        throw new ConflictException({ code: "COMMITMENT_LIMIT" });
      const commitment: Commitment = {
        id: randomUUID(),
        ...body,
        status: "OPEN",
        createdAt: now.toISOString(),
        resolvedAt: null,
      };
      current.commitments.push(commitment);
      if (current.commitments.length > MAX_COMMITMENTS) {
        const oldestResolved = current.commitments.findIndex(
          (candidate) => candidate.status !== "OPEN",
        );
        current.commitments.splice(oldestResolved, 1);
      }
      return commitment;
    });
    return result;
  }

  async resolveCommitment(
    request: CompanionStateRequest,
    commitmentId: string,
  ) {
    const ownerAccountId = owner(request);
    if (!uuid.safeParse(commitmentId).success) throw new BadRequestException();
    const { status } = parse(resolveCommitmentSchema, request.body);
    const { result } = await this.mutate(ownerAccountId, (current, now) => {
      const commitment = current.commitments.find(
        (candidate) => candidate.id === commitmentId,
      );
      if (!commitment) throw new NotFoundException();
      if (commitment.status !== "OPEN")
        throw new ConflictException({ code: "COMMITMENT_RESOLVED" });
      commitment.status = status;
      commitment.resolvedAt = now.toISOString();
      return { ...commitment };
    });
    return result;
  }
}

@Controller("api/v1/companion-state")
export class CompanionStateController {
  constructor(private readonly service: CompanionStateService) {}

  @Get()
  get(@Req() request: CompanionStateRequest) {
    return this.service.get(request);
  }

  @Patch()
  update(@Req() request: CompanionStateRequest) {
    return this.service.update(request);
  }

  @Post("commitments")
  createCommitment(@Req() request: CompanionStateRequest) {
    return this.service.createCommitment(request);
  }

  @Patch("commitments/:commitmentId")
  resolveCommitment(
    @Req() request: CompanionStateRequest,
    @Param("commitmentId") commitmentId: string,
  ) {
    return this.service.resolveCommitment(request, commitmentId);
  }
}

export const registerCompanionStateModule = (
  configuration: Configuration,
  dependencies: CompanionStateDependencies = {},
): DynamicModule => {
  const repository: Provider = dependencies.repository
    ? { provide: REPOSITORY, useValue: dependencies.repository }
    : {
        provide: REPOSITORY,
        useFactory: () => new MongoCompanionStateRepository(configuration),
      };
  return {
    module: CompanionStateModule,
    controllers: [CompanionStateController],
    providers: [
      CompanionStateService,
      repository,
      {
        provide: CRYPTO,
        useFactory: () => new CompanionStateCrypto(configuration),
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
export class CompanionStateModule {}
