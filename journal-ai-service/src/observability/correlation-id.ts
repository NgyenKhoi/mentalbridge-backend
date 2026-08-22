import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

const correlationHeader = "x-correlation-id";
const correlationIdPattern = /^[A-Za-z0-9._:-]{1,128}$/;

export const getCorrelationId = (request: IncomingMessage): string => {
  const headerValue = request.headers[correlationHeader];

  if (Array.isArray(headerValue)) {
    const firstValue = headerValue[0];
    return firstValue && correlationIdPattern.test(firstValue)
      ? firstValue
      : randomUUID();
  }

  return headerValue && correlationIdPattern.test(headerValue)
    ? headerValue
    : randomUUID();
};

export const setCorrelationIdHeader = (
  response: ServerResponse,
  correlationId: string,
): void => {
  response.setHeader(correlationHeader, correlationId);
};
