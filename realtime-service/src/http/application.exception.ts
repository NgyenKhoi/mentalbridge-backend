import { HttpException } from '@nestjs/common';

export class ApplicationException extends HttpException {
  constructor(
    status: number,
    readonly code: string,
    message: string,
    readonly retryable = false,
  ) {
    super(message, status);
  }
}
