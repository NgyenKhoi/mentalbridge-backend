const logger = require('./logger');

/**
 * RFC 9457 Problem Details error handler
 */
function errorHandler(err, req, res, next) {
  const correlationId = req.headers['x-correlation-id'] || null;

  if (err.type === 'domain') {
    return res.status(err.status || 400).json({
      type: `https://mentalbridge.io/errors/${err.code}`,
      title: err.message,
      status: err.status || 400,
      code: err.code,
      correlationId,
    });
  }

  logger.error({ event: 'unhandled_error', error: err.message, stack: err.stack, correlationId });

  return res.status(500).json({
    type: 'https://mentalbridge.io/errors/INTERNAL_ERROR',
    title: 'An unexpected error occurred.',
    status: 500,
    code: 'INTERNAL_ERROR',
    correlationId,
  });
}

function domainError(code, message, status = 400) {
  const err = new Error(message);
  err.type = 'domain';
  err.code = code;
  err.status = status;
  return err;
}

module.exports = { errorHandler, domainError };
