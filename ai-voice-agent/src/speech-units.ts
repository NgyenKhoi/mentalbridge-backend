import { z } from "zod";
import { AgentFailure } from "./configuration.js";
import { replySchema } from "./contract.js";

export const speechUnitCapability = "voice-speech-units-v1";
// MB-846d: a spoken reply is at most 800 characters across its units.
export const speechUnitMaxCharacters = 800;
export const speechEventSchema = z.discriminatedUnion("type", [
  replySchema
    .omit({ policyVersion: true })
    .extend({
      type: z.literal("SPEECH_UNIT"),
      unitSequence: z.number().int().min(1).max(2),
      spokenText: z.string().trim().min(1).max(speechUnitMaxCharacters),
      reservedCharacters: z.literal(speechUnitMaxCharacters),
      policyVersion: z.literal("companion-voice-speech-unit-v1"),
    })
    .strict(),
  z
    .object({ type: z.literal("COMPLETED"), stopReason: z.literal("END_TURN") })
    .strict(),
  z
    .object({ type: z.literal("FAILED"), code: z.literal("TURN_FAILED") })
    .strict(),
]);

export class SpeechEventArray {
  private phase: "START" | "VALUE" | "OBJECT" | "DELIMITER" | "DONE" = "START";
  private quoted = false;
  private escaped = false;
  private depth = 0;
  private buffer = "";
  private count = 0;
  private length = 0;
  constructor(
    private readonly consume: (
      event: z.infer<typeof speechEventSchema>,
    ) => void,
  ) {}
  private invalid(): never {
    throw new AgentFailure("INVALID_RESPONSE");
  }
  accept(text: string): void {
    if ((this.length += text.length) > 16384) this.invalid();
    for (const character of text) {
      if (this.phase === "OBJECT") {
        this.buffer += character;
        if (this.quoted) {
          if (this.escaped) this.escaped = false;
          else if (character === "\\") this.escaped = true;
          else if (character === '"') this.quoted = false;
        } else if (character === '"') this.quoted = true;
        else if (character === "{" || character === "[") {
          if (++this.depth > 8) this.invalid();
        } else if (character === "}" || character === "]") {
          if (--this.depth < 0) this.invalid();
          if (this.depth === 0) {
            let value: unknown;
            try {
              value = JSON.parse(this.buffer);
            } catch {
              this.invalid();
            }
            const parsed = speechEventSchema.safeParse(value);
            if (!parsed.success || ++this.count > 3) this.invalid();
            this.buffer = "";
            this.phase = "DELIMITER";
            this.consume(parsed.data);
          }
        }
        continue;
      }
      if (/^[\t\r\n ]$/u.test(character)) continue;
      if (this.phase === "START") {
        if (character !== "[") this.invalid();
        this.phase = "VALUE";
      } else if (this.phase === "VALUE") {
        if (character !== "{") this.invalid();
        this.phase = "OBJECT";
        this.buffer = "{";
        this.depth = 1;
      } else if (this.phase === "DELIMITER") {
        if (character === ",") this.phase = "VALUE";
        else if (character === "]") this.phase = "DONE";
        else this.invalid();
      } else this.invalid();
    }
  }
  finish(): void {
    if (this.phase !== "DONE" || this.count < 2) this.invalid();
  }
}
