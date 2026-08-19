import express from 'express';
import cors from 'cors';
import healthRouter from './shared/healthRouter';
import { errorHandler } from './shared/errorHandler';

const app = express();

app.use(cors());
app.use(express.json());

app.use('/health', healthRouter);

// API routes to be added in subsequent tasks
// app.use('/api/v1/resources', resourceRouter);
// app.use('/api/v1/hotlines', hotlineRouter);
// app.use('/api/v1/notifications', notificationRouter);

app.use(errorHandler);

export default app;
