import { VoiceProviderFailure } from "./voice-diagnostics.js";

export class VoiceJsonObjectArray {
  private phase: "START" | "VALUE" | "OBJECT" | "DELIMITER" | "DONE" = "START";
  private depth = 0;
  private quoted = false;
  private escaped = false;
  private buffer = "";
  private count = 0;
  private length = 0;
  private afterComma = false;
  constructor(
    private readonly consume: (value: unknown) => void,
    private readonly maxObjects = 2,
    private readonly maxCharacters = 16384,
  ) {}
  private invalid(): never {
    throw new VoiceProviderFailure("OUTPUT_JSON_INVALID");
  }
  accept(fragment: string): void {
    this.length += fragment.length;
    if (this.length > this.maxCharacters) this.invalid();
    for (const character of fragment) {
      if (this.phase === "OBJECT") {
        this.buffer += character;
        if (this.quoted) {
          if (this.escaped) this.escaped = false;
          else if (character === "\\") this.escaped = true;
          else if (character === '"') this.quoted = false;
        } else if (character === '"') this.quoted = true;
        else if (character === "{" || character === "[") {
          if (++this.depth > 16) this.invalid();
        } else if (character === "}" || character === "]") {
          if (--this.depth < 0) this.invalid();
          if (this.depth === 0) {
            let value: unknown;
            try {
              value = JSON.parse(this.buffer);
            } catch {
              this.invalid();
            }
            if (
              !value ||
              typeof value !== "object" ||
              Array.isArray(value) ||
              ++this.count > this.maxObjects
            )
              this.invalid();
            this.buffer = "";
            this.phase = "DELIMITER";
            this.consume(value);
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
        this.buffer = "{";
        this.depth = 1;
        this.quoted = false;
        this.escaped = false;
        this.afterComma = false;
        this.phase = "OBJECT";
      } else if (this.phase === "DELIMITER") {
        if (character === ",") {
          this.phase = "VALUE";
          this.afterComma = true;
        } else if (character === "]") this.phase = "DONE";
        else this.invalid();
      } else this.invalid();
    }
  }
  finish(): void {
    if (this.phase !== "DONE" || this.afterComma || this.count === 0)
      this.invalid();
  }
}
