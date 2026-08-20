import test from "node:test";

import request from "supertest";

import { createApp } from "./app.js";

void test("returns 404 for an unknown route", async () => {
  await request(createApp()).get("/unknown").expect(404);
});