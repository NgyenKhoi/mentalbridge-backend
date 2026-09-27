import {
  BadRequestException,
  Controller,
  Get,
  Inject,
  Injectable,
  Module,
  Query,
  Req,
  ServiceUnavailableException,
  UnauthorizedException,
  type DynamicModule,
  type OnApplicationShutdown,
  type Provider,
} from "@nestjs/common";
import { MongoClient, type Collection } from "mongodb";
import { z } from "zod";

import type { ServiceConfiguration as Configuration } from "../configuration/configuration.js";
import type { AuthenticatedRequest } from "../security/authenticated-principal.js";

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
  readonly deleted: boolean;
}

export interface NotificationActivityRepository {
  snapshot(ownerAccountId: string): Promise<{
    readonly journalOccurredAt: readonly Date[];
    readonly emotionLocalDates: readonly string[];
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
          .project<{ localDate: string }>({ _id: 0, localDate: 1 })
          .toArray(),
      ]);
      return {
        journalOccurredAt: journals.map((row) => row.occurredAt),
        emotionLocalDates: emotions.map((row) => row.localDate),
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
    return {
      asOfLocalDate,
      timezone: parsedTimezone.data,
      journal: factualStreak(journalDates, asOfLocalDate),
      emotionCheckIn: factualStreak(snapshot.emotionLocalDates, asOfLocalDate),
      interpretation: "FACTUAL_ACTIVITY_NOT_ADHERENCE_OR_RECOVERY" as const,
    };
  }
}

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
    controllers: [NotificationActivityController],
    providers: [
      NotificationActivityService,
      repository,
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
