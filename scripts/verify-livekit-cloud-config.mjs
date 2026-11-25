import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { resolve } from "node:path";

// Read-only operator check. No credentials/JWTs/room metadata enter output.
try {
  process.loadEnvFile(resolve(".env"));
  const url = new URL(process.env.LIVEKIT_URL ?? "");
  assert(
    url.protocol === "wss:" &&
      /^[a-z0-9-]+\.livekit\.cloud$/.test(url.hostname) &&
      !url.username &&
      !url.password &&
      !url.search &&
      !url.hash &&
      !url.port &&
      ["/", ""].includes(url.pathname),
    "Invalid LiveKit Cloud URL",
  );
  const key = process.env.LIVEKIT_API_KEY;
  const secret = process.env.LIVEKIT_API_SECRET;
  assert(key && secret, "LiveKit credentials are missing");
  const now = Math.floor(Date.now() / 1000);
  const encode = (value) =>
    Buffer.from(JSON.stringify(value)).toString("base64url");
  const payload = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({ iss: key, nbf: now - 5, exp: now + 60, video: { roomList: true } })}`;
  const token = `${payload}.${createHmac("sha256", secret).update(payload).digest("base64url")}`;
  const response = await fetch(
    `https://${url.hostname}/twirp/livekit.RoomService/ListRooms`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: "{}",
      signal: AbortSignal.timeout(10000),
    },
  );
  if (!response.ok) {
    console.error(
      `LiveKit credential probe rejected: HTTP ${response.status}. No response body retained.`,
    );
    process.exitCode = 1;
  } else {
    await response.arrayBuffer();
    console.log(
      "PASS: existing LiveKit Cloud credentials accepted by authenticated read-only RoomService. No room created; this is not a media-call test.",
    );
  }
} catch {
  console.error(
    "LiveKit credential probe failed: check local configuration and network availability. No secret or provider payload retained.",
  );
  process.exitCode = 1;
}
