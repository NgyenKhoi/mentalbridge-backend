import {
  BadRequestException,
  ConflictException,
  Controller,
  Delete,
  Get,
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
  UnauthorizedException,
} from "@nestjs/common";
import type { DynamicModule } from "@nestjs/common";
import { MongoClient, type Collection } from "mongodb";
import { randomUUID } from "node:crypto";
import { z } from "zod";
import {
  loadConfiguration,
  type ServiceConfiguration as Configuration,
} from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

export interface Request extends AuthenticatedRequest {
  body: unknown;
}
export interface Revision {
  revision: number;
  createdAt: Date;
  content: string;
  tags: string[];
}
export interface Entry {
  _id: string;
  ownerAccountId: string;
  clientEntryId: string;
  occurredAt: Date;
  createdAt: Date;
  updatedAt: Date;
  deleted: boolean;
  deletedAt: Date | null;
  currentRevision: number;
  revisions: Revision[];
  lastCommandKeys: string[];
}
export interface JournalStore {
  create(entry: Entry): Promise<Entry>;
  find(owner: string, id: string): Promise<Entry | null>;
  findByClientId(owner: string, id: string): Promise<Entry | null>;
  list(
    owner: string,
    limit: number,
    cursor?: string,
    includeDeleted?: boolean,
  ): Promise<{ rows: Entry[]; hasMore: boolean }>;
  update(
    owner: string,
    id: string,
    update: Partial<Entry>,
  ): Promise<Entry | null>;
}

const uuid = z.uuid();
const createSchema = z
  .object({
    clientEntryId: uuid,
    occurredAt: z.iso.datetime(),
    content: z.string().min(1).max(12000),
    tags: z.array(z.string().min(1).max(40)).max(20).default([]),
  })
  .strict();
const reviseSchema = z
  .object({
    content: z.string().min(1).max(12000),
    tags: z.array(z.string().min(1).max(40)).max(20).optional(),
  })
  .strict();
const header = (request: Request, name: string) => {
  const value = request.headers[name.toLowerCase()];
  return typeof value === "string" ? value : undefined;
};
const owner = (request: Request): string => {
  const value = request.principal?.accountId;
  if (!value || !uuid.safeParse(value).success)
    throw new UnauthorizedException("Authenticated owner is required");
  return value;
};
const commandKey = (request: Request) => header(request, "idempotency-key");
const output = (entry: Entry) => {
  const revision = entry.revisions.at(-1);
  if (!revision)
    throw new InternalServerErrorException("Journal revision is missing");
  return {
    id: entry._id,
    ownerAccountId: entry.ownerAccountId,
    clientEntryId: entry.clientEntryId,
    currentRevision: entry.currentRevision,
    occurredAt: entry.occurredAt.toISOString(),
    createdAt: entry.createdAt.toISOString(),
    updatedAt: entry.updatedAt.toISOString(),
    deleted: entry.deleted,
    content: revision.content,
    tags: revision.tags,
  };
};

@Injectable()
export class MongoJournalStore implements JournalStore {
  private readonly client: MongoClient;
  private readonly collection: Collection<Entry>;
  constructor(configuration: Configuration = loadConfiguration()) {
    this.client = new MongoClient(configuration.MONGODB_URI);
    this.collection = this.client
      .db(configuration.MONGODB_DATABASE)
      .collection<Entry>("journal_entries");
  }
  private async db() {
    await this.client.connect();
    return this.collection;
  }
  async create(entry: Entry) {
    try {
      await (await this.db()).insertOne(entry);
      return entry;
    } catch (error) {
      if ((error as { code?: number }).code === 11000)
        throw new ConflictException("Journal entry already exists");
      throw error;
    }
  }
  async find(ownerAccountId: string, id: string) {
    return (await this.db()).findOne({ _id: id, ownerAccountId });
  }
  async findByClientId(ownerAccountId: string, id: string) {
    return (await this.db()).findOne({ clientEntryId: id, ownerAccountId });
  }
  async list(
    ownerAccountId: string,
    limit: number,
    cursor?: string,
    includeDeleted = false,
  ) {
    const filter = {
      ownerAccountId,
      ...(includeDeleted ? {} : { deleted: false }),
      ...(cursor ? { _id: { $lt: cursor } } : {}),
    };
    const rows = await (
      await this.db()
    )
      .find(filter)
      .sort({ _id: -1 })
      .limit(limit + 1)
      .toArray();
    return { rows: rows.slice(0, limit), hasMore: rows.length > limit };
  }
  async update(ownerAccountId: string, id: string, update: Partial<Entry>) {
    return (await this.db()).findOneAndUpdate(
      { _id: id, ownerAccountId },
      { $set: update },
      { returnDocument: "after" },
    );
  }
}

