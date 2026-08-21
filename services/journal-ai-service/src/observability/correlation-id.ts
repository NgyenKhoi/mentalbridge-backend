import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

const correlationHeader = "x-correlation-id";

export const getCorrelationId = (request: IncomingMessage): string => {
  const headerValue = request.headers[correlationHeader];

  if (Array.isArray(headerValue)) {
    return headerValue[0] ?? randomUUID();
  }

  return headerValue ?? randomUUID();
};

export const setCorrelationIdHeader = (
  response: ServerResponse,
  correlationId: string,
): void => {
  response.setHeader(correlationHeader, correlationId);
};
