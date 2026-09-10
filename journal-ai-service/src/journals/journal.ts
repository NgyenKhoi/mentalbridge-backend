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
  InternalServerErrorException,
  Module,
  NotFoundException,
  Param,
  Patch,
  Post,
  Query,
  Req,
  Res,
  UnauthorizedException,
} from "@nestjs/common";
import type { DynamicModule, OnApplicationShutdown } from "@nestjs/common";
import { Binary, MongoClient, type Collection, type Filter } from "mongodb";
import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { z } from "zod";
import {
  loadConfiguration,
  type ServiceConfiguration as Configuration,
} from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const STORE = "JOURNAL_STORE";
const ENCRYPTION = "JOURNAL_ENCRYPTION";
const MAX_COMMANDS = 32;

export interface Request extends AuthenticatedRequest {
  body: unknown;
}
export interface EncryptedContent {
  ciphertext: Buffer | Binary;
  iv: Buffer | Binary;
  tag: Buffer | Binary;
  algorithm: "AES-256-GCM";
  keyId: string;
  encryptedAt: Date;
}
export interface Revision {
  revision: number;
  createdAt: Date;
  content: EncryptedContent;
  contentByteLength: number;
  contentHash: string;
  analysisInvalidatedAt: Date | null;
}
export interface MutationCommand {
  keyHash: string;
  fingerprint: string;
  operation: "create" | "revise" | "delete";
  resultRevision?: number;
  recordedAt: Date;
}
export interface Entry {
  _id: string;
  ownerAccountId: string;
  clientEntryId: string;
  currentRevision: number;
  occurredAt: Date;
  createdAt: Date;
  updatedAt: Date;
  deleted: boolean;
  deletedAt?: Date | null;
  deletedBy?: string;
  tombstoneReason?: "owner_deleted" | "retention_expired" | null;
  tags: string[];
  analysisState: "not_requested" | "current" | "stale";
  revisions: Revision[];
  commands: MutationCommand[];
  cursor: { sortOccurredAt: Date; sortCreatedAt: Date; entryId: string };
}
export interface JournalCursor {
  occurredAt: Date;
  createdAt: Date;
  id: string;
}
export interface JournalStore {
  create(entry: Entry): Promise<Entry>;
  find(owner: string, id: string): Promise<Entry | null>;
  findByClientId(owner: string, id: string): Promise<Entry | null>;
  findByCommand(owner: string, keyHash: string): Promise<Entry | null>;
  list(
    owner: string,
    limit: number,
    cursor?: JournalCursor,
    includeDeleted?: boolean,
  ): Promise<{ rows: Entry[]; hasMore: boolean }>;
  revise(
    owner: string,
    id: string,
    expectedRevision: number,
    revision: Revision,
    tags: string[],
    command: MutationCommand,
  ): Promise<Entry | null>;
  tombstone(
    owner: string,
    id: string,
    deletedAt: Date,
    command: MutationCommand,
  ): Promise<Entry | null>;
}

const uuid = z.uuid();
const tagsSchema = z
  .array(z.string().trim().min(1).max(40))
  .max(20)
  .refine((tags) => new Set(tags).size === tags.length);
const createSchema = z
  .object({
    clientEntryId: uuid,
    occurredAt: z.iso.datetime({ offset: true }),
    content: z.object({ text: z.string().min(1).max(12_000) }).strict(),
    tags: tagsSchema.default([]),
  })
  .strict();
const reviseSchema = z
  .object({
    content: z.object({ text: z.string().min(1).max(12_000) }).strict(),
    tags: tagsSchema.optional(),
  })
  .strict();
const listSchema = z
  .object({
    limit: z.coerce.number().int().min(1).max(50).default(20),
    cursor: z.string().min(1).max(512).optional(),
    includeDeleted: z.enum(["true", "false"]).default("false"),
  })
  .strict();
const idempotencyKeySchema = z.string().min(16).max(128);
const revisionHeaderSchema = z.coerce.number().int().min(1);

