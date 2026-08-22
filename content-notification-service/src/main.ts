import { createApplication } from './application.js';
import { loadConfiguration } from './configuration/configuration.js';

const configuration = loadConfiguration();
const app = await createApplication(configuration);

app.enableShutdownHooks(['SIGINT', 'SIGTERM']);
await app.listen(configuration.PORT);
