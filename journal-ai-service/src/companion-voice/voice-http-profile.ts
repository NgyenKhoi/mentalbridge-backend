import { AsyncLocalStorage } from "node:async_hooks";
import { channel } from "node:diagnostics_channel";
import { performance } from "node:perf_hooks";

export interface VoiceHttpDiagnostic {
  nativeRequests: number;
  httpObservation: "OBSERVED" | "UNAVAILABLE" | "MULTIPLE_REQUESTS";
  connection: "FIRST_OBSERVED_SOCKET" | "REUSED_OBSERVED_SOCKET" | "UNKNOWN";
  httpStatusClass:
    "HTTP_2XX" | "HTTP_4XX" | "HTTP_5XX" | "HTTP_OTHER" | "UNKNOWN";
  httpResult: "COMPLETE" | "ERROR" | "INCOMPLETE";
  sdkBeforeRequestMs?: number;
  requestToSendMs?: number;
  sendToHeadersMs?: number;
  headersToBodyMs?: number;
  sdkAfterBodyMs?: number;
}
interface ReadScope {
  clock: () => number;
  started: number;
  count: number;
  first?: RequestTiming;
}
interface RequestTiming {
  scope: ReadScope;
  created: number;
  sent?: number;
  headers?: number;
  completed?: number;
  connection: VoiceHttpDiagnostic["connection"];
  status: VoiceHttpDiagnostic["httpStatusClass"];
  result: VoiceHttpDiagnostic["httpResult"];
}
const active = new AsyncLocalStorage<ReadScope>();
const requests = new WeakMap<object, RequestTiming>();
const sockets = new WeakSet<object>();
let subscribed = false;
const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object";
const duration = (start?: number, end?: number) =>
  start !== undefined &&
  end !== undefined &&
  Number.isFinite(start) &&
  Number.isFinite(end) &&
  end >= start
    ? Math.min(30000, Math.floor(end - start))
    : undefined;

function subscribe() {
  if (subscribed) return;
  subscribed = true;
  for (const event of [
    "create",
    "sendHeaders",
    "headers",
    "trailers",
    "error",
  ]) {
    const name =
      event === "sendHeaders"
        ? "undici:client:sendHeaders"
        : `undici:request:${event}`;
    channel(name).subscribe((message: unknown) => {
      try {
        if (!object(message) || !object(message.request)) return;
        if (event === "create") {
          const scope = active.getStore();
          if (!scope) return;
          scope.count = Math.min(16, scope.count + 1);
          const timing: RequestTiming = {
            scope,
            created: scope.clock(),
            connection: "UNKNOWN",
            status: "UNKNOWN",
            result: "INCOMPLETE",
          };
          if (scope.count === 1) scope.first = timing;
          requests.set(message.request, timing);
          return;
        }
        const timing = requests.get(message.request);
        if (!timing) return;
        const now = timing.scope.clock();
        if (event === "sendHeaders") {
          timing.sent = now;
          if (object(message.socket)) {
            timing.connection = sockets.has(message.socket)
              ? "REUSED_OBSERVED_SOCKET"
              : "FIRST_OBSERVED_SOCKET";
            sockets.add(message.socket);
          }
        }
        if (event === "headers") {
          timing.headers = now;
          const status = object(message.response)
            ? message.response.statusCode
            : undefined;
          if (
            typeof status === "number" &&
            Number.isInteger(status) &&
            status >= 100 &&
            status <= 599
          )
            timing.status =
              status >= 200 && status < 300
                ? "HTTP_2XX"
                : status >= 400 && status < 500
                  ? "HTTP_4XX"
                  : status >= 500
                    ? "HTTP_5XX"
                    : "HTTP_OTHER";
        }
        if (event === "trailers") {
          timing.completed = now;
          timing.result = "COMPLETE";
        }
        if (event === "error") timing.result = "ERROR";
        if (event === "trailers" || event === "error")
          requests.delete(message.request);
      } catch {
        return;
      }
    });
  }
}

export async function profileVoiceHttpRead<T>(
  read: () => Promise<T>,
  observe: (value: VoiceHttpDiagnostic) => void,
  clock: () => number = () => performance.now(),
): Promise<T> {
  subscribe();
  const scope: ReadScope = { clock, started: clock(), count: 0 };
  try {
    return await active.run(scope, read);
  } finally {
    const first = scope.count === 1 ? scope.first : undefined;
    const value: VoiceHttpDiagnostic = {
      nativeRequests: scope.count,
      httpObservation:
        scope.count === 1
          ? "OBSERVED"
          : scope.count === 0
            ? "UNAVAILABLE"
            : "MULTIPLE_REQUESTS",
      connection: first?.connection ?? "UNKNOWN",
      httpStatusClass: first?.status ?? "UNKNOWN",
      httpResult: first?.result ?? "INCOMPLETE",
    };
    for (const [key, start, end] of [
      ["sdkBeforeRequestMs", scope.started, first?.created],
      ["requestToSendMs", first?.created, first?.sent],
      ["sendToHeadersMs", first?.sent, first?.headers],
      ["headersToBodyMs", first?.headers, first?.completed],
      ["sdkAfterBodyMs", first?.completed, clock()],
    ] as const) {
      const measured = duration(start, end);
      if (measured !== undefined && first) value[key] = measured;
    }
    emit(observe, value);
  }
}

function emit(
  observe: (value: VoiceHttpDiagnostic) => void,
  value: VoiceHttpDiagnostic,
) {
  try {
    observe(value);
  } catch {
    return;
  }
}
