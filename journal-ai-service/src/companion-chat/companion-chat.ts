import {
  BadRequestException,
  ConflictException,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpException,
  Inject,
  Injectable,
  Module,
  NotFoundException,
  Param,
  Post,
  Req,
  type DynamicModule,
  type OnApplicationShutdown,
} from "@nestjs/common";
import {
  Binary,
  MongoClient,
  MongoServerError,
  type Collection,
} from "mongodb";
import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { z } from "zod";

import { CareConsentClient, type ConsentClient } from "../analysis/analysis.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { Entry, Revision } from "../journals/journal.js";
import {
  ConsultationEntitlementClient,
  type AiProviderId,
  type EntitlementClient,
  type EntitlementDecision,
  type ServicePlan,
} from "../model-routing/model-routing.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

const REPOSITORY = "COMPANION_CHAT_REPOSITORY";
const CONSENT = "COMPANION_CHAT_CONSENT";
const ENTITLEMENT = "COMPANION_CHAT_ENTITLEMENT";
const CONTEXT = "COMPANION_CHAT_CONTEXT";
const PROVIDER = "COMPANION_CHAT_PROVIDER";
const CLOCK = "COMPANION_CHAT_CLOCK";

const uuid = z.uuid();
const idempotencyKey = z.string().min(16).max(128);
const createConversationSchema = z
  .object({ title: z.string().trim().min(1).max(80).optional() })
  .strict();
const sendMessageSchema = z
  .object({
    message: z.string().trim().min(1).max(2_000),
    context: z
      .object({
        journalIds: z.array(uuid).max(3).default([]),
        longitudinalAnalysisId: uuid.optional(),
        includeCurrentSupportPlan: z.boolean().default(true),
        includeReminderContext: z.boolean().default(false),
      })
      .strict()
      .default({
        journalIds: [],
        includeCurrentSupportPlan: true,
        includeReminderContext: false,
      }),
  })
  .strict();

interface ChatRequest extends AuthenticatedRequest {
  readonly id?: string;
  readonly body: unknown;
}

export interface EncryptedChatText {
  ciphertext: Buffer | Binary;
  iv: Buffer | Binary;
  tag: Buffer | Binary;
  algorithm: "AES-256-GCM";
  keyId: string;
}

export interface ChatRoute {
  workload: "COMPANION_CHAT";
  servicePlan: ServicePlan;
  entitlementSource: EntitlementDecision["source"];
  entitlementPolicyVersion: string;
  entitlementVersion: number;
  routingPolicyVersion: string;
  providerApprovalVersion: string;
  provider: AiProviderId;
  model: string;
  promptVersion: "companion-chat-v1";
}

export interface ChatMessage {
  messageId: string;
  role: "USER" | "ASSISTANT";
  content: EncryptedChatText;
  createdAt: Date;
  route: ChatRoute | null;
  contextKinds: ("JOURNAL" | "SUPPORT_PLAN" | "REASSESSMENT")[];
}

export interface Conversation {
  _id: string;
  ownerAccountId: string;
  title: string;
  messages: ChatMessage[];
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date;
}

export interface QuotaSnapshot {
  plan: ServicePlan;
  policyVersion: "companion-quota-v1";
  remaining: number | null;
  resetAt: string;
  limitDisplayed: boolean;
}

export interface SendResult {
  conversationId: string;
  userMessageId: string;
  assistantMessageId: string;
  assistant: string;
  createdAt: string;
  quota: QuotaSnapshot;
}

export interface ChatCommand {
  _id: string;
  ownerAccountId: string;
  conversationId: string;
  keyHash: string;
  fingerprint: string;
  state: "RUNNING" | "SUCCEEDED" | "FAILED";
  response: SendResult | null;
  failureCode: string | null;
  failureStatus: number | null;
  failureTitle: string | null;
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date;
}

export interface ContextSelection {
  journalIds: string[];
  longitudinalAnalysisId?: string | undefined;
  includeCurrentSupportPlan: boolean;
  includeReminderContext: boolean;
}

export interface MinimizedContext {
  kinds: ("JOURNAL" | "SUPPORT_PLAN" | "REASSESSMENT")[];
  prompt: string;
}

export interface ProviderReply {
  message: string;
  inputTokens: number;
  outputTokens: number;
}

export interface ChatProvider {
  reply(
    userMessage: string,
    context: MinimizedContext,
    route: ChatRoute,
  ): Promise<ProviderReply>;
}

export interface ChatContextAssembler {
  assemble(
    ownerAccountId: string,
    bearer: string,
    correlationId: string,
    selection: ContextSelection,
  ): Promise<MinimizedContext>;
}

export interface CompanionClock {
  now(): Date;
}

