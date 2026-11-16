import { crc32 } from "node:zlib";
import {
  VoiceProviderFailure,
  voiceStopReason,
  type VoiceObserver,
} from "./voice-diagnostics.js";

const maxFrameBytes = 1024 * 1024;
const maxResponseBytes = 2 * maxFrameBytes;
const maxFrames = 4096;
const utf8 = new TextDecoder("utf-8", { fatal: true });
function invalid(): never {
  throw new VoiceProviderFailure("STREAM_PROTOCOL_INVALID");
}
const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const index = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) < 16;
const tokenCount = (value: unknown): value is number =>
  Number.isSafeInteger(value) && Number(value) >= 0;
const observe = (
  observer: VoiceObserver | undefined,
  value: Parameters<VoiceObserver>[0],
) => {
  try {
    observer?.(value);
  } catch {
    return;
  }
};
const aborted = (signal: AbortSignal) =>
  new VoiceProviderFailure(
    signal.reason instanceof Error && signal.reason.name === "TimeoutError"
      ? "TIMEOUT"
      : "ABORTED",
  );

function headers(bytes: Buffer): Map<string, string> {
  const selected = new Map<string, string>();
  let offset = 0;
  while (offset < bytes.length) {
    const nameLength = bytes[offset++] ?? invalid();
    if (!nameLength || offset + nameLength + 1 > bytes.length) invalid();
    const name = utf8.decode(bytes.subarray(offset, offset + nameLength));
    offset += nameLength;
    const type = bytes[offset++] ?? invalid();
    let length: number;
    if (type === 0 || type === 1) length = 0;
    else if (type === 2) length = 1;
    else if (type === 3) length = 2;
    else if (type === 4) length = 4;
    else if (type === 5 || type === 8) length = 8;
    else if (type === 9) length = 16;
    else if (type === 6 || type === 7) {
      if (offset + 2 > bytes.length) invalid();
      length = bytes.readUInt16BE(offset);
      offset += 2;
    } else invalid();
    if (offset + length > bytes.length) invalid();
    if (
      [
        ":message-type",
        ":event-type",
        ":content-type",
        ":exception-type",
      ].includes(name)
    ) {
      if (type !== 7 || selected.has(name)) invalid();
      selected.set(name, utf8.decode(bytes.subarray(offset, offset + length)));
    }
    offset += length;
  }
  return selected;
}

async function nextChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal,
): Promise<ReadableStreamReadResult<Uint8Array>> {
  if (signal.aborted) throw aborted(signal);
  return new Promise((resolve, reject) => {
    const stop = () => {
      reject(aborted(signal));
      void reader.cancel().catch(() => undefined);
    };
    signal.addEventListener("abort", stop, { once: true });
    void reader
      .read()
      .then(resolve, () => {
        reject(
          signal.aborted
            ? aborted(signal)
            : new VoiceProviderFailure("TRANSPORT"),
        );
      })
      .finally(() => {
        signal.removeEventListener("abort", stop);
      });
    if (signal.aborted) stop();
  });
}

export const bedrockConverseStreamUrl = (region: string, model: string): URL =>
  new URL(
    `/model/${encodeURIComponent(model)}/converse-stream`,
    `https://bedrock-runtime.${region}.amazonaws.com`,
  );

