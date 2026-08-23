import {
  Module,
  type DynamicModule,
  type MiddlewareConsumer,
  type NestModule,
  type Provider,
} from "@nestjs/common";
import { APP_GUARD } from "@nestjs/core";

import type { ServiceConfiguration } from "./configuration/configuration.js";
import { HealthController } from "./health/health.controller.js";
import {
  MongoReadinessProbe,
  type ReadinessProbe,
} from "./health/readiness.js";
import { createLogger } from "./observability/logger.js";
import { createMetrics } from "./observability/metrics.js";
import { MetricsController } from "./observability/metrics.controller.js";
import { RequestLoggingMiddleware } from "./observability/request-logging.middleware.js";
import {
  CONFIGURATION_TOKEN,
  LOGGER_TOKEN,
  METRICS_TOKEN,
  READINESS_PROBE_TOKEN,
} from "./observability/tokens.js";
import { IdentityJwtVerifier } from "./security/identity-jwt-verifier.js";
import { JwtAuthenticationGuard } from "./security/jwt-authentication.guard.js";
import { registerJournalModule } from "./journals/journal.js";

export interface ApplicationDependencies {
  readonly readinessProbe?: ReadinessProbe;
}

@Module({})
export class AppModule implements NestModule {
  static register(
    configuration: ServiceConfiguration,
    dependencies: ApplicationDependencies = {},
  ): DynamicModule {
    const readinessProvider: Provider = dependencies.readinessProbe
      ? {
          provide: READINESS_PROBE_TOKEN,
          useValue: dependencies.readinessProbe,
        }
      : {
          provide: READINESS_PROBE_TOKEN,
          useClass: MongoReadinessProbe,
        };

    return {
      module: AppModule,
      imports: [registerJournalModule(configuration)],
      controllers: [HealthController, MetricsController],
      providers: [
        RequestLoggingMiddleware,
        {
          provide: CONFIGURATION_TOKEN,
          useValue: configuration,
        },
        {
          provide: LOGGER_TOKEN,
          useValue: createLogger(configuration),
        },
        {
          provide: METRICS_TOKEN,
          useValue: createMetrics(configuration),
        },
        readinessProvider,
        IdentityJwtVerifier,
        {
          provide: APP_GUARD,
          useClass: JwtAuthenticationGuard,
        },
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(RequestLoggingMiddleware).forRoutes("*");
  }
}