export interface ChatRepository {
  createConversation(conversation: Conversation): Promise<Conversation>;
  listConversations(ownerAccountId: string): Promise<Conversation[]>;
  findConversation(
    ownerAccountId: string,
    conversationId: string,
  ): Promise<Conversation | null>;
  deleteConversation(
    ownerAccountId: string,
    conversationId: string,
  ): Promise<boolean>;
  findCommand(
    ownerAccountId: string,
    keyHash: string,
  ): Promise<ChatCommand | null>;
  beginCommand(command: ChatCommand): Promise<void>;
  reserve(
    ownerAccountId: string,
    reservationId: string,
    localDate: string,
    minuteBucket: string,
    plan: ServicePlan,
    answerLimit: number,
    rateLimit: number,
    tokenBudget: number,
    expiresAt: Date,
  ): Promise<{ successfulAnswers: number; usedTokens: number }>;
  complete(
    command: ChatCommand,
    userMessage: ChatMessage,
    assistantMessage: ChatMessage,
    result: SendResult,
    usedTokens: number,
  ): Promise<void>;
  fail(
    command: ChatCommand,
    code: string,
    status: number,
    title: string,
  ): Promise<void>;
}

export interface CompanionChatDependencies {
  repository?: ChatRepository;
  consentClient?: ConsentClient;
  entitlementClient?: EntitlementClient;
  contextAssembler?: ChatContextAssembler;
  provider?: ChatProvider;
  clock?: CompanionClock;
}

class ChatProblem extends HttpException {
  constructor(
    status: number,
    readonly code: string,
    readonly safeTitle: string,
  ) {
    super({ statusCode: status, status, code, title: safeTitle }, status);
  }
}

const buffer = (value: Buffer | Binary): Buffer =>
  Buffer.isBuffer(value) ? value : Buffer.from(value.buffer);

const requestOwner = (request: ChatRequest): string => {
  const value = request.principal?.accountId;
  if (!value || !uuid.safeParse(value).success)
    throw new ChatProblem(
      401,
      "AUTHENTICATION_REQUIRED",
      "Authentication is required",
    );
  return value;
};

const header = (request: ChatRequest, name: string): string | undefined => {
  const value = request.headers[name.toLowerCase()];
  return Array.isArray(value) ? value[0] : value;
};

const bearer = (request: ChatRequest): string => {
  const value = header(request, "authorization");
  if (!value?.startsWith("Bearer "))
    throw new ChatProblem(
      401,
      "AUTHENTICATION_REQUIRED",
      "Authentication is required",
    );
  return value.slice(7);
};

const digest = (key: Buffer, value: string): string =>
  createHmac("sha256", key).update(value).digest("base64url");

const localDate = (instant: Date, timezone: string): string => {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(instant);
  const pick = (type: string) =>
    parts.find((part) => part.type === type)?.value;
  const year = pick("year");
  const month = pick("month");
  const day = pick("day");
  if (!year || !month || !day) throw new Error("Unable to resolve local date");
  return `${year}-${month}-${day}`;
};

const nextLocalDay = (instant: Date, timezone: string): Date => {
  const today = localDate(instant, timezone);
  let low = instant.getTime();
  let high = low + 30 * 60 * 60 * 1_000;
  while (high - low > 1_000) {
    const middle = Math.floor((low + high) / 2);
    if (localDate(new Date(middle), timezone) === today) low = middle;
    else high = middle;
  }
  return new Date(high);
};

@Injectable()
export class ChatCrypto {
  constructor(private readonly configuration: ServiceConfiguration) {}

  encrypt(
    owner: string,
    conversationId: string,
    messageId: string,
    text: string,
  ) {
    const iv = randomBytes(12);
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.configuration.JOURNAL_ENCRYPTION_KEY,
      iv,
    );
    cipher.setAAD(Buffer.from(`${owner}:${conversationId}:${messageId}`));
    const ciphertext = Buffer.concat([
      cipher.update(text, "utf8"),
      cipher.final(),
    ]);
    return {
      ciphertext,
      iv,
      tag: cipher.getAuthTag(),
      algorithm: "AES-256-GCM" as const,
      keyId: this.configuration.JOURNAL_ENCRYPTION_KEY_ID,
    };
  }

  decrypt(owner: string, conversationId: string, message: ChatMessage): string {
    const decipher = createDecipheriv(
      "aes-256-gcm",
      this.configuration.JOURNAL_ENCRYPTION_KEY,
      buffer(message.content.iv),
    );
    decipher.setAAD(
      Buffer.from(`${owner}:${conversationId}:${message.messageId}`),
    );
    decipher.setAuthTag(buffer(message.content.tag));
    return Buffer.concat([
      decipher.update(buffer(message.content.ciphertext)),
      decipher.final(),
    ]).toString("utf8");
  }
}

interface QuotaLedger {
  _id: string;
  ownerAccountId: string;
  localDate: string;
  plan: ServicePlan;
  successfulAnswers: number;
  usedTokens: number;
  reservations: string[];
  expiresAt: Date;
}

interface RateLedger {
  _id: string;
  ownerAccountId: string;
  minuteBucket: string;
  count: number;
  expiresAt: Date;
}