export async function readBedrockConverseStream(
  response: Response,
  signal: AbortSignal,
  observer?: VoiceObserver,
): Promise<unknown> {
  if (
    response.headers
      .get("content-type")
      ?.split(";")[0]
      ?.trim()
      .toLowerCase() !== "application/vnd.amazon.eventstream" ||
    !response.body
  ) {
    void response.body?.cancel().catch(() => undefined);
    invalid();
  }
  let reader: ReadableStreamDefaultReader<Uint8Array>;
  try {
    reader = response.body.getReader();
  } catch {
    invalid();
  }
  let pending = Buffer.alloc(0);
  let responseBytes = 0;
  let frames = 0;
  let started = false;
  let stopped = false;
  let metadata = false;
  let firstText = false;
  let text = "";
  let stopReason: string | undefined;
  let usage: { inputTokens: number; outputTokens: number } | undefined;
  const blocks = new Map<number, boolean>();
  const activeBlock = (value: unknown) => {
    if (!index(value) || stopped || !started) invalid();
    if (!blocks.has(value)) {
      if (value !== blocks.size || [...blocks.values()].some(Boolean))
        invalid();
      blocks.set(value, true);
    }
    if (!blocks.get(value)) invalid();
    return value;
  };
  try {
    for (;;) {
      const chunk = await nextChunk(reader, signal);
      if (chunk.done) break;
      responseBytes += chunk.value.byteLength;
      if (responseBytes > maxResponseBytes) invalid();
      pending = Buffer.concat([pending, chunk.value]);
      while (pending.length >= 12) {
        if (signal.aborted) throw aborted(signal);
        const size = pending.readUInt32BE(0);
        const headerLength = pending.readUInt32BE(4);
        if (
          size < 16 ||
          size > maxFrameBytes ||
          headerLength > 16384 ||
          headerLength > size - 16 ||
          crc32(pending.subarray(0, 8)) !== pending.readUInt32BE(8)
        )
          invalid();
        if (pending.length < size) break;
        const frame = pending.subarray(0, size);
        pending = pending.subarray(size);
        if (
          ++frames > maxFrames ||
          crc32(frame.subarray(0, size - 4)) !== frame.readUInt32BE(size - 4)
        )
          invalid();
        const envelope = headers(frame.subarray(12, 12 + headerLength));
        const kind = envelope.get(":message-type");
        if (kind === "exception" || kind === "error")
          throw new VoiceProviderFailure("STREAM_PROVIDER_EXCEPTION");
        if (
          kind !== "event" ||
          envelope.get(":content-type") !== "application/json"
        )
          invalid();
        const event = envelope.get(":event-type");
        const body: unknown = JSON.parse(
          utf8.decode(frame.subarray(12 + headerLength, size - 4)),
        );
        if (!object(body) || metadata) invalid();
        if (event === "messageStart") {
          if (started || body.role !== "assistant") invalid();
          started = true;
        } else if (event === "contentBlockStart") {
          if (
            !object(body.start) ||
            Object.keys(body.start).length !== 0 ||
            blocks.has(Number(body.contentBlockIndex))
          )
            invalid();
          activeBlock(body.contentBlockIndex);
        } else if (event === "contentBlockDelta") {
          activeBlock(body.contentBlockIndex);
          if (
            !object(body.delta) ||
            Object.keys(body.delta).length !== 1 ||
            typeof body.delta.text !== "string"
          )
            invalid();
          if (body.delta.text.length > 0) {
            if (!firstText) {
              firstText = true;
              observe(observer, { phase: "bedrock_stream_first_text" });
            }
            text += body.delta.text;
            if (text.length > 65536) invalid();
          }
        } else if (event === "contentBlockStop") {
          if (
            !index(body.contentBlockIndex) ||
            !blocks.get(body.contentBlockIndex) ||
            stopped
          )
            invalid();
          blocks.set(body.contentBlockIndex, false);
        } else if (event === "messageStop") {
          if (
            !started ||
            stopped ||
            blocks.size === 0 ||
            [...blocks.values()].some(Boolean)
          )
            invalid();
          if (
            body.stopReason !== undefined &&
            typeof body.stopReason !== "string"
          )
            invalid();
          stopped = true;
          stopReason = body.stopReason;
          observe(observer, {
            phase: "bedrock_stream_message_stopped",
            stopReason: voiceStopReason(
              stopReason === undefined ? {} : { stopReason },
            ),
          });
        } else if (event === "metadata") {
          if (
            !stopped ||
            !object(body.usage) ||
            !tokenCount(body.usage.inputTokens) ||
            !tokenCount(body.usage.outputTokens)
          )
            invalid();
          usage = {
            inputTokens: body.usage.inputTokens,
            outputTokens: body.usage.outputTokens,
          };
          metadata = true;
          observe(observer, {
            phase: "provider_usage",
            ...usage,
            usageSource: "PROVIDER",
          });
        } else invalid();
      }
    }
    if (signal.aborted) throw aborted(signal);
    if (pending.length !== 0 || !metadata || !stopped || !firstText || !usage)
      invalid();
    observe(observer, { phase: "bedrock_stream_completed" });
    return {
      output: { message: { content: [{ text }] } },
      ...(stopReason === undefined ? {} : { stopReason }),
      usage,
    };
  } catch (error) {
    void reader.cancel().catch(() => undefined);
    if (signal.aborted) throw aborted(signal);
    if (error instanceof VoiceProviderFailure) throw error;
    throw new VoiceProviderFailure("STREAM_PROTOCOL_INVALID");
  } finally {
    reader.releaseLock();
  }
}
