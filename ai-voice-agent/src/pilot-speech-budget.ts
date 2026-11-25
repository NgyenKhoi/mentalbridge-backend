import { AgentFailure } from "./configuration.js";

export class PilotSpeechBudget {
  private reservedCharacters = 0;
  constructor(private readonly maxCharacters = 1200) {
    if (
      !Number.isInteger(maxCharacters) ||
      maxCharacters < 1 ||
      maxCharacters > 8000
    )
      throw new AgentFailure("CONFIGURATION_INVALID");
  }
  reserve(text: string) {
    if (this.reservedCharacters + text.length > this.maxCharacters)
      throw new AgentFailure("PROVIDER_FAILED");
    this.reservedCharacters += text.length;
  }
}
