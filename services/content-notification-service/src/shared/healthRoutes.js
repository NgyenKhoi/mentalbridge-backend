const express = require('express');
const db = require('../infrastructure/database/db');

const router = express.Router();

router.get('/live', (req, res) => res.json({ status: 'ok' }));

router.get('/ready', async (req, res) => {
  try {
    await db.query('SELECT 1');
    res.json({ status: 'ok', db: 'connected' });
  } catch (err) {
    res.status(503).json({ status: 'unavailable', db: 'disconnected' });
  }
});

module.exports = router;
