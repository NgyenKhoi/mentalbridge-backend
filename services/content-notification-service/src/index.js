require('dotenv').config();
const app = require('./app');
const logger = require('./shared/logger');

const PORT = process.env.PORT || 3003;

app.listen(PORT, () => {
  logger.info({ event: 'server_started', port: PORT, service: 'content-notification-service' });
});