@Injectable()
export class JournalService {
  constructor(@Inject("JOURNAL_STORE") private readonly store: JournalStore) {}
  async create(request: Request) {
    const parsed = createSchema.safeParse(request.body);
    if (!parsed.success)
      throw new BadRequestException("Invalid journal payload");
    const ownerAccountId = owner(request);
    const existing = await this.store.findByClientId(
      ownerAccountId,
      parsed.data.clientEntryId,
    );
    if (existing) return output(existing);
    const now = new Date();
    const key = commandKey(request);
    const entry: Entry = {
      _id: randomUUID(),
      ownerAccountId,
      clientEntryId: parsed.data.clientEntryId,
      occurredAt: new Date(parsed.data.occurredAt),
      createdAt: now,
      updatedAt: now,
      deleted: false,
      deletedAt: null,
      currentRevision: 1,
      revisions: [
        {
          revision: 1,
          createdAt: now,
          content: parsed.data.content,
          tags: parsed.data.tags,
        },
      ],
      lastCommandKeys: key ? [key] : [],
    };
    return output(await this.store.create(entry));
  }
  async detail(request: Request, id: string) {
    const entry = await this.store.find(owner(request), id);
    if (!entry || entry.deleted)
      throw new NotFoundException("Journal entry not found");
    return output(entry);
  }
  async list(
    request: Request,
    query: { limit?: string; cursor?: string; includeDeleted?: string },
  ) {
    const limit = Math.min(Math.max(Number(query.limit ?? 20) || 20, 1), 50);
    const result = await this.store.list(
      owner(request),
      limit,
      query.cursor,
      query.includeDeleted === "true",
    );
    const lastRow = result.rows.at(-1);
    return {
      items: result.rows.map(output),
      page: {
        limit,
        hasMore: result.hasMore,
        ...(result.hasMore && lastRow ? { nextCursor: lastRow._id } : {}),
      },
    };
  }
  async revise(request: Request, id: string) {
    const parsed = reviseSchema.safeParse(request.body);
    if (!parsed.success)
      throw new BadRequestException("Invalid journal payload");
    const ownerAccountId = owner(request);
    const entry = await this.store.find(ownerAccountId, id);
    if (!entry || entry.deleted)
      throw new NotFoundException("Journal entry not found");
    const key = commandKey(request);
    if (key && entry.lastCommandKeys.includes(key)) return output(entry);
    const now = new Date();
    const previous = entry.revisions.at(-1);
    if (!previous)
      throw new InternalServerErrorException("Journal revision is missing");
    const revision: Revision = {
      revision: entry.currentRevision + 1,
      createdAt: now,
      content: parsed.data.content,
      tags: parsed.data.tags ?? previous.tags,
    };
    const updated = await this.store.update(ownerAccountId, id, {
      currentRevision: revision.revision,
      revisions: [...entry.revisions, revision],
      updatedAt: now,
      ...(key ? { lastCommandKeys: [...entry.lastCommandKeys, key] } : {}),
    });
    if (!updated)
      throw new ConflictException(
        "Journal changed before revision could be saved",
      );
    return output(updated);
  }
  async remove(request: Request, id: string) {
    const ownerAccountId = owner(request);
    const entry = await this.store.find(ownerAccountId, id);
    if (!entry) throw new NotFoundException("Journal entry not found");
    const key = commandKey(request);
    if (key && entry.lastCommandKeys.includes(key))
      return {
        id: entry._id,
        ownerAccountId,
        deleted: true,
        deletedAt: entry.deletedAt?.toISOString(),
      };
    const deletedAt = new Date();
    const updated = await this.store.update(ownerAccountId, id, {
      deleted: true,
      deletedAt,
      updatedAt: deletedAt,
      ...(key ? { lastCommandKeys: [...entry.lastCommandKeys, key] } : {}),
    });
    if (!updated)
      throw new ConflictException(
        "Journal changed before deletion could be saved",
      );
    return {
      id: updated._id,
      ownerAccountId,
      deleted: true,
      deletedAt: deletedAt.toISOString(),
    };
  }
}

@Controller("api/v1/journals")
export class JournalController {
  constructor(
    @Inject(JournalService) private readonly service: JournalService,
  ) {}

  @Post()
  create(@Req() request: Request) {
    return this.service.create(request);
  }

  @Get()
  list(
    @Req() request: Request,
    @Query()
    query: { limit?: string; cursor?: string; includeDeleted?: string },
  ) {
    return this.service.list(request, query);
  }

  @Get(":journalId")
  detail(@Req() request: Request, @Param("journalId") id: string) {
    return this.service.detail(request, id);
  }

  @Patch(":journalId")
  revise(@Req() request: Request, @Param("journalId") id: string) {
    return this.service.revise(request, id);
  }

  @Delete(":journalId")
  remove(@Req() request: Request, @Param("journalId") id: string) {
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
      provide: "JOURNAL_STORE",
      useFactory: () => new MongoJournalStore(configuration),
    },
  ],
});

@Module({})
export class JournalModule {
  readonly moduleName = "journal";
}
