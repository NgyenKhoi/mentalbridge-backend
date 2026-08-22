import {
  type ArgumentsHost,
  Catch,
  type ExceptionFilter,
  HttpException,
  HttpStatus,
} from "@nestjs/common";

interface ErrorResponse {
  status(code: number): ErrorResponse;
  setHeader(name: string, value: string): void;
  json(body: ProblemDetails): void;
}

interface ErrorRequest {
  readonly id?: string;
}

interface ProblemDetails {
  readonly type: string;
  readonly title: string;
  readonly status: number;
  readonly code: string;
  readonly correlationId: string;
}

interface ProblemDefinition {
  readonly type: string;
  readonly title: string;
  readonly code: string;
}

const definitions: Readonly<Record<number, ProblemDefinition>> = {
  [HttpStatus.BAD_REQUEST]: {
    type: "https://mentalbridge.dev/problems/validation-failed",
    title: "Request validation failed",
    code: "VALIDATION_FAILED",
  },
  [HttpStatus.UNAUTHORIZED]: {
    type: "https://mentalbridge.dev/problems/authentication-required",
    title: "Authentication is required",
    code: "AUTHENTICATION_REQUIRED",
  },
  [HttpStatus.FORBIDDEN]: {
    type: "https://mentalbridge.dev/problems/access-denied",
    title: "Access is denied",
    code: "ACCESS_DENIED",
  },
  [HttpStatus.NOT_FOUND]: {
    type: "https://mentalbridge.dev/problems/resource-not-found",
    title: "Resource was not found",
    code: "RESOURCE_NOT_FOUND",
  },
  [HttpStatus.CONFLICT]: {
    type: "https://mentalbridge.dev/problems/conflict",
    title: "The request conflicts with current state",
    code: "CONFLICT",
  },
  [HttpStatus.PRECONDITION_FAILED]: {
    type: "https://mentalbridge.dev/problems/precondition-failed",
    title: "A request precondition failed",
    code: "PRECONDITION_FAILED",
  },
  [HttpStatus.SERVICE_UNAVAILABLE]: {
    type: "https://mentalbridge.dev/problems/dependency-unavailable",
    title: "A required dependency is unavailable",
    code: "DEPENDENCY_UNAVAILABLE",
  },
};

const internalError: ProblemDefinition = {
  type: "https://mentalbridge.dev/problems/internal-error",
  title: "An internal error occurred",
  code: "INTERNAL_ERROR",
};

@Catch()
export class ProblemDetailsFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const request = http.getRequest<ErrorRequest>();
    const response = http.getResponse<ErrorResponse>();
    const status =
      exception instanceof HttpException
        ? exception.getStatus()
        : HttpStatus.INTERNAL_SERVER_ERROR;
    const definition = definitions[status] ?? internalError;

    response.setHeader("Content-Type", "application/problem+json");
    response.status(status).json({
      ...definition,
      status,
      correlationId: request.id ?? "unavailable",
    });
  }
}
