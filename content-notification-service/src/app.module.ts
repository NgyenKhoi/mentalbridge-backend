import { Module, type DynamicModule, type Provider } from '@nestjs/common';
import { Reflector } from '@nestjs/core';

import {
  CONFIGURATION_TOKEN,
  READINESS_PROBE_TOKEN,
  DATABASE_SERVICE_TOKEN,
  RESOURCE_REPOSITORY_TOKEN,
  RESOURCE_SERVICE_TOKEN,
  RESOURCE_PROGRESS_REPOSITORY_TOKEN,
  RESOURCE_PROGRESS_SERVICE_TOKEN,
  RESOURCE_JOURNEY_REPOSITORY_TOKEN,
  RESOURCE_JOURNEY_SERVICE_TOKEN,
  E2E_OUTAGE_STATE_TOKEN,
  RESOURCE_ELIGIBILITY_REPOSITORY_TOKEN,
  RESOURCE_ELIGIBILITY_SERVICE_TOKEN,
  SAFETY_DIRECTORY_REPOSITORY_TOKEN,
  SAFETY_DIRECTORY_SERVICE_TOKEN,
  NOTIFICATION_PREFERENCE_REPOSITORY_TOKEN,
  NOTIFICATION_PREFERENCE_SERVICE_TOKEN,
  NOTIFICATION_REPOSITORY_TOKEN,
  NOTIFICATION_SERVICE_TOKEN,
  REMINDER_ACTIVITY_CLIENT_TOKEN,
  REMINDER_MATERIALIZATION_SERVICE_TOKEN,
  REMINDER_CLOCK_TOKEN,
  REMINDER_SCHEDULER_TOKEN,
  WELLBEING_DIGEST_REPOSITORY_TOKEN,
  WELLBEING_DIGEST_SERVICE_TOKEN,
  WELLBEING_EMAIL_DELIVERY_TOKEN,
  WELLBEING_RECIPIENT_CLIENT_TOKEN,
  WELLBEING_DIGEST_SCHEDULER_TOKEN,
} from './application.tokens.js';
import type { ServiceConfiguration } from './configuration/configuration.js';
import { DatabaseService, type ReadinessProbe } from './database/database.service.js';
import { HealthController } from './health/health.controller.js';
import { ResourceController } from './resources/resource.controller.js';
import { ResourceRepository } from './resources/resource.repository.js';
import { ResourceService } from './resources/resource.service.js';
import { ResourceProgressController } from './resources/resource-progress.controller.js';
import { ResourceProgressRepository } from './resources/resource-progress.repository.js';
import { ResourceProgressService } from './resources/resource-progress.service.js';
import { ResourceJourneyController } from './resources/resource-journey.controller.js';
import { ResourceJourneyRepository } from './resources/resource-journey.repository.js';
import { ResourceJourneyService } from './resources/resource-journey.service.js';
import { E2eOutageController } from './e2e/e2e-outage.controller.js';
import { AuthModule } from './auth/auth.module.js';
import { JwtStrategy } from './auth/jwt.strategy.js';
import { RolesGuard } from './auth/roles.guard.js';
import { ResourceEligibilityController } from './resources/resource-eligibility.controller.js';
import { ResourceEligibilityRepository } from './resources/resource-eligibility.repository.js';
import { ResourceEligibilityService } from './resources/resource-eligibility.service.js';
import {
  SafetyDirectoryController,
  SafetyDirectoryLookupController,
} from './safety-directory/safety-directory.controller.js';
import { SafetyDirectoryRepository } from './safety-directory/safety-directory.repository.js';
import { SafetyDirectoryService } from './safety-directory/safety-directory.service.js';
import { NotificationPreferenceController } from './notification-preferences/notification-preference.controller.js';
import { NotificationPreferenceRepository } from './notification-preferences/notification-preference.repository.js';
import { NotificationPreferenceService } from './notification-preferences/notification-preference.service.js';
import { NotificationController } from './notifications/notification.controller.js';
import { NotificationRepository } from './notifications/notification.repository.js';
import { NotificationService } from './notifications/notification.service.js';
import { JournalAiReminderActivityClient } from './reminders/reminder-activity.client.js';
import { ReminderMaterializationService } from './reminders/reminder-materialization.service.js';
import type { ReminderActivityClient } from './reminders/reminder-activity.client.js';
import type { ReminderClock } from './reminders/reminder-materialization.service.js';
import { ReminderScheduler } from './reminders/reminder.scheduler.js';
import {
  AccountLifecycleConsumer,
  AccountLifecycleProjector,
} from './notification-preferences/account-lifecycle.consumer.js';
import { WellbeingDigestController } from './wellbeing-digest/wellbeing-digest.controller.js';
import { WellbeingDigestRepository } from './wellbeing-digest/wellbeing-digest.repository.js';
import { WellbeingDigestService } from './wellbeing-digest/wellbeing-digest.service.js';
import { WellbeingDigestScheduler } from './wellbeing-digest/wellbeing-digest.scheduler.js';
import {
  BrevoWellbeingEmailDelivery,
  IdentityWellbeingRecipientClient,
} from './wellbeing-digest/wellbeing-delivery.clients.js';

