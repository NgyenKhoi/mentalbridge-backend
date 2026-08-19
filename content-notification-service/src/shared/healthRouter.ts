import { Router, Request, Response } from 'express';
import { query } from '../infrastructure/database/db.js';

const router = Router();

// Liveness: never depends on DB
router.get('/live', (_req: Request, res: Response) => {
  res.json({ status: 'ok' });
});

// Readiness: checks DB connectivity
router.get('/ready', async (_req: Request, res: Response) => {
  try {
    await query('SELECT 1');
    res.json({ status: 'ok', db: 'connected' });
  } catch {
    res.status(503).json({ status: 'unavailable', db: 'disconnected' });
  }
});

export default router;
