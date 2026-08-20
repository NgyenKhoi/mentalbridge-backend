import express, { type Express } from "express";

export const createApp = (): Express => {
  const app = express();

  app.disable("x-powered-by");
  app.use(express.json({ limit: "1mb" }));

  return app;
};