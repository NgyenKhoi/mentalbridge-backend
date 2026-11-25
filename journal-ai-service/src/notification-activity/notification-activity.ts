import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Headers,
  HttpCode,
  HttpStatus,
  Inject,
  Injectable,
  Module,
  Post,
  Query,
  Req,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type OnApplicationShutdown,
  type Provider,
} from "@nestjs/common";
import { MongoClient, type Collection } from "mongodb";
import { timingSafeEqual } from "node:crypto";
import { z } from "zod";

import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";
import { Public } from "../security/public.decorator.js";
import { calculateEmotionStreak } from "../emotion-check-ins/emotion-progress.js";

const REPOSITORY = "NOTIFICATION_ACTIVITY_REPOSITORY";
const CLOCK = "NOTIFICATION_ACTIVITY_CLOCK";

interface JournalActivityRow {
  readonly ownerAccountId: string;
  readonly occurredAt: Date;
  readonly deleted: boolean;
}

interface EmotionActivityRow {
  readonly ownerAccountId: string;
  readonly localDate: string;
  readonly createdAt: Date;
  readonly deleted: boolean;
}

export interface NotificationActivityRepository {
  snapshot(ownerAccountId: string): Promise<{
    readonly journalOccurredAt: readonly Date[];
    readonly emotionActivity: readonly {
      readonly localDate: string;
      readonly occurredAt: Date;
    }[];
  }>;
}

export interface NotificationActivityClock {
  now(): Date;
}

export interface NotificationActivityDependencies {
  readonly repository?: NotificationActivityRepository;
  readonly clock?: NotificationActivityClock;
}

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

const uuidSchema = z.uuid();
const internalRequestSchema = z
  .object({ ownerAccountId: uuidSchema, timezone: timezoneSchema })
  .strict();

const owner = (request: AuthenticatedRequest): string => {
  const accountId = request.principal?.accountId;
  if (!accountId || !uuidSchema.safeParse(accountId).success)
    throw new UnauthorizedException();
  return accountId;
};