@Injectable()
export class MongoChatRepository
  implements ChatRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly conversations: Collection<Conversation>;
  private readonly commands: Collection<ChatCommand>;
  private readonly quotas: Collection<QuotaLedger>;
  private readonly rates: Collection<RateLedger>;

  constructor(private readonly configuration: ServiceConfiguration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const db = this.client.db(configuration.MONGODB_DATABASE);
    this.conversations = db.collection("ai_companion_conversations");
    this.commands = db.collection("ai_companion_commands");
    this.quotas = db.collection("ai_companion_quota_ledgers");
    this.rates = db.collection("ai_companion_rate_ledgers");
  }

  private connect() {
    return this.client.connect().then(() => undefined);
  }

  async onApplicationShutdown() {
    await this.client.close();
  }

  async createConversation(value: Conversation) {
    await this.connect();
    await this.conversations.insertOne(value);
    return value;
  }

  async listConversations(ownerAccountId: string) {
    await this.connect();
    return this.conversations
      .find({ ownerAccountId })
      .sort({ updatedAt: -1, _id: -1 })
      .limit(50)
      .toArray();
  }

  async findConversation(ownerAccountId: string, conversationId: string) {
    await this.connect();
    return this.conversations.findOne({ _id: conversationId, ownerAccountId });
  }

  async deleteConversation(ownerAccountId: string, conversationId: string) {
    await this.connect();
    const session = this.client.startSession();
    try {
      let deleted = false;
      await session.withTransaction(async () => {
        const result = await this.conversations.deleteOne(
          { _id: conversationId, ownerAccountId },
          { session },
        );
        deleted = result.deletedCount === 1;
        if (deleted)
          await this.commands.deleteMany(
            { ownerAccountId, conversationId },
            { session },
          );
      });
      return deleted;
    } finally {
      await session.endSession();
    }
  }

  async findCommand(ownerAccountId: string, keyHash: string) {
    await this.connect();
    return this.commands.findOne({ ownerAccountId, keyHash });
  }

  async beginCommand(command: ChatCommand) {
    await this.connect();
    try {
      await this.commands.insertOne(command);
    } catch (error) {
      throw new ConflictException({ cause: error });
    }
  }

  async reserve(
    ownerAccountId: string,
    reservationId: string,
    day: string,
    minuteBucket: string,
    plan: ServicePlan,
    answerLimit: number,
    rateLimit: number,
    tokenBudget: number,
    expiresAt: Date,
  ) {
    await this.connect();
    const rateId = `${ownerAccountId}:${minuteBucket}`;
    try {
      const rate = await this.rates.findOneAndUpdate(
        { _id: rateId, count: { $lt: rateLimit } },
        {
          $setOnInsert: { ownerAccountId, minuteBucket, expiresAt },
          $inc: { count: 1 },
        },
        { upsert: true, returnDocument: "after" },
      );
      if (!rate) throw new Error("rate-limit");
    } catch {
      throw new ChatProblem(429, "CHAT_RATE_LIMITED", "Too many chat requests");
    }

    const quotaId = `${ownerAccountId}:${day}`;
    try {
      try {
        const initial: QuotaLedger = {
          _id: quotaId,
          ownerAccountId,
          localDate: day,
          plan,
          successfulAnswers: 0,
          usedTokens: 0,
          reservations: [reservationId],
          expiresAt,
        };
        await this.quotas.insertOne(initial);
        return { successfulAnswers: 0, usedTokens: 0 };
      } catch (error) {
        // The owner/day ledger already exists; continue with the atomic limit guard.
        if (!(error instanceof MongoServerError) || error.code !== 11_000)
          throw error;
      }
      const quota = await this.quotas.findOneAndUpdate(
        {
          _id: quotaId,
          usedTokens: { $lt: tokenBudget },
          $expr: {
            $lt: [
              { $add: ["$successfulAnswers", { $size: "$reservations" }] },
              answerLimit,
            ],
          },
          reservations: { $ne: reservationId },
        },
        {
          $setOnInsert: {
            ownerAccountId,
            localDate: day,
            successfulAnswers: 0,
            usedTokens: 0,
            expiresAt,
          },
          $set: { plan },
          $addToSet: { reservations: reservationId },
        },
        { returnDocument: "after" },
      );
      if (!quota) throw new Error("quota-limit");
      return {
        successfulAnswers: quota.successfulAnswers,
        usedTokens: quota.usedTokens,
      };
    } catch {
      await this.rates.updateOne({ _id: rateId }, { $inc: { count: -1 } });
      throw new ChatProblem(
        429,
        "CHAT_QUOTA_EXHAUSTED",
        "The AI Companion quota is exhausted",
      );
    }
  }

  async complete(
    command: ChatCommand,
    userMessage: ChatMessage,
    assistantMessage: ChatMessage,
    result: SendResult,
    usedTokens: number,
  ) {
    await this.connect();
    const session = this.client.startSession();
    try {
      await session.withTransaction(async () => {
        const conversation = await this.conversations.updateOne(
          {
            _id: command.conversationId,
            ownerAccountId: command.ownerAccountId,
            "messages.messageId": { $ne: assistantMessage.messageId },
            $expr: { $lte: [{ $size: "$messages" }, 398] },
          },
          {
            $push: { messages: { $each: [userMessage, assistantMessage] } },
            $set: { updatedAt: new Date(result.createdAt) },
          },
          { session },
        );
        if (conversation.modifiedCount !== 1)
          throw new ChatProblem(
            409,
            "CHAT_CONVERSATION_LIMIT_REACHED",
            "Start a new conversation to continue",
          );
        const quota = await this.quotas.updateOne(
          {
            ownerAccountId: command.ownerAccountId,
            reservations: command._id,
            usedTokens: {
              $lte: Math.max(
                0,
                this.configuration.CHAT_DAILY_TOKEN_BUDGET - usedTokens,
              ),
            },
          },
          {
            $pull: { reservations: command._id },
            $inc: { successfulAnswers: 1, usedTokens },
          },
          { session },
        );
        if (quota.modifiedCount !== 1)
          throw new ChatProblem(
            429,
            "CHAT_TOKEN_BUDGET_EXHAUSTED",
            "AI token budget is exhausted",
          );
        const completed = await this.commands.updateOne(
          { _id: command._id, state: "RUNNING" },
          {
            $set: {
              state: "SUCCEEDED",
              response: result,
              updatedAt: new Date(result.createdAt),
            },
          },
          { session },
        );
        if (completed.modifiedCount !== 1)
          throw new Error("Atomic chat completion precondition failed");
      });
    } finally {
      await session.endSession();
    }
  }

  async fail(
    command: ChatCommand,
    code: string,
    status: number,
    title: string,
  ) {
    await this.connect();
    await this.quotas.updateOne(
      { ownerAccountId: command.ownerAccountId, reservations: command._id },
      { $pull: { reservations: command._id } },
    );
    await this.commands.updateOne(
      { _id: command._id },
      {
        $set: {
          state: "FAILED",
          failureCode: code,
          failureStatus: status,
          failureTitle: title,
          updatedAt: new Date(),
        },
      },
    );
  }
}

