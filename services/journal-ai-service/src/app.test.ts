import test from "node:test";
import type { Server } from "node:http";
import assert from "node:assert/strict";

import request from "supertest";

import { createApplication } from "./app.js";
import {
  loadConfiguration,
  type ServiceConfiguration,
} from "./configuration/configuration.js";

const testConfiguration: ServiceConfiguration = {
  NODE_ENV: "test",
  PORT: 3000,
  LOG_LEVEL: "silent",
  SERVICE_NAME: "journal-ai-service",
};

void test("returns health liveness", async () => {
  const app = await createApplication(testConfiguration);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/health/live")
      .expect(200)
      .expect({
        status: "ok",
        service: "journal-ai-service",
        environment: "test",
      });
  } finally {
    await app.close();
  }
});

void test("returns 404 for an unknown route", async () => {
  const app = await createApplication(testConfiguration);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server).get("/unknown").expect(404);
  } finally {
    await app.close();
  }
});

void test("returns readiness with a correlation id", async () => {
  const app = await createApplication(testConfiguration);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/health/ready")
      .set("x-correlation-id", "test-correlation-id")
      .expect("x-correlation-id", "test-correlation-id")
      .expect(200);
  } finally {
    await app.close();
  }
});

void test("returns prometheus metrics", async () => {
  const app = await createApplication(testConfiguration);
  await app.init();

  try {
    const server = app.getHttpServer() as unknown as Server;

    await request(server)
      .get("/metrics")
      .expect("Content-Type", /text\/plain/)
      .expect(200)
      .expect((response) => {
        if (!response.text.includes("journal_ai_health_checks_total")) {
          throw new Error("Expected health metric to be exposed");
        }
      });
  } finally {
    await app.close();
  }
});

void test("validates configuration", () => {
  const configuration = loadConfiguration({
    NODE_ENV: "test",
    PORT: "3100",
    LOG_LEVEL: "debug",
    SERVICE_NAME: "journal-ai-service",
  });

  assert.equal(configuration.PORT, 3100);
  assert.equal(configuration.LOG_LEVEL, "debug");
});

void test("rejects an invalid port", () => {
  assert.throws(() =>
    loadConfiguration({
      NODE_ENV: "test",
      PORT: "70000",
      LOG_LEVEL: "info",
      SERVICE_NAME: "journal-ai-service",
    }),
  );
});
