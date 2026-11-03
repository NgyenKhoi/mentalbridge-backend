import assert from "node:assert/strict";
import test from "node:test";
import { loadConfiguration } from "../configuration/configuration.js";
import { ResourceCatalog, catalogPrompt } from "./resource-catalog.js";

const configuration = loadConfiguration({
  NODE_ENV: "test",
  IDENTITY_JWT_ISSUER: "https://identity.test.mentalbridge",
  IDENTITY_JWT_AUDIENCE: "mentalbridge-api",
  IDENTITY_JWT_KEY_ID: "test-key",
  IDENTITY_JWT_PUBLIC_KEY: "test-public-key",
  JOURNAL_AI_CONTENT_BASE_URL: "http://content.test",
});
const id = (index: number) =>
  `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`;
const resource = (index: number, title = `Bài tập ${String(index)}`) => ({
  id: id(index),
  category: "BREATHING",
  locale: "vi-VN",
  title,
  summary: "Không gửi cho mô hình",
  status: "PUBLISHED",
});
const withFetch = async (
  handler: (url: URL, init?: RequestInit) => Promise<Response>,
  run: () => Promise<void>,
) => {
  const original = globalThis.fetch;
  globalThis.fetch = (url, init) =>
    handler(
      url instanceof Request ? new URL(url.url) : new URL(String(url)),
      init,
    );
  try {
    await run();
  } finally {
    globalThis.fetch = original;
  }
};

void test("requests published vi-VN resources, limit 30, without authorization", async () => {
  await withFetch(
    (url, init) => {
      assert.equal(url.origin, "http://content.test");
      assert.equal(url.pathname, "/api/v1/resources");
      assert.equal(url.searchParams.get("locale"), "vi-VN");
      assert.equal(url.searchParams.get("limit"), "30");
      assert.equal(new Headers(init?.headers).has("authorization"), false);
      return Promise.resolve(
        Response.json({ data: [resource(1), resource(2)], count: 2 }),
      );
    },
    async () => {
      const items = await new ResourceCatalog(configuration).items();
      assert.deepEqual(items, [
        {
          ref: "R1",
          resourceId: id(1),
          category: "BREATHING",
          title: "Bài tập 1",
        },
        {
          ref: "R2",
          resourceId: id(2),
          category: "BREATHING",
          title: "Bài tập 2",
        },
      ]);
    },
  );
});

void test("serves the cache for 10 minutes, then refetches", async () => {
  let calls = 0;
  let now = 0;
  await withFetch(
    () => {
      calls++;
      return Promise.resolve(
        Response.json({ data: [resource(calls)], count: 1 }),
      );
    },
    async () => {
      const catalog = new ResourceCatalog(configuration, () => now);
      await catalog.items();
      now = 10 * 60_000 - 1;
      assert.equal((await catalog.items())[0]?.title, "Bài tập 1");
      now = 10 * 60_000;
      assert.equal((await catalog.items())[0]?.title, "Bài tập 2");
      assert.equal(calls, 2);
    },
  );
});

void test("failures give an empty catalogue and are not cached", async () => {
  for (const failure of [
    () => Promise.reject(new DOMException("timeout", "TimeoutError")),
    () => Promise.resolve(new Response("down", { status: 503 })),
    () => Promise.resolve(Response.json({ unexpected: true })),
    () =>
      Promise.resolve(
        Response.json({ data: [], count: 0, fallback: "unavailable" }),
      ),
  ]) {
    let calls = 0;
    await withFetch(
      () => {
        calls++;
        return calls === 1
          ? failure()
          : Promise.resolve(Response.json({ data: [resource(1)], count: 1 }));
      },
      async () => {
        const catalog = new ResourceCatalog(configuration);
        assert.deepEqual(await catalog.items(), []);
        assert.equal((await catalog.items()).length, 1);
      },
    );
  }
});

void test("skips items with a non-uuid id or a title over 200 characters", async () => {
  await withFetch(
    () =>
      Promise.resolve(
        Response.json({
          data: [
            { ...resource(1), id: "not-a-uuid" },
            resource(2, "ệ".repeat(201)),
            resource(3),
          ],
          count: 3,
        }),
      ),
    async () => {
      const items = await new ResourceCatalog(configuration).items();
      assert.deepEqual(
        items.map((item) => [item.ref, item.resourceId]),
        [["R1", id(3)]],
      );
    },
  );
});

void test("catalogPrompt sends only ref, category and title within 1500 bytes", () => {
  const items = Array.from({ length: 30 }, (_, index) => ({
    ref: `R${String(index + 1)}`,
    resourceId: id(index + 1),
    category: "MEDITATION",
    title: `Thiền chánh niệm buổi tối số ${String(index + 1)}`,
  }));
  const prompt = catalogPrompt(items);
  assert.ok(Buffer.byteLength(JSON.stringify(prompt)) <= 1_500);
  assert.ok(prompt.length > 0 && prompt.length < 30);
  assert.deepEqual(prompt[0], {
    ref: "R1",
    category: "MEDITATION",
    title: "Thiền chánh niệm buổi tối số 1",
  });
  assert.deepEqual(catalogPrompt([]), []);
});