interface LongitudinalResultRow {
  _id: string;
  userId: string;
  result: {
    contextSignals: string[];
    recurringThemes: string[];
    preferences: string[];
    barriers: string[];
    helpfulPatterns: string[];
  };
}

const supportPlanSchema = z.looseObject({
  supportPlanId: uuid,
  status: z.enum(["ACTIVE", "PAUSED"]),
  version: z.number().int().min(0),
  rationale: z.looseObject({ text: z.string().max(2_048) }),
  slots: z
    .array(
      z.looseObject({
        purposeCode: z.string().max(64),
        selectedResource: z
          .looseObject({
            title: z.string().max(255),
            summary: z.string().max(4_096),
          })
          .nullable(),
      }),
    )
    .max(5),
});

@Injectable()
export class OwnerVerifiedContextAssembler
  implements ChatContextAssembler, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly journals: Collection<Entry>;
  private readonly longitudinal: Collection<LongitudinalResultRow>;

  constructor(private readonly configuration: ServiceConfiguration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const db = this.client.db(configuration.MONGODB_DATABASE);
    this.journals = db.collection("journal_entries");
    this.longitudinal = db.collection("journal_longitudinal_analysis_results");
  }

  async onApplicationShutdown() {
    await this.client.close();
  }

  async assemble(
    ownerAccountId: string,
    authorization: string,
    correlationId: string,
    selection: ContextSelection,
  ): Promise<MinimizedContext> {
    if (selection.includeReminderContext)
      throw new ChatProblem(
        409,
        "REMINDER_CONTEXT_UNAVAILABLE",
        "Reminder context has no approved owner contract",
      );
    await this.client.connect();
    const sections: string[] = [];
    const kinds = new Set<MinimizedContext["kinds"][number]>();

    if (selection.journalIds.length > 0) {
      const rows = await this.journals
        .find({
          _id: { $in: selection.journalIds },
          ownerAccountId,
          deleted: false,
        })
        .toArray();
      if (rows.length !== selection.journalIds.length)
        throw new ChatProblem(
          404,
          "CHAT_CONTEXT_NOT_FOUND",
          "Selected context was not found",
        );
      for (const journalId of selection.journalIds) {
        const row = rows.find((candidate) => candidate._id === journalId);
        const revision = row?.revisions.find(
          (candidate: Revision) => candidate.revision === row.currentRevision,
        );
        if (!row || !revision)
          throw new ChatProblem(
            404,
            "CHAT_CONTEXT_NOT_FOUND",
            "Selected context was not found",
          );
        const decipher = createDecipheriv(
          "aes-256-gcm",
          this.configuration.JOURNAL_ENCRYPTION_KEY,
          buffer(revision.content.iv),
        );
        decipher.setAAD(
          Buffer.from(
            `${ownerAccountId}:${journalId}:${String(row.currentRevision)}`,
          ),
        );
        decipher.setAuthTag(buffer(revision.content.tag));
        const text = Buffer.concat([
          decipher.update(buffer(revision.content.ciphertext)),
          decipher.final(),
        ]).toString("utf8");
        sections.push(`Journal reflection: ${text.slice(0, 1_500)}`);
      }
      kinds.add("JOURNAL");
    }

    if (selection.longitudinalAnalysisId) {
      const row = await this.longitudinal.findOne({
        _id: selection.longitudinalAnalysisId,
        userId: ownerAccountId,
      });
      if (!row)
        throw new ChatProblem(
          404,
          "CHAT_CONTEXT_NOT_FOUND",
          "Selected context was not found",
        );
      sections.push(
        `Reassessment context (non-clinical): ${JSON.stringify({
          contextSignals: row.result.contextSignals,
          recurringThemes: row.result.recurringThemes,
          preferences: row.result.preferences,
          barriers: row.result.barriers,
          helpfulPatterns: row.result.helpfulPatterns,
        })}`,
      );
      kinds.add("REASSESSMENT");
    }

    if (selection.includeCurrentSupportPlan) {
      let response: Response;
      try {
        response = await fetch(
          new URL(
            "/api/v1/support-plans/current",
            this.configuration.CARE_BASE_URL,
          ),
          {
            headers: {
              authorization: `Bearer ${authorization}`,
              "x-correlation-id": correlationId,
            },
            signal: AbortSignal.timeout(this.configuration.CARE_TIMEOUT_MS),
          },
        );
      } catch {
        throw new ChatProblem(
          503,
          "CHAT_CONTEXT_UNAVAILABLE",
          "Care context is unavailable",
        );
      }
      if (response.status !== 404) {
        if (!response.ok)
          throw new ChatProblem(
            503,
            "CHAT_CONTEXT_UNAVAILABLE",
            "Care context is unavailable",
          );
        const parsed = supportPlanSchema.safeParse(await response.json());
        if (!parsed.success)
          throw new ChatProblem(
            503,
            "CHAT_CONTEXT_UNAVAILABLE",
            "Care context is invalid",
          );
        sections.push(
          `Current SupportPlan: ${JSON.stringify({
            status: parsed.data.status,
            version: parsed.data.version,
            summary: parsed.data.rationale.text,
            activities: parsed.data.slots.map((slot) => ({
              purposeCode: slot.purposeCode,
              resource: slot.selectedResource
                ? {
                    title: slot.selectedResource.title,
                    summary: slot.selectedResource.summary,
                  }
                : null,
            })),
          })}`,
        );
        kinds.add("SUPPORT_PLAN");
      }
    }

    return { kinds: [...kinds], prompt: sections.join("\n").slice(0, 8_000) };
  }
}