const header = (request: Request, name: string): string | undefined => {
  const value = request.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
};
const owner = (request: Request): string => {
  const value = request.principal?.accountId;
  if (!value || !uuid.safeParse(value).success)
    throw new UnauthorizedException();
  return value;
};
const requiredHeader = (
  request: Request,
  name: string,
  schema: z.ZodType<string | number>,
): string | number => {
  const result = schema.safeParse(header(request, name));
  if (!result.success) throw new BadRequestException();
  return result.data;
};
const decodeCursor = (value: string | undefined): JournalCursor | undefined => {
  if (!value) return undefined;
  try {
    const parsed = z
      .object({
        occurredAt: z.iso.datetime(),
        createdAt: z.iso.datetime(),
        id: uuid,
      })
      .strict()
      .parse(JSON.parse(Buffer.from(value, "base64url").toString("utf8")));
    return {
      occurredAt: new Date(parsed.occurredAt),
      createdAt: new Date(parsed.createdAt),
      id: parsed.id,
    };
  } catch {
    throw new BadRequestException();
  }
};
const encodeCursor = (entry: Entry): string =>
  Buffer.from(
    JSON.stringify({
      occurredAt: entry.occurredAt.toISOString(),
      createdAt: entry.createdAt.toISOString(),
      id: entry._id,
    }),
  ).toString("base64url");

const binaryBuffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

