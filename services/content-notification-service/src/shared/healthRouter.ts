import { Router, Request, Response } from 'express';
import { query } from '../infrastructure/database/db';

const router = Router();

router.get('/live', (_req: Request, res: Response) => {
  res.json({ status: 'ok' });
});

router.get('/ready', async (_req: Request, res: Response) => {
  try {
    await query('SELECT 1');
    res.json({ status: 'ok', db: 'connected' });
  } catch {
    res.status(503).json({ status: 'unavailable', db: 'disconnected' });
  }
});

export default router;