@Injectable()
export class RoutedChatProvider implements ChatProvider {
  constructor(private readonly configuration: ServiceConfiguration) {}

  async reply(
    userMessage: string,
    context: MinimizedContext,
    route: ChatRoute,
  ) {
    if (route.provider === "DETERMINISTIC_FAKE") {
      return {
        message:
          context.kinds.length === 0
            ? "Mình đang lắng nghe. Bạn muốn bắt đầu từ điều gì đang khiến bạn bận tâm nhất lúc này?"
            : "Mình đã xem phần bối cảnh bạn cho phép. Ta có thể chọn một bước nhỏ, phù hợp với kế hoạch hiện tại và điều bạn vừa chia sẻ.",
        inputTokens: Math.ceil(
          (userMessage.length + context.prompt.length) / 4,
        ),
        outputTokens: 32,
      };
    }
    const system = [
      "You are MentalBridge AI Companion. Reply in Vietnamese with at most 500 characters.",
      "Support reflection only. Never diagnose, score assessments, decide safety or eligibility, mutate a SupportPlan, schedule reminders, or contact third parties.",
      "Do not reveal hidden reasoning. If immediate danger is mentioned, direct the user to the deterministic Help now control in the app; do not claim to contact anyone.",
      context.prompt
        ? `Authorized minimized context:\n${context.prompt}`
        : "No additional context was authorized.",
    ].join("\n");
    const startedAt = performance.now();
    let response: Response;
    try {
      if (route.provider === "GEMINI") {
        response = await fetch(
          new URL(
            `/v1beta/models/${encodeURIComponent(route.model.replace(/^models\//, ""))}:generateContent`,
            this.configuration.GEMINI_BASE_URL,
          ),
          {
            method: "POST",
            headers: {
              "content-type": "application/json",
              "x-goog-api-key": this.configuration.GEMINI_API_KEY ?? "",
            },
            body: JSON.stringify({
              systemInstruction: { parts: [{ text: system }] },
              contents: [{ role: "user", parts: [{ text: userMessage }] }],
              generationConfig: { temperature: 0.2, maxOutputTokens: 300 },
            }),
            signal: AbortSignal.timeout(this.configuration.PROVIDER_TIMEOUT_MS),
          },
        );
      } else {
        response = await fetch(
          new URL("/v1/responses", this.configuration.OPENAI_BASE_URL),
          {
            method: "POST",
            headers: {
              "content-type": "application/json",
              authorization: `Bearer ${this.configuration.OPENAI_API_KEY ?? ""}`,
            },
            body: JSON.stringify({
              model: route.model,
              store: false,
              instructions: system,
              input: userMessage,
              max_output_tokens: 300,
            }),
            signal: AbortSignal.timeout(this.configuration.PROVIDER_TIMEOUT_MS),
          },
        );
      }
    } catch {
      throw new ChatProblem(
        503,
        "CHAT_PROVIDER_UNAVAILABLE",
        "AI provider is unavailable",
      );
    }
    if (!response.ok)
      throw new ChatProblem(
        503,
        "CHAT_PROVIDER_UNAVAILABLE",
        "AI provider is unavailable",
      );
    const body: unknown = await response.json();
    const text =
      route.provider === "GEMINI"
        ? z
            .object({
              candidates: z
                .array(
                  z.object({
                    content: z.object({
                      parts: z.array(z.object({ text: z.string() })),
                    }),
                  }),
                )
                .min(1),
            })
            .safeParse(body)
        : z
            .object({
              output: z.array(
                z.object({
                  content: z
                    .array(
                      z.object({
                        type: z.string(),
                        text: z.string().optional(),
                      }),
                    )
                    .optional(),
                }),
              ),
            })
            .safeParse(body);
    let message: string | undefined;
    if (text.success) {
      message =
        route.provider === "GEMINI"
          ? (
              text.data as {
                candidates: { content: { parts: { text: string }[] } }[];
              }
            ).candidates[0]?.content.parts
              .map((part) => part.text)
              .join("")
          : (
              text.data as {
                output: { content?: { type: string; text?: string }[] }[];
              }
            ).output
              .flatMap((item) => item.content ?? [])
              .find((item) => item.type === "output_text")?.text;
    }
    const normalized = message?.trim().slice(0, 500);
    if (!normalized)
      throw new ChatProblem(
        503,
        "CHAT_PROVIDER_INVALID_RESPONSE",
        "AI response is invalid",
      );
    void startedAt;
    return {
      message: normalized,
      inputTokens: Math.ceil((system.length + userMessage.length) / 4),
      outputTokens: Math.ceil(normalized.length / 4),
    };
  }
}

