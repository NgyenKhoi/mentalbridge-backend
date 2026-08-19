import express from 'express';
import cors from 'cors';
import { pinoHttp } from 'pino-http';
import healthRouter from './shared/healthRouter.js';
import { errorHandler } from './shared/errorHandler.js';
import logger from './shared/logger.js';

const app = express();

app.use(pinoHttp({ logger }));

// CORS: deny by default, allow only configured origins
const allowedOrigins = process.env.CORS_ORIGINS?.split(',').map((o) => o.trim()) ?? [];
app.use(
  cors({
    origin: allowedOrigins.length > 0 ? allowedOrigins : false,
    credentials: true,
  }),
);

app.use(express.json());

app.use('/health', healthRouter);

// API routes — to be implemented in Story 209
// app.use('/api/v1/resources', resourceRouter);
// app.use('/api/v1/hotlines', hotlineRouter);

app.use(errorHandler);

export default app;
