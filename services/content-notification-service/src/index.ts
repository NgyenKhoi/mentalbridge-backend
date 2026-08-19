import 'dotenv/config';
import app from './app';
import logger from './shared/logger';

const PORT = parseInt(process.env.PORT ?? '3003', 10);

app.listen(PORT, () => {
  logger.info({ event: 'server_started', port: PORT, service: 'content-notification-service' });
});