const publicConversation = (
  crypto: ChatCrypto,
  conversation: Conversation,
) => ({
  conversationId: conversation._id,
  title: conversation.title,
  createdAt: conversation.createdAt.toISOString(),
  updatedAt: conversation.updatedAt.toISOString(),
  expiresAt: conversation.expiresAt.toISOString(),
  messages: conversation.messages.map((message) => ({
    messageId: message.messageId,
    role: message.role,
    content: crypto.decrypt(
      conversation.ownerAccountId,
      conversation._id,
      message,
    ),
    createdAt: message.createdAt.toISOString(),
    contextKinds: message.contextKinds,
  })),
});

@Injectable()
export class CompanionChatService {
  private readonly crypto: ChatCrypto;

  constructor(
    @Inject(REPOSITORY) private readonly repository: ChatRepository,
    @Inject(CONSENT) private readonly consent: ConsentClient,
    @Inject(ENTITLEMENT) private readonly entitlement: EntitlementClient,
    @Inject(CONTEXT) private readonly context: ChatContextAssembler,
    @Inject(PROVIDER) private readonly provider: ChatProvider,
    @Inject(CLOCK) private readonly clock: CompanionClock,
    private readonly configuration: ServiceConfiguration,
  ) {
    this.crypto = new ChatCrypto(configuration);
  }

  async create(request: ChatRequest) {
    const owner = requestOwner(request);
    const body = createConversationSchema.safeParse(request.body ?? {});
    if (!body.success) throw new BadRequestException();
    const now = this.clock.now();
    const conversation: Conversation = {
      _id: randomUUID(),
      ownerAccountId: owner,
      title: body.data.title ?? "Cuộc trò chuyện mới",
      messages: [],
      createdAt: now,
      updatedAt: now,
      expiresAt: new Date(
        now.getTime() + this.configuration.CHAT_RETENTION_DAYS * 86_400_000,
      ),
    };
    await this.repository.createConversation(conversation);
    return publicConversation(this.crypto, conversation);
  }

  async list(request: ChatRequest) {
    const owner = requestOwner(request);
    const rows = await this.repository.listConversations(owner);
    return { items: rows.map((row) => publicConversation(this.crypto, row)) };
  }

  async get(request: ChatRequest, conversationId: string) {
    const owner = requestOwner(request);
    if (!uuid.safeParse(conversationId).success)
      throw new BadRequestException();
    const row = await this.repository.findConversation(owner, conversationId);
    if (!row) throw new NotFoundException();
    return publicConversation(this.crypto, row);
  }

  async delete(request: ChatRequest, conversationId: string) {
    const owner = requestOwner(request);
    if (!uuid.safeParse(conversationId).success)
      throw new BadRequestException();
    if (!(await this.repository.deleteConversation(owner, conversationId)))
      throw new NotFoundException();
  }