export interface ApplicationDependencies {
  readonly readinessProbe?: ReadinessProbe;
  readonly resourceRepository?: ResourceRepository;
  readonly resourceProgressRepository?: ResourceProgressRepository;
  readonly resourceJourneyRepository?: ResourceJourneyRepository;
  readonly outageState?: { enabled: boolean };
  readonly resourceEligibilityRepository?: ResourceEligibilityRepository;
  readonly safetyDirectoryRepository?: SafetyDirectoryRepository;
  readonly notificationPreferenceRepository?: NotificationPreferenceRepository;
  readonly notificationRepository?: NotificationRepository;
  readonly reminderActivityClient?: ReminderActivityClient;
  readonly reminderClock?: ReminderClock;
  readonly wellbeingDigestService?: WellbeingDigestService;
}

@Module({})
class ContentNotificationModule {}

export const createAppModule = (
  configuration: ServiceConfiguration,
  dependencies: ApplicationDependencies = {},
): DynamicModule => {
  const readinessProvider: Provider = dependencies.readinessProbe
    ? { provide: READINESS_PROBE_TOKEN, useValue: dependencies.readinessProbe }
    : { provide: READINESS_PROBE_TOKEN, useExisting: DatabaseService };

  const dbServiceProvider: Provider = {
    provide: DATABASE_SERVICE_TOKEN,
    useExisting: DatabaseService,
  };

  const repositoryProvider: Provider = dependencies.resourceRepository
    ? { provide: RESOURCE_REPOSITORY_TOKEN, useValue: dependencies.resourceRepository }
    : { provide: RESOURCE_REPOSITORY_TOKEN, useClass: ResourceRepository };

  const serviceProvider: Provider = {
    provide: RESOURCE_SERVICE_TOKEN,
    useClass: ResourceService,
  };
  const resourceProgressRepositoryProvider: Provider = dependencies.resourceProgressRepository
    ? {
        provide: RESOURCE_PROGRESS_REPOSITORY_TOKEN,
        useValue: dependencies.resourceProgressRepository,
      }
    : { provide: RESOURCE_PROGRESS_REPOSITORY_TOKEN, useClass: ResourceProgressRepository };
  const resourceJourneyRepositoryProvider: Provider = dependencies.resourceJourneyRepository
    ? {
        provide: RESOURCE_JOURNEY_REPOSITORY_TOKEN,
        useValue: dependencies.resourceJourneyRepository,
      }
    : { provide: RESOURCE_JOURNEY_REPOSITORY_TOKEN, useClass: ResourceJourneyRepository };
  const eligibilityRepositoryProvider: Provider = dependencies.resourceEligibilityRepository
    ? {
        provide: RESOURCE_ELIGIBILITY_REPOSITORY_TOKEN,
        useValue: dependencies.resourceEligibilityRepository,
      }
    : { provide: RESOURCE_ELIGIBILITY_REPOSITORY_TOKEN, useClass: ResourceEligibilityRepository };
  const safetyDirectoryRepositoryProvider: Provider = dependencies.safetyDirectoryRepository
    ? {
        provide: SAFETY_DIRECTORY_REPOSITORY_TOKEN,
        useValue: dependencies.safetyDirectoryRepository,
      }
    : { provide: SAFETY_DIRECTORY_REPOSITORY_TOKEN, useClass: SafetyDirectoryRepository };
  const notificationPreferenceRepositoryProvider: Provider =
    dependencies.notificationPreferenceRepository
      ? {
          provide: NOTIFICATION_PREFERENCE_REPOSITORY_TOKEN,
          useValue: dependencies.notificationPreferenceRepository,
        }
      : {
          provide: NOTIFICATION_PREFERENCE_REPOSITORY_TOKEN,
          useClass: NotificationPreferenceRepository,
        };
  const notificationRepositoryProvider: Provider = dependencies.notificationRepository
    ? { provide: NOTIFICATION_REPOSITORY_TOKEN, useValue: dependencies.notificationRepository }
    : { provide: NOTIFICATION_REPOSITORY_TOKEN, useClass: NotificationRepository };
  const reminderActivityClientProvider: Provider = dependencies.reminderActivityClient
    ? { provide: REMINDER_ACTIVITY_CLIENT_TOKEN, useValue: dependencies.reminderActivityClient }
    : { provide: REMINDER_ACTIVITY_CLIENT_TOKEN, useClass: JournalAiReminderActivityClient };

  return {
    module: ContentNotificationModule,
    imports: [AuthModule],
    controllers: [
      HealthController,
      ResourceController,
      ResourceProgressController,
      ResourceJourneyController,
      ResourceEligibilityController,
      E2eOutageController,
      SafetyDirectoryController,
      SafetyDirectoryLookupController,
      NotificationPreferenceController,
      NotificationController,
      WellbeingDigestController,
    ],
    providers: [
      { provide: CONFIGURATION_TOKEN, useValue: configuration },
      {
        provide: E2E_OUTAGE_STATE_TOKEN,
        useValue: dependencies.outageState ?? { enabled: false },
      },
      DatabaseService,
      readinessProvider,
      dbServiceProvider,
      repositoryProvider,
      serviceProvider,
      resourceProgressRepositoryProvider,
      { provide: RESOURCE_PROGRESS_SERVICE_TOKEN, useClass: ResourceProgressService },
      resourceJourneyRepositoryProvider,
      { provide: RESOURCE_JOURNEY_SERVICE_TOKEN, useClass: ResourceJourneyService },
      eligibilityRepositoryProvider,
      { provide: RESOURCE_ELIGIBILITY_SERVICE_TOKEN, useClass: ResourceEligibilityService },
      safetyDirectoryRepositoryProvider,
      { provide: SAFETY_DIRECTORY_SERVICE_TOKEN, useClass: SafetyDirectoryService },
      notificationPreferenceRepositoryProvider,
      { provide: NOTIFICATION_PREFERENCE_SERVICE_TOKEN, useClass: NotificationPreferenceService },
      notificationRepositoryProvider,
      { provide: NOTIFICATION_SERVICE_TOKEN, useClass: NotificationService },
      reminderActivityClientProvider,
      {
        provide: REMINDER_MATERIALIZATION_SERVICE_TOKEN,
        useClass: ReminderMaterializationService,
      },
      {
        provide: REMINDER_CLOCK_TOKEN,
        useValue: dependencies.reminderClock ?? { now: () => new Date() },
      },
      { provide: REMINDER_SCHEDULER_TOKEN, useClass: ReminderScheduler },
      { provide: WELLBEING_DIGEST_REPOSITORY_TOKEN, useClass: WellbeingDigestRepository },
      { provide: WELLBEING_EMAIL_DELIVERY_TOKEN, useClass: BrevoWellbeingEmailDelivery },
      { provide: WELLBEING_RECIPIENT_CLIENT_TOKEN, useClass: IdentityWellbeingRecipientClient },
      dependencies.wellbeingDigestService
        ? { provide: WELLBEING_DIGEST_SERVICE_TOKEN, useValue: dependencies.wellbeingDigestService }
        : { provide: WELLBEING_DIGEST_SERVICE_TOKEN, useClass: WellbeingDigestService },
      { provide: WELLBEING_DIGEST_SCHEDULER_TOKEN, useClass: WellbeingDigestScheduler },
      AccountLifecycleProjector,
      AccountLifecycleConsumer,
      JwtStrategy,
      {
        provide: RolesGuard,
        useFactory: (reflector: Reflector) => new RolesGuard(reflector),
        inject: [Reflector],
      },
    ],
  };
};