@Injectable()
export class JournalEncryption {
  constructor(private readonly configuration: Configuration) {}
  encrypt(
    ownerAccountId: string,
    entryId: string,
    revision: number,
    text: string,
  ): EncryptedContent {
    const iv = randomBytes(12);
    const encryptedAt = new Date();
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.configuration.JOURNAL_ENCRYPTION_KEY,
      iv,
    );
    cipher.setAAD(
      Buffer.from(`${ownerAccountId}:${entryId}:${String(revision)}`),
    );
    const ciphertext = Buffer.concat([
      cipher.update(text, "utf8"),
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
  decrypt(ownerAccountId: string, entryId: string, revision: Revision): string {
    if (revision.content.keyId !== this.configuration.JOURNAL_ENCRYPTION_KEY_ID)
      throw new InternalServerErrorException();
    try {
      const decipher = createDecipheriv(
        "aes-256-gcm",
        this.configuration.JOURNAL_ENCRYPTION_KEY,
        binaryBuffer(revision.content.iv),
      );
      decipher.setAAD(
        Buffer.from(
          `${ownerAccountId}:${entryId}:${String(revision.revision)}`,
        ),
      );
      decipher.setAuthTag(binaryBuffer(revision.content.tag));
      return Buffer.concat([
        decipher.update(binaryBuffer(revision.content.ciphertext)),
        decipher.final(),
      ]).toString("utf8");
    } catch {
      throw new InternalServerErrorException();
    }
  }
  keyHash(key: string): string {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(key)
      .digest("base64url");
  }
  fingerprint(value: unknown): string {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(JSON.stringify(value))
      .digest("base64url");
  }
  contentHash(text: string): string {
    return createHmac("sha256", this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY)
      .update(text)
      .digest("base64");
  }
}

@Injectable()
export class MongoJournalStore implements JournalStore, OnApplicationShutdown {
  private readonly client: MongoClient;
  private readonly collection: Collection<Entry>;
  constructor(configuration: Configuration = loadConfiguration()) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    this.collection = this.client
      .db(configuration.MONGODB_DATABASE)
      .collection<Entry>("journal_entries");
  }
  private async entries() {
    await this.client.connect();
    return this.collection;
  }
  async onApplicationShutdown() {
    await this.client.close();
  }
  async create(entry: Entry) {
    try {
      await (await this.entries()).insertOne(entry);
      return entry;
    } catch (error) {
      if ((error as { code?: number }).code === 11_000)
        throw new ConflictException();
      throw error;
    }
  }
  async find(ownerAccountId: string, id: string) {
    return (await this.entries()).findOne({ _id: id, ownerAccountId });
  }
  async findByClientId(ownerAccountId: string, id: string) {
    return (await this.entries()).findOne({
      clientEntryId: id,
      ownerAccountId,
    });
  }
  async findByCommand(ownerAccountId: string, keyHash: string) {
    return (await this.entries()).findOne({
      ownerAccountId,
      "commands.keyHash": keyHash,
    });
  }
  async list(
    ownerAccountId: string,
    limit: number,
    cursor?: JournalCursor,
    includeDeleted = false,
  ) {
    const cursorFilter: Filter<Entry> = cursor
      ? {
          $or: [
            { occurredAt: { $lt: cursor.occurredAt } },
            {
              occurredAt: cursor.occurredAt,
              createdAt: { $lt: cursor.createdAt },
            },
            {
              occurredAt: cursor.occurredAt,
              createdAt: cursor.createdAt,
              _id: { $gt: cursor.id },
            },
          ],
        }
      : {};
    const rows = await (
      await this.entries()
    )
      .find({
        ownerAccountId,
        ...(includeDeleted ? {} : { deleted: false }),
        ...cursorFilter,
      })
      .sort({ occurredAt: -1, createdAt: -1, _id: 1 })
      .limit(limit + 1)
      .toArray();
    return { rows: rows.slice(0, limit), hasMore: rows.length > limit };
  }
  async revise(
    ownerAccountId: string,
    id: string,
    expectedRevision: number,
    revision: Revision,
    tags: string[],
    command: MutationCommand,
  ) {
    return (await this.entries()).findOneAndUpdate(
      {
        _id: id,
        ownerAccountId,
        deleted: false,
        currentRevision: expectedRevision,
        "commands.keyHash": { $ne: command.keyHash },
      },
      {
        $set: { tags, updatedAt: command.recordedAt, analysisState: "stale" },
        $inc: { currentRevision: 1 },
        $push: {
          revisions: revision,
          commands: { $each: [command], $slice: -MAX_COMMANDS },
        },
      },
      { returnDocument: "after" },
    );
  }
  async tombstone(
    ownerAccountId: string,
    id: string,
    deletedAt: Date,
    command: MutationCommand,
  ) {
    return (await this.entries()).findOneAndUpdate(
      {
        _id: id,
        ownerAccountId,
        deleted: false,
        "commands.keyHash": { $ne: command.keyHash },
      },
      {
        $set: {
          deleted: true,
          deletedAt,
          deletedBy: ownerAccountId,
          tombstoneReason: "owner_deleted",
          updatedAt: deletedAt,
        },
        $push: { commands: { $each: [command], $slice: -MAX_COMMANDS } },
      },
      { returnDocument: "after" },
    );
  }
}

@Injectable()
export class JournalService {
  constructor(
    @Inject(STORE) private readonly store: JournalStore,
    @Inject(ENCRYPTION) private readonly encryption: JournalEncryption,
  ) {}
  private command(
    request: Request,
    operation: MutationCommand["operation"],
    value: unknown,
    resultRevision?: number,
  ): MutationCommand {
    const key = requiredHeader(
      request,
      "idempotency-key",
      idempotencyKeySchema,
    );
    return {
      keyHash: this.encryption.keyHash(String(key)),
      fingerprint: this.encryption.fingerprint({ operation, value }),
      operation,
      ...(resultRevision === undefined ? {} : { resultRevision }),
      recordedAt: new Date(),
    };
  }
  private replay(
    entry: Entry,
    command: MutationCommand,
  ): MutationCommand | undefined {
    const previous = entry.commands.find(
      (item) => item.keyHash === command.keyHash,
    );
    if (!previous) return undefined;
    if (
      previous.fingerprint !== command.fingerprint ||
      previous.operation !== command.operation
    )
      throw new ConflictException();
    return previous;
  }
  private revision(entry: Entry, number = entry.currentRevision): Revision {
    const revision = entry.revisions.find((item) => item.revision === number);
    if (!revision) throw new InternalServerErrorException();
    return revision;
  }
  private output(
    entry: Entry,
    revisionNumber = entry.currentRevision,
    updatedAt?: Date,
  ) {
    const revision = this.revision(entry, revisionNumber);
    const text = this.encryption.decrypt(
      entry.ownerAccountId,
      entry._id,
      revision,
    );
    return {
      id: entry._id,
      ownerAccountId: entry.ownerAccountId,
      currentRevision: revisionNumber,
      occurredAt: entry.occurredAt.toISOString(),
      createdAt: entry.createdAt.toISOString(),
      updatedAt: (updatedAt ?? entry.updatedAt).toISOString(),
      deleted: false,
      tags: entry.tags,
      encryption: {
        algorithm: revision.content.algorithm,
        keyId: revision.content.keyId,
        encryptedAt: revision.content.encryptedAt.toISOString(),
      },
      analysisState: entry.analysisState,
      content: { text, byteLength: revision.contentByteLength },
    };
  }
  async create(request: Request) {
    const parsed = createSchema.safeParse(request.body);
    if (!parsed.success) throw new BadRequestException();
    const ownerAccountId = owner(request);
    const command = this.command(request, "create", parsed.data, 1);
    const previousByCommand = await this.store.findByCommand(
      ownerAccountId,
      command.keyHash,
    );
    if (previousByCommand) {
      const replay = this.replay(previousByCommand, command);
      return this.output(
        previousByCommand,
        replay?.resultRevision,
        replay?.recordedAt,
      );
    }
    if (
      await this.store.findByClientId(ownerAccountId, parsed.data.clientEntryId)
    )
      throw new ConflictException();
    const now = command.recordedAt;
    const id = randomUUID();
    const text = parsed.data.content.text;
    const revision: Revision = {
      revision: 1,
      createdAt: now,
      content: this.encryption.encrypt(ownerAccountId, id, 1, text),
      contentByteLength: Buffer.byteLength(text, "utf8"),
      contentHash: this.encryption.contentHash(text),
      analysisInvalidatedAt: null,
    };
    const entry: Entry = {
      _id: id,
      ownerAccountId,
      clientEntryId: parsed.data.clientEntryId,
      currentRevision: 1,
      occurredAt: new Date(parsed.data.occurredAt),
      createdAt: now,
      updatedAt: now,
      deleted: false,
      deletedAt: null,
      tombstoneReason: null,
      tags: parsed.data.tags,
      analysisState: "not_requested",
      revisions: [revision],
      commands: [command],
      cursor: {
        sortOccurredAt: new Date(parsed.data.occurredAt),
        sortCreatedAt: now,
        entryId: id,
      },
    };
    try {
      return this.output(await this.store.create(entry));
    } catch (error) {
      if (!(error instanceof ConflictException)) throw error;
      const raced = await this.store.findByCommand(
        ownerAccountId,
        command.keyHash,
      );
      if (!raced) throw error;
      const replay = this.replay(raced, command);
      return this.output(raced, replay?.resultRevision, replay?.recordedAt);
    }
  }
  async detail(request: Request, id: string) {
    if (!uuid.safeParse(id).success) throw new BadRequestException();
    const entry = await this.store.find(owner(request), id);
    if (!entry || entry.deleted) throw new NotFoundException();
    return this.output(entry);
  }
  async list(request: Request, query: Record<string, string | undefined>) {
    const parsed = listSchema.safeParse(query);
    if (!parsed.success) throw new BadRequestException();
    const result = await this.store.list(
      owner(request),
      parsed.data.limit,
      decodeCursor(parsed.data.cursor),
      parsed.data.includeDeleted === "true",
    );
    const visibleRows = result.rows.filter((entry) => !entry.deleted);
    const lastRow = result.rows.at(-1);
    return {
      items: visibleRows.map((entry) => {
        const value = this.output(entry);
        return {
          ...value,
          content: {
            preview: Array.from(value.content.text).slice(0, 160).join(""),
            byteLength: value.content.byteLength,
          },
        };
      }),
      page: {
        limit: parsed.data.limit,
        hasMore: result.hasMore,
        ...(result.hasMore && lastRow
          ? { nextCursor: encodeCursor(lastRow) }
          : {}),
      },
    };
  }
  async revise(request: Request, id: string) {
    if (!uuid.safeParse(id).success) throw new BadRequestException();
    const parsed = reviseSchema.safeParse(request.body);
    if (!parsed.success) throw new BadRequestException();
    const ownerAccountId = owner(request);
    const expectedRevision = Number(
      requiredHeader(request, "if-match-revision", revisionHeaderSchema),
    );
    const command = this.command(request, "revise", {
      id,
      expectedRevision,
      body: parsed.data,
    });
    const entry = await this.store.find(ownerAccountId, id);
    if (!entry || entry.deleted) throw new NotFoundException();
    const replay = this.replay(entry, command);
    if (replay)
      return this.output(entry, replay.resultRevision, replay.recordedAt);
    if (entry.currentRevision !== expectedRevision)
      throw new HttpException("", HttpStatus.PRECONDITION_FAILED);
    const now = command.recordedAt;
    const nextRevision = expectedRevision + 1;
    const text = parsed.data.content.text;
    const revision: Revision = {
      revision: nextRevision,
      createdAt: now,
      content: this.encryption.encrypt(ownerAccountId, id, nextRevision, text),
      contentByteLength: Buffer.byteLength(text, "utf8"),
      contentHash: this.encryption.contentHash(text),
      analysisInvalidatedAt: now,
    };
    const updated = await this.store.revise(
      ownerAccountId,
      id,
      expectedRevision,
      revision,
      parsed.data.tags ?? entry.tags,
      { ...command, resultRevision: nextRevision },
    );
    if (updated) return this.output(updated);
    const raced = await this.store.find(ownerAccountId, id);
    if (!raced || raced.deleted) throw new NotFoundException();
    const racedReplay = this.replay(raced, command);
    if (racedReplay)
      return this.output(
        raced,
        racedReplay.resultRevision,
        racedReplay.recordedAt,
      );
    throw new HttpException("", HttpStatus.PRECONDITION_FAILED);
  }
  async remove(request: Request, id: string) {
    if (!uuid.safeParse(id).success) throw new BadRequestException();
    const ownerAccountId = owner(request);
    const command = this.command(request, "delete", { id });
    const entry = await this.store.find(ownerAccountId, id);
    if (!entry) throw new NotFoundException();
    const replay = this.replay(entry, command);
    if (replay)
      return {
        id,
        ownerAccountId,
        deleted: true,
        deletedAt: replay.recordedAt.toISOString(),
      };
    if (entry.deleted) throw new NotFoundException();
    const updated = await this.store.tombstone(
      ownerAccountId,
      id,
      command.recordedAt,
      command,
    );
    if (!updated) {
      const raced = await this.store.find(ownerAccountId, id);
      const racedReplay = raced ? this.replay(raced, command) : undefined;
      if (racedReplay)
        return {
          id,
          ownerAccountId,
          deleted: true,
          deletedAt: racedReplay.recordedAt.toISOString(),
        };
      throw new NotFoundException();
    }
    return {
      id,
      ownerAccountId,
      deleted: true,
      deletedAt: command.recordedAt.toISOString(),
    };
  }
}

@Controller("api/v1/journals")
export class JournalController {
  constructor(
    @Inject(JournalService) private readonly service: JournalService,
  ) {}
  @Post() async create(
    @Req() request: Request,
    @Res({ passthrough: true })
    response: {
      setHeader(name: string, value: string): void;
    },
  ) {
    const entry = await this.service.create(request);
    response.setHeader("Location", `/api/v1/journals/${entry.id}`);
    return entry;
  }
  @Get() list(
    @Req() request: Request,
    @Query() query: Record<string, string | undefined>,
  ) {
    return this.service.list(request, query);
  }
  @Get(":journalId") detail(
    @Req() request: Request,
    @Param("journalId") id: string,
  ) {
    return this.service.detail(request, id);
  }
  @Patch(":journalId") revise(
    @Req() request: Request,
    @Param("journalId") id: string,
  ) {
    return this.service.revise(request, id);
  }
  @Delete(":journalId") remove(
    @Req() request: Request,
    @Param("journalId") id: string,
  ) {
    return this.service.remove(request, id);
  }
}
export const registerJournalModule = (
  configuration: Configuration,
): DynamicModule => ({
  module: JournalModule,
  controllers: [JournalController],
  providers: [
    JournalService,
    {
      provide: ENCRYPTION,
      useFactory: () => new JournalEncryption(configuration),
    },
    { provide: STORE, useFactory: () => new MongoJournalStore(configuration) },
  ],
});
@Module({})
export class JournalModule {
  readonly moduleName = "journal";
}