  async send(
    request: ChatRequest,
    conversationId: string,
  ): Promise<SendResult> {
    const owner = requestOwner(request);
    if (!uuid.safeParse(conversationId).success)
      throw new BadRequestException();
    const parsed = sendMessageSchema.safeParse(request.body);
    const rawKey = idempotencyKey.safeParse(header(request, "idempotency-key"));
    if (!parsed.success || !rawKey.success) throw new BadRequestException();
    const conversation = await this.repository.findConversation(
      owner,
      conversationId,
    );
    if (!conversation) throw new NotFoundException();
    if (conversation.messages.length >= 400)
      throw new ChatProblem(
        409,
        "CHAT_CONVERSATION_LIMIT_REACHED",
        "Start a new conversation to continue",
      );
    const keyHash = digest(
      this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      rawKey.data,
    );
    const fingerprint = digest(
      this.configuration.JOURNAL_IDEMPOTENCY_HMAC_KEY,
      JSON.stringify({ conversationId, body: parsed.data }),
    );
    const replay = await this.repository.findCommand(owner, keyHash);
    if (replay) return this.replay(replay, fingerprint);

    const now = this.clock.now();
    const command: ChatCommand = {
      _id: randomUUID(),
      ownerAccountId: owner,
      conversationId,
      keyHash,
      fingerprint,
      state: "RUNNING",
      response: null,
      failureCode: null,
      failureStatus: null,
      failureTitle: null,
      createdAt: now,
      updatedAt: now,
      expiresAt: conversation.expiresAt,
    };
    try {
      await this.repository.beginCommand(command);
    } catch (error) {
      const concurrent = await this.repository.findCommand(owner, keyHash);
      if (concurrent) return this.replay(concurrent, fingerprint);
      throw error;
    }

    let decision: EntitlementDecision;
    try {
      const consent = await this.consent.check(
        bearer(request),
        request.id ?? randomUUID(),
      );
      if (!consent.authorized)
        throw new ChatProblem(
          403,
          "AI_CONSENT_REQUIRED",
          "AI processing consent is required",
        );
      decision = await this.entitlement.current(
        bearer(request),
        request.id ?? randomUUID(),
      );
    } catch (error) {
      const failure = this.failure(
        error,
        "CONSENT_OR_ENTITLEMENT_UNAVAILABLE",
        "Authorization is unavailable",
      );
      await this.repository.fail(
        command,
        failure.code,
        failure.status,
        failure.title,
      );
      if (error instanceof HttpException) throw error;
      throw new ChatProblem(
        503,
        "CONSENT_OR_ENTITLEMENT_UNAVAILABLE",
        "Authorization is unavailable",
      );
    }

    const day = localDate(now, this.configuration.CHAT_DEFAULT_TIMEZONE);
    const resetAt = nextLocalDay(now, this.configuration.CHAT_DEFAULT_TIMEZONE);
    const answerLimit =
      decision.packageCode === "FREE"
        ? this.configuration.CHAT_FREE_DAILY_ANSWERS
        : decision.packageCode === "PLUS"
          ? this.configuration.CHAT_PLUS_DAILY_ANSWERS
          : this.configuration.CHAT_PREMIUM_FAIR_USE_DAILY_ANSWERS;
    let reserved: { successfulAnswers: number; usedTokens: number };
    try {
      reserved = await this.repository.reserve(
        owner,
        command._id,
        day,
        now.toISOString().slice(0, 16),
        decision.packageCode,
        answerLimit,
        this.configuration.CHAT_RATE_LIMIT_PER_MINUTE,
        this.configuration.CHAT_DAILY_TOKEN_BUDGET,
        new Date(resetAt.getTime() + 7 * 86_400_000),
      );
    } catch (error) {
      const failure = this.failure(
        error,
        "CHAT_LIMIT_UNAVAILABLE",
        "Chat limit is unavailable",
      );
      await this.repository.fail(
        command,
        failure.code,
        failure.status,
        failure.title,
      );
      throw error;
    }

    try {
      const minimized = await this.context.assemble(
        owner,
        bearer(request),
        request.id ?? randomUUID(),
        parsed.data.context,
      );
      const route = this.route(decision);
      const reply = await this.provider.reply(
        parsed.data.message,
        minimized,
        route,
      );
      const tokens = reply.inputTokens + reply.outputTokens;
      if (
        reserved.usedTokens + tokens >
        this.configuration.CHAT_DAILY_TOKEN_BUDGET
      )
        throw new ChatProblem(
          429,
          "CHAT_TOKEN_BUDGET_EXHAUSTED",
          "AI token budget is exhausted",
        );
      const createdAt = this.clock.now();
      const userMessageId = randomUUID();
      const assistantMessageId = randomUUID();
      const userMessage: ChatMessage = {
        messageId: userMessageId,
        role: "USER",
        content: this.crypto.encrypt(
          owner,
          conversationId,
          userMessageId,
          parsed.data.message,
        ),
        createdAt,
        route: null,
        contextKinds: [],
      };
      const assistantMessage: ChatMessage = {
        messageId: assistantMessageId,
        role: "ASSISTANT",
        content: this.crypto.encrypt(
          owner,
          conversationId,
          assistantMessageId,
          reply.message,
        ),
        createdAt,
        route,
        contextKinds: minimized.kinds,
      };
      const remaining = Math.max(
        0,
        answerLimit - reserved.successfulAnswers - 1,
      );
      const result: SendResult = {
        conversationId,
        userMessageId,
        assistantMessageId,
        assistant: reply.message,
        createdAt: createdAt.toISOString(),
        quota: {
          plan: decision.packageCode,
          policyVersion: "companion-quota-v1",
          remaining: decision.packageCode === "PREMIUM" ? null : remaining,
          resetAt: resetAt.toISOString(),
          limitDisplayed: decision.packageCode !== "PREMIUM",
        },
      };
      await this.repository.complete(
        command,
        userMessage,
        assistantMessage,
        result,
        tokens,
      );
      return result;
    } catch (error) {
      const failure = this.failure(
        error,
        "CHAT_PROVIDER_UNAVAILABLE",
        "AI Companion is temporarily unavailable",
      );
      await this.repository.fail(
        command,
        failure.code,
        failure.status,
        failure.title,
      );
      if (error instanceof HttpException) throw error;
      throw new ChatProblem(503, failure.code, failure.title);
    }
  }