const localDateAt = (instant: Date, timezone: string): string => {
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

const ordinal = (localDate: string): number => {
  const [year, month, day] = localDate.split("-").map(Number);
  if (year === undefined || month === undefined || day === undefined)
    throw new BadRequestException();
  return Math.floor(Date.UTC(year, month - 1, day) / 86_400_000);
};

export const factualStreak = (
  dates: readonly string[],
  asOfLocalDate: string,
): {
  completedToday: boolean;
  currentStreak: number;
  longestStreak: number;
} => {
  const asOfOrdinal = ordinal(asOfLocalDate);
  const active = new Set(
    dates.filter((date) => date <= asOfLocalDate).map(ordinal),
  );
  let currentStreak = 0;
  let current = active.has(asOfOrdinal) ? asOfOrdinal : asOfOrdinal - 1;
  while (active.has(current)) {
    currentStreak += 1;
    current -= 1;
  }
  let longestStreak = 0;
  let run = 0;
  let previous: number | undefined;
  for (const dateOrdinal of [...active].sort((left, right) => left - right)) {
    run = previous !== undefined && dateOrdinal === previous + 1 ? run + 1 : 1;
    longestStreak = Math.max(longestStreak, run);
    previous = dateOrdinal;
  }
  return {
    completedToday: active.has(asOfOrdinal),
    currentStreak,
    longestStreak,
  };
};

const ACHIEVEMENT_STREAK_THRESHOLDS = [3, 7] as const;

export const factualAchievementActivity = (
  activity: readonly {
    readonly localDate: string;
    readonly occurredAt: Date;
  }[],
  asOfLocalDate: string,
) => {
  const earliestByDay = new Map<string, Date>();
  for (const item of activity) {
    if (item.localDate > asOfLocalDate) continue;
    const current = earliestByDay.get(item.localDate);
    if (!current || item.occurredAt < current)
      earliestByDay.set(item.localDate, item.occurredAt);
  }
  const ordered = [...earliestByDay.entries()].sort(([left], [right]) =>
    left.localeCompare(right),
  );
  const reachedAt = new Map<number, string>();
  let run = 0;
  let longestStreak = 0;
  let previous: number | undefined;
  for (const [localDate, occurredAt] of ordered) {
    const dateOrdinal = ordinal(localDate);
    run = previous !== undefined && dateOrdinal === previous + 1 ? run + 1 : 1;
    longestStreak = Math.max(longestStreak, run);
    for (const threshold of ACHIEVEMENT_STREAK_THRESHOLDS) {
      if (run >= threshold && !reachedAt.has(threshold)) {
        reachedAt.set(threshold, occurredAt.toISOString());
      }
    }
    previous = dateOrdinal;
  }
  return {
    distinctDays: ordered.length,
    longestStreak,
    firstOccurredAt:
      activity
        .filter((item) => item.localDate <= asOfLocalDate)
        .map((item) => item.occurredAt)
        .sort((left, right) => left.getTime() - right.getTime())[0]
        ?.toISOString() ?? null,
    streakMilestones: ACHIEVEMENT_STREAK_THRESHOLDS.map((days) => ({
      days,
      reachedAt: reachedAt.get(days) ?? null,
    })),
  };
};

@Injectable()
export class MongoNotificationActivityRepository
  implements NotificationActivityRepository, OnApplicationShutdown
{
  private readonly client: MongoClient;
  private readonly journals: Collection<JournalActivityRow>;
  private readonly emotions: Collection<EmotionActivityRow>;

  constructor(configuration: Configuration) {
    this.client = new MongoClient(configuration.MONGODB_URI, {
      connectTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
      serverSelectionTimeoutMS: configuration.MONGODB_CONNECTION_TIMEOUT_MS,
    });
    const database = this.client.db(configuration.MONGODB_DATABASE);
    this.journals = database.collection<JournalActivityRow>("journal_entries");
    this.emotions =
      database.collection<EmotionActivityRow>("emotion_check_ins");
  }

  async snapshot(ownerAccountId: string) {
    try {
      await this.client.connect();
      const [journals, emotions] = await Promise.all([
        this.journals
          .find({ ownerAccountId, deleted: false })
          .project<{ occurredAt: Date }>({ _id: 0, occurredAt: 1 })
          .toArray(),
        this.emotions
          .find({ ownerAccountId, deleted: false })
          .project<{ localDate: string; createdAt: Date }>({
            _id: 0,
            localDate: 1,
            createdAt: 1,
          })
          .toArray(),
      ]);
      return {
        journalOccurredAt: journals.map((row) => row.occurredAt),
        emotionActivity: emotions.map((row) => ({
          localDate: row.localDate,
          occurredAt: row.createdAt,
        })),
      };
    } catch (error) {
      throw new ServiceUnavailableException({ cause: error });
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.client.close();
  }
}

@Injectable()
export class NotificationActivityService {
  constructor(
    @Inject(REPOSITORY)
    private readonly repository: NotificationActivityRepository,
    @Inject(CLOCK) private readonly clock: NotificationActivityClock,
  ) {}

  async get(ownerAccountId: string, timezone: string) {
    const parsedTimezone = timezoneSchema.safeParse(timezone);
    if (!parsedTimezone.success) throw new BadRequestException();
    const now = this.clock.now();
    const asOfLocalDate = localDateAt(now, parsedTimezone.data);
    const snapshot = await this.repository.snapshot(ownerAccountId);
    const journalDates = snapshot.journalOccurredAt.map((occurredAt) =>
      localDateAt(occurredAt, parsedTimezone.data),
    );
    const journalActivity = snapshot.journalOccurredAt.map((occurredAt) => ({
      localDate: localDateAt(occurredAt, parsedTimezone.data),
      occurredAt,
    }));
    const emotionDates = snapshot.emotionActivity.map((item) => item.localDate);
    return {
      asOfLocalDate,
      timezone: parsedTimezone.data,
      journal: factualStreak(journalDates, asOfLocalDate),
      emotionCheckIn: calculateEmotionStreak(emotionDates, asOfLocalDate),
      achievementFacts: {
        ownerAccountId,
        journal: factualAchievementActivity(journalActivity, asOfLocalDate),
        emotionCheckIn: factualAchievementActivity(
          snapshot.emotionActivity,
          asOfLocalDate,
        ),
      },
      interpretation: "FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY" as const,
    };
  }
}

const matchesServiceToken = (
  candidate: string | undefined,
  expected: string | null,
): boolean => {
  if (!candidate || !expected) return false;
  const candidateBuffer = Buffer.from(candidate);
  const expectedBuffer = Buffer.from(expected);
  return (
    candidateBuffer.length === expectedBuffer.length &&
    timingSafeEqual(candidateBuffer, expectedBuffer)
  );
};

@Controller("api/v1/notification-activity")
export class NotificationActivityController {
  constructor(private readonly service: NotificationActivityService) {}

  @Get()
  get(
    @Req() request: AuthenticatedRequest,
    @Query("timezone") timezone: string,
  ) {
    return this.service.get(owner(request), timezone);
  }
}

@Public()
@Controller("internal/v1/notification-activity")
export class InternalNotificationActivityController {
  constructor(
    private readonly service: NotificationActivityService,
    @Inject("NOTIFICATION_ACTIVITY_CONFIGURATION")
    private readonly configuration: Configuration,
  ) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  get(
    @Headers("x-mentalbridge-service-token") serviceToken: string | undefined,
    @Body() body: unknown,
  ) {
    if (
      !matchesServiceToken(
        serviceToken,
        this.configuration.REMINDER_SERVICE_TOKEN,
      )
    )
      throw new UnauthorizedException();
    const parsed = internalRequestSchema.safeParse(body);
    if (!parsed.success) throw new BadRequestException();
    return this.service.get(parsed.data.ownerAccountId, parsed.data.timezone);
  }
}

export const registerNotificationActivityModule = (
  configuration: Configuration,
  dependencies: NotificationActivityDependencies = {},
): DynamicModule => {
  const repository: Provider = dependencies.repository
    ? { provide: REPOSITORY, useValue: dependencies.repository }
    : {
        provide: REPOSITORY,
        useFactory: () =>
          new MongoNotificationActivityRepository(configuration),
      };
  return {
    module: NotificationActivityModule,
    controllers: [
      NotificationActivityController,
      InternalNotificationActivityController,
    ],
    providers: [
      NotificationActivityService,
      repository,
      {
        provide: "NOTIFICATION_ACTIVITY_CONFIGURATION",
        useValue: configuration,
      },
      {
        provide: CLOCK,
        useValue: dependencies.clock ?? { now: () => new Date() },
      },
    ],
  };
};

@Module({})
export class NotificationActivityModule {
  readonly moduleName = "notification-activity";
}
