const express = require('express');
const cors = require('cors');
const healthRoutes = require('./shared/healthRoutes');

const app = express();

app.use(cors());
app.use(express.json());

app.use('/health', healthRoutes);

module.exports = app;
