import type { AgentState } from "./voice-worker.js";
import type { Dispatch } from "./contract.js";

export function isAdmittedAgent(
  dispatch: Dispatch,
  expectedRoom: string,
  current: {
    connected: boolean;
    room: string | undefined;
    metadata: string | undefined;
    identity: string | undefined;
    agent: boolean;
  },
) {
  return (
    current.connected &&
    current.room === expectedRoom &&
    current.metadata === dispatch.dispatchId &&
    current.identity === `va-${dispatch.attemptId}` &&
    current.agent
  );
}

export const agentStates = [
  "initializing",
  "idle",
  "listening",
  "thinking",
  "speaking",
] as const;
export type AgentDisplayState = (typeof agentStates)[number];
const displayStates: Record<AgentState, AgentDisplayState | ""> = {
  CONNECTING: "initializing",
  LISTENING: "listening",
  TRANSCRIBING: "listening",
  WAITING_FOR_REPLY: "thinking",
  SPEAKING: "speaking",
  INTERRUPTED: "listening",
  MUTED: "idle",
  DISCONNECTED: "idle",
  ENDING: "",
  ENDED: "",
};

export class AgentStatePublisher {
  private pending: AgentDisplayState | "" | undefined;
  private current: AgentDisplayState | "" | undefined;
  private work: Promise<void> | undefined;
  private ended = false;
  constructor(
    private readonly admitted: () => boolean,
    private readonly write: (attributes: {
      "lk.agent.state": string;
    }) => Promise<unknown>,
  ) {}
  state(state: AgentState) {
    if (this.ended) return;
    const value = displayStates[state];
    if (state === "ENDING" || state === "ENDED") this.ended = true;
    if (value === this.current && this.pending === undefined) return;
    this.pending = value;
    this.schedule();
  }
  private schedule() {
    this.work ??= Promise.resolve()
      .then(() => this.flush())
      .finally(() => {
        this.work = undefined;
        if (this.pending !== undefined) this.schedule();
      });
  }
  private async flush() {
    while (this.pending !== undefined) {
      const value = this.pending;
      this.pending = undefined;
      try {
        if (!this.admitted()) continue;
        await this.write({ "lk.agent.state": value });
        this.current = value;
      } catch {
        this.current = undefined;
      }
    }
  }
  async close() {
    this.state("ENDING");
    await this.work;
  }
}
