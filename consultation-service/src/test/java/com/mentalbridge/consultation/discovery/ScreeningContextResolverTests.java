package com.mentalbridge.consultation.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mentalbridge.consultation.specialist.SupportArea;

class ScreeningContextResolverTests {

	private final CareSupportEvaluationClient care = mock(CareSupportEvaluationClient.class);
	private final ScreeningContextResolver resolver = new ScreeningContextResolver(care);

	@Test
	void reducesTheOwnedEvaluationToDomainPrioritiesOnly() {
		var id = UUID.randomUUID();
		when(care.get(id, "Bearer browser-token")).thenReturn(new CareSupportEvaluationClient.SupportEvaluation(
				id, "mb-support-routing-capstone-v2", List.of(
						new CareSupportEvaluationClient.DomainContribution("DEPRESSIVE_SYMPTOMS", "MILD",
								"SELF_GUIDED_SUPPORT"),
						new CareSupportEvaluationClient.DomainContribution("ANXIETY_SYMPTOMS", "MODERATE",
								"PROFESSIONAL_SUPPORT_RECOMMENDED"))));

		var context = resolver.resolve(id, "browser-token").orElseThrow();

		assertThat(context.policyVersion()).isEqualTo("mb-support-routing-capstone-v2");
		assertThat(context.priorities()).containsEntry(SupportArea.DEPRESSIVE_SYMPTOMS, 1)
				.containsEntry(SupportArea.ANXIETY_SYMPTOMS, 2).hasSize(2);
		verify(care).get(id, "Bearer browser-token");
	}

	@Test
	void malformedOrUnavailableCareContextFailsToNeutral() {
		var malformed = UUID.randomUUID();
		when(care.get(malformed, "Bearer token")).thenReturn(new CareSupportEvaluationClient.SupportEvaluation(
				malformed, "mb-support-routing-capstone-v2", List.of(
						new CareSupportEvaluationClient.DomainContribution("ANXIETY_SYMPTOMS", "GLOBAL_HIGH",
								"PROFESSIONAL_SUPPORT_RECOMMENDED"))));
		var unavailable = UUID.randomUUID();
		when(care.get(unavailable, "Bearer token")).thenThrow(new IllegalStateException("dependency unavailable"));

		assertThat(resolver.resolve(malformed, "token")).isEmpty();
		assertThat(resolver.resolve(unavailable, "token")).isEmpty();
	}
}
