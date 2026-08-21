import { createApplication } from "./app.js";
const configuration = (await import("./configuration.js")).loadConfiguration();
const app = await createApplication(configuration);
await app.listen(configuration.PORT);
