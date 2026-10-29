import { AgentFailure } from "./configuration.js";

export class PilotSpeechBudget {
  private reservedCharacters = 0;
  reserve(text: string) {
    if (this.reservedCharacters + text.length > 1200)
      throw new AgentFailure("PROVIDER_FAILED");
    this.reservedCharacters += text.length;
  }
}
