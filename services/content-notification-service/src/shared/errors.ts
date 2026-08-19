export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  code: string;
  correlationId?: string | null;
  fieldViolations?: unknown[];
}

export class DomainError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number = 400,
  ) {
    super(message);
    this.name = 'DomainError';
  }
}

export function domainError(code: string, message: string, status = 400): DomainError {
  return new DomainError(code, message, status);
}