  private route(decision: EntitlementDecision): ChatRoute {
    if (this.configuration.PROVIDER_MODE === "DETERMINISTIC_FAKE")
      return {
        workload: "COMPANION_CHAT",
        servicePlan: decision.packageCode,
        entitlementSource: decision.source,
        entitlementPolicyVersion: decision.policyVersion,
        entitlementVersion: decision.version,
        routingPolicyVersion: this.configuration.CHAT_ROUTING_POLICY_VERSION,
        providerApprovalVersion: "local-deterministic-v1",
        provider: "DETERMINISTIC_FAKE",
        model: "deterministic-companion-v1",
        promptVersion: "companion-chat-v1",
      };
    const configured =
      decision.packageCode === "PREMIUM"
        ? this.configuration.PREMIUM_ROUTE
        : this.configuration.FREE_PLUS_ROUTE;
    if (!configured || !this.configuration.PROVIDER_APPROVAL_VERSION)
      throw new ChatProblem(
        503,
        "CHAT_ROUTE_UNAVAILABLE",
        "Approved AI route is unavailable",
      );
    return {
      workload: "COMPANION_CHAT",
      servicePlan: decision.packageCode,
      entitlementSource: decision.source,
      entitlementPolicyVersion: decision.policyVersion,
      entitlementVersion: decision.version,
      routingPolicyVersion: this.configuration.CHAT_ROUTING_POLICY_VERSION,
      providerApprovalVersion: this.configuration.PROVIDER_APPROVAL_VERSION,
      provider: configured.provider,
      model: configured.model,
      promptVersion: "companion-chat-v1",
    };
  }

  private replay(command: ChatCommand, fingerprint: string): SendResult {
    if (command.fingerprint !== fingerprint)
      throw new ChatProblem(
        409,
        "IDEMPOTENCY_KEY_REUSED",
        "Idempotency key was reused",
      );
    if (command.state === "SUCCEEDED" && command.response)
      return command.response;
    if (command.state === "FAILED")
      throw new ChatProblem(
        command.failureStatus ?? 503,
        command.failureCode ?? "CHAT_REQUEST_FAILED",
        command.failureTitle ?? "Chat request failed",
      );
    throw new ChatProblem(
      409,
      "CHAT_REQUEST_IN_PROGRESS",
      "Chat request is in progress",
    );
  }

  private failure(error: unknown, code: string, title: string) {
    if (error instanceof ChatProblem)
      return {
        code: error.code,
        status: error.getStatus(),
        title: error.safeTitle,
      };
    return { code, status: 503, title };
  }
}

@Controller("api/v1/ai-companion/conversations")
export class CompanionChatController {
  constructor(private readonly service: CompanionChatService) {}

  @Post()
  create(@Req() request: ChatRequest) {
    return this.service.create(request);
  }

  @Get()
  list(@Req() request: ChatRequest) {
    return this.service.list(request);
  }

  @Get(":conversationId")
  get(
    @Req() request: ChatRequest,
    @Param("conversationId") conversationId: string,
  ) {
    return this.service.get(request, conversationId);
  }

  @Post(":conversationId/messages")
  send(
    @Req() request: ChatRequest,
    @Param("conversationId") conversationId: string,
  ) {
    return this.service.send(request, conversationId);
  }

  @Delete(":conversationId")
  @HttpCode(204)
  delete(
    @Req() request: ChatRequest,
    @Param("conversationId") conversationId: string,
  ) {
    return this.service.delete(request, conversationId);
  }
}

export const registerCompanionChatModule = (
  configuration: ServiceConfiguration,
  dependencies: CompanionChatDependencies = {},
): DynamicModule => ({
  module: CompanionChatModule,
  controllers: [CompanionChatController],
  providers: [
    dependencies.repository
      ? { provide: REPOSITORY, useValue: dependencies.repository }
      : {
          provide: REPOSITORY,
          useFactory: () => new MongoChatRepository(configuration),
        },
    dependencies.consentClient
      ? { provide: CONSENT, useValue: dependencies.consentClient }
      : {
          provide: CONSENT,
          useFactory: () => new CareConsentClient(configuration),
        },
    dependencies.entitlementClient
      ? { provide: ENTITLEMENT, useValue: dependencies.entitlementClient }
      : {
          provide: ENTITLEMENT,
          useFactory: () => new ConsultationEntitlementClient(configuration),
        },
    dependencies.contextAssembler
      ? { provide: CONTEXT, useValue: dependencies.contextAssembler }
      : {
          provide: CONTEXT,
          useFactory: () => new OwnerVerifiedContextAssembler(configuration),
        },
    dependencies.provider
      ? { provide: PROVIDER, useValue: dependencies.provider }
      : {
          provide: PROVIDER,
          useFactory: () => new RoutedChatProvider(configuration),
        },
    {
      provide: CLOCK,
      useValue: dependencies.clock ?? { now: () => new Date() },
    },
    {
      provide: CompanionChatService,
      useFactory: (
        repository: ChatRepository,
        consent: ConsentClient,
        entitlement: EntitlementClient,
        context: ChatContextAssembler,
        provider: ChatProvider,
        clock: CompanionClock,
      ) =>
        new CompanionChatService(
          repository,
          consent,
          entitlement,
          context,
          provider,
          clock,
          configuration,
        ),
      inject: [REPOSITORY, CONSENT, ENTITLEMENT, CONTEXT, PROVIDER, CLOCK],
    },
  ],
});

@Module({})
export class CompanionChatModule {
  readonly moduleName = "companion-chat";
}
