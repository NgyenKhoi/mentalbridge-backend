import {
  RoutedChatProvider,
  selectCompanionProviderRoute,
} from "../companion-chat/companion-chat.js";
import type { ServiceConfiguration } from "../configuration/configuration.js";
import type { VoicePlan } from "./voice-policy.js";
import { VoiceMediaError } from "./voice-media-provider.js";

export const selectVoiceProviderRoute = (
  configuration: ServiceConfiguration,
  plan: VoicePlan,
): ReturnType<typeof selectCompanionProviderRoute> => {
  const voice = configuration.VOICE_BEDROCK_ROUTE;
  if (voice?.approvedVersion && configuration.BEDROCK_API_KEY) {
    return {
      provider: "BEDROCK",
      model: voice.model,
      providerApprovalVersion: voice.approvedVersion,
      routingPolicyVersion: "companion-voice-sonnet-v1",
      promptVersion: "companion-chat-v1",
    };
  }
  if (
    configuration.VOICE_MEDIA?.enabled ||
    configuration.NODE_ENV === "production"
  )
    throw new VoiceMediaError();
  return selectCompanionProviderRoute(configuration, plan);
};

export const createVoiceProvider = (configuration: ServiceConfiguration) =>
  new RoutedChatProvider({
    ...configuration,
    BEDROCK_REGION:
      configuration.VOICE_BEDROCK_ROUTE?.region ?? configuration.BEDROCK_REGION,
  });
