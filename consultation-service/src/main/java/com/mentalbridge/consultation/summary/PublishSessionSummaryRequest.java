package com.mentalbridge.consultation.summary;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PublishSessionSummaryRequest(
		@NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 160) String> topicsDiscussed,
		@Size(max = 1000) String progressSummary,
		@Size(max = 1000) String specialistNoteForUser,
		boolean followUpSuggested,
		@Size(max = 8) List<@Valid NextStep> agreedNextSteps) {

	public PublishSessionSummaryRequest {
		topicsDiscussed = topicsDiscussed == null ? List.of() : List.copyOf(topicsDiscussed);
		agreedNextSteps = agreedNextSteps == null ? List.of() : List.copyOf(agreedNextSteps);
	}

	@JsonAnySetter
	public void rejectUnknownField(String name, Object value) {
		throw new IllegalArgumentException("Unsupported session summary field: " + name);
	}

	public record NextStep(@NotNull AgreedNextStepType type, @NotBlank @Size(max = 160) String title,
			@Size(max = 500) String details, UUID resourceId, @Size(max = 64) String resourceVersion) {
		@JsonAnySetter
		public void rejectUnknownField(String name, Object value) {
			throw new IllegalArgumentException("Unsupported agreed next step field: " + name);
		}
	}
}
