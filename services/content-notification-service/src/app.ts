import express from 'express';
import cors from 'cors';
import pinoHttp from 'pino-http';
import healthRouter from './shared/healthRouter';
import { errorHandler } from './shared/errorHandler';
import logger from './shared/logger';

const app = express();

// Request logging
app.use(pinoHttp({ logger }));

app.use(cors());
app.use(express.json());

app.use('/health', healthRouter);

// Metrics endpoint
app.get('/metrics', (_req, res) => {
  res.json({
    service: 'content-notification-service',
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
    memory: process.memoryUsage(),
  });
});

// API routes to be added in subsequent tasks
// app.use('/api/v1/resources', resourceRouter);
// app.use('/api/v1/hotlines', hotlineRouter);
// app.use('/api/v1/notifications', notificationRouter);

app.use(errorHandler);

export default app;
