package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.reassessment.JournalLongitudinalClient.Projection;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Change;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Coverage;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Evidence;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.Period;
import com.mentalbridge.care.reassessment.JournalLongitudinalContract.SourceRevision;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReassessmentSummaryIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final Instant PREVIOUS_START = Instant.parse("2026-08-27T00:00:00Z");
	private static final Instant PREVIOUS_END = Instant.parse("2026-09-10T00:00:00Z");
	private static final Instant CURRENT_START = Instant.parse("2026-09-10T00:00:00Z");
	private static final Instant CURRENT_END = Instant.parse("2026-09-24T00:00:00Z");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;
	@MockitoBean JournalLongitudinalClient journal;

	@BeforeEach
	void sufficientJournalProjection() {
		when(journal.read(any(), any(), any(), any(), anyString(), any())).thenAnswer(invocation -> {
			UUID analysisId = invocation.getArgument(1);
			return new Projection("AVAILABLE", null, journalEvidence(analysisId, true, 3, 3));
		});
		when(journal.resolveJob(any(), any(), any(), any(), anyString(), any())).thenAnswer(invocation -> {
			UUID jobId = invocation.getArgument(1);
			UUID analysisId = UUID.randomUUID();
			return new Projection(jobId, analysisId, "AVAILABLE", null,
					journalEvidence(analysisId, true, 3, 3));
		});
	}

	@Test
	void composesPersistsReloadsAndListsContradictorySourcedDimensions() throws Exception {
		var owner = insertProfile();
		var evidence = assessments(owner);
		insertReusableEngagement(owner, evidence.currentPhq9(), evidence.currentGad7());
		var jobId = UUID.randomUUID();
		var selfReportId = createSelfReport(owner, "reassessment-self-0001", "MORE_DIFFICULT",
				"Short pauses helped.", "Work pressure felt harder.");
		String body = jobBody(evidence.currentPhq9(), evidence.currentGad7(), jobId,
				PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END, selfReportId);

		var created = mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-summary-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.summaryVersion").value("reassessment-summary-v2"))
				.andExpect(jsonPath("$.screening.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.screening.trends[0].instrument").value("PHQ9"))
				.andExpect(jsonPath("$.screening.trends[0].rawDelta").value(-5))
				.andExpect(jsonPath("$.screening.trends[0].direction").value("DECREASED"))
				.andExpect(jsonPath("$.screening.trends[1].direction").value("INCREASED"))
				.andExpect(jsonPath("$.journalContext.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.journalContext.jobId").value(jobId.toString()))
				.andExpect(jsonPath("$.journalContext.analysisId").isNotEmpty())
				.andExpect(jsonPath("$.journalContext.changesComparedWithPreviousPeriod[0].direction")
						.value("MORE_FREQUENT"))
				.andExpect(jsonPath("$.supportPlanEngagement.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.supportPlanEngagement.previousPeriod.completedCount").value(1))
				.andExpect(jsonPath("$.supportPlanEngagement.currentPeriod.skippedCount").value(1))
				.andExpect(jsonPath("$.supportPlanEngagement.sources[0].helpfulness").doesNotExist())
				.andExpect(jsonPath("$.selfReportedExperience.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.selfReportedExperience.source.currentExperience").value("MORE_DIFFICULT"))
				.andExpect(jsonPath("$.selfReportedExperience.source.difficultContext").value("Work pressure felt harder."))
				.andExpect(jsonPath("$.activityReflection.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.activityReflection.sources[0].helpfulness").value("HELPFUL"))
				.andExpect(jsonPath("$.activityReflection.sources[0].reflection").value("Breathing helped me settle."))
				.andExpect(jsonPath("$.userReflection").doesNotExist())
				.andExpect(jsonPath("$.disclaimerCode").value("FOUR_DIMENSIONS_NOT_COMBINED"))
				.andExpect(jsonPath("$.combinedScore").doesNotExist())
				.andExpect(jsonPath("$.overallDirection").doesNotExist())
				.andReturn();
		JsonNode first = objectMapper.readTree(created.getResponse().getContentAsString());
		UUID summaryId = UUID.fromString(first.path("summaryId").asText());

		when(journal.resolveJob(any(), any(), any(), any(), anyString(), any()))
				.thenReturn(new Projection("UNAVAILABLE", "SOURCE_NOT_FOUND", null));
		mvc.perform(get("/api/v1/reassessment-summaries/current").with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.summaryId").value(summaryId.toString()))
				.andExpect(jsonPath("$.journalContext.state").value("AVAILABLE"));
		mvc.perform(get("/api/v1/reassessment-summaries/{id}", summaryId).with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.summaryId").value(summaryId.toString()));
		mvc.perform(get("/api/v1/reassessment-summaries").with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].summaryId").value(summaryId.toString()))
				.andExpect(jsonPath("$.hasMore").value(false));

		var replay = mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-summary-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn();
		assertThat(objectMapper.readTree(replay.getResponse().getContentAsString()).path("summaryId").asText())
				.isEqualTo(summaryId.toString());
		assertThat(jdbc.sql("select count(*) from reassessment_summary where user_id=:id").param("id", owner)
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void persistsSparseAndUnavailableStatesWithoutBlockingLocalScreening() throws Exception {
		var sparseOwner = insertProfile();
		var sparse = assessments(sparseOwner);
		var sparseJob = UUID.randomUUID();
		var sparseAnalysis = UUID.randomUUID();
		when(journal.resolveJob(any(), any(), any(), any(), anyString(), any()))
				.thenReturn(new Projection(sparseJob, sparseAnalysis, "INSUFFICIENT_DATA", null,
						journalEvidence(sparseAnalysis, false, 2, 2)));

		mvc.perform(post("/api/v1/reassessment-summaries").with(user(sparseOwner))
				.header("Idempotency-Key", "reassessment-summary-sparse")
				.contentType(MediaType.APPLICATION_JSON).content(jobBody(sparse.currentPhq9(), sparse.currentGad7(),
						sparseJob, PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.screening.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.journalContext.state").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.journalContext.changesComparedWithPreviousPeriod[0].direction")
						.value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.supportPlanEngagement.state").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.selfReportedExperience.state").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.selfReportedExperience.unavailableReason").value("NOT_PROVIDED"))
				.andExpect(jsonPath("$.activityReflection.state").value("INSUFFICIENT_DATA"));

		var unavailableOwner = insertProfile();
		var unavailable = assessments(unavailableOwner);
		var unavailableJob = UUID.randomUUID();
		when(journal.resolveJob(any(), any(), any(), any(), anyString(), any()))
				.thenReturn(new Projection(unavailableJob, null, "UNAVAILABLE", "PROVIDER_TIMEOUT", null));
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(unavailableOwner))
				.header("Idempotency-Key", "reassessment-unavailable")
				.contentType(MediaType.APPLICATION_JSON).content(jobBody(unavailable.currentPhq9(), unavailable.currentGad7(),
						unavailableJob, PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.screening.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.journalContext.state").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.journalContext.unavailableReason").value("PROVIDER_TIMEOUT"))
				.andExpect(jsonPath("$.journalContext.jobId").value(unavailableJob.toString()))
				.andExpect(jsonPath("$.journalContext.analysisId").doesNotExist())
				.andExpect(jsonPath("$.journalContext.dataCoverage").doesNotExist());

		var firstScreeningOwner = insertProfile();
		var firstScreening = currentAssessments(firstScreeningOwner);
		var sufficientAnalysis = UUID.randomUUID();
		var sufficientJob = UUID.randomUUID();
		when(journal.resolveJob(any(), any(), any(), any(), anyString(), any()))
				.thenReturn(new Projection(sufficientJob, sufficientAnalysis, "AVAILABLE", null,
						journalEvidence(sufficientAnalysis, true, 3, 3)));
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(firstScreeningOwner))
				.header("Idempotency-Key", "reassessment-first-screening")
				.contentType(MediaType.APPLICATION_JSON).content(jobBody(firstScreening.currentPhq9(),
						firstScreening.currentGad7(), sufficientJob,
						PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.screening.state").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.screening.trends[0].direction").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.screening.trends[0].previous").doesNotExist())
				.andExpect(jsonPath("$.screening.trends[0].rawDelta").doesNotExist())
				.andExpect(jsonPath("$.journalContext.state").value("AVAILABLE"));
	}

	@Test
	void rejectsUnauthorizedInvalidCrossOwnerAndConflictingRequests() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		var evidence = assessments(owner);
		var otherEvidence = assessments(other);
		var analysisId = UUID.randomUUID();
		String valid = body(evidence.currentPhq9(), evidence.currentGad7(), analysisId,
				PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END);

		mvc.perform(post("/api/v1/reassessment-summaries").header("Idempotency-Key", "reassessment-auth-0001")
				.contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/reassessment-summaries").with(admin())
				.header("Idempotency-Key", "reassessment-auth-0002")
				.contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-cross-owner")
				.contentType(MediaType.APPLICATION_JSON).content(body(otherEvidence.currentPhq9(),
						evidence.currentGad7(), analysisId, PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-invalid-period")
				.contentType(MediaType.APPLICATION_JSON).content(body(evidence.currentPhq9(), evidence.currentGad7(),
						analysisId, PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END.minusSeconds(1))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REASSESSMENT_PERIODS"));

		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-conflict-key")
				.contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isCreated());
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-conflict-key")
				.contentType(MediaType.APPLICATION_JSON).content(body(evidence.currentPhq9(), evidence.currentGad7(),
						UUID.randomUUID(), PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void issuesCareOwnedContextAndRejectsStaleAssessmentSelection() throws Exception {
		var owner = insertProfile();
		var selected = assessments(owner);

		mvc.perform(get("/api/v1/reassessment-summaries/context").with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.policyVersion").value("reassessment-comparison-v1"))
				.andExpect(jsonPath("$.state").value("READY"))
				.andExpect(jsonPath("$.missingInstruments").isEmpty())
				.andExpect(jsonPath("$.phq9AssessmentId").value(selected.currentPhq9().toString()))
				.andExpect(jsonPath("$.gad7AssessmentId").value(selected.currentGad7().toString()));

		var newerPhq9 = insertAssessment(owner, PHQ9_DEFINITION, 7, "MILD", "phq9-standard-bands-v1",
				Instant.parse("2026-09-23T08:00:00Z"));
		assertThat(newerPhq9).isNotEqualTo(selected.currentPhq9());
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-stale-assessment")
				.contentType(MediaType.APPLICATION_JSON).content(jobBody(selected.currentPhq9(),
						selected.currentGad7(), UUID.randomUUID(), PREVIOUS_START, PREVIOUS_END,
						CURRENT_START, CURRENT_END)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_CURRENT"));
	}

	@Test
	void replacesDeletesAndKeepsEarlierSummarySnapshotsDeterministic() throws Exception {
		var owner = insertProfile();
		var other = insertProfile();
		var evidence = assessments(owner);
		var jobId = UUID.randomUUID();
		var selfReportId = createSelfReport(owner, "reassessment-self-history-01", "MORE_DIFFICULT", null,
				"Deadlines felt harder.");
		String summaryBody = jobBody(evidence.currentPhq9(), evidence.currentGad7(), jobId,
				PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END, selfReportId);

		var first = mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-history-0001")
				.contentType(MediaType.APPLICATION_JSON).content(summaryBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.selfReportedExperience.source.currentExperience").value("MORE_DIFFICULT"))
				.andReturn();
		String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).path("summaryId").asText();

		String replacement = """
				{"currentExperience":"BETTER","helpfulContext":"A steadier routine helped.","difficultContext":null}
				""";
		mvc.perform(put("/api/v1/reassessment-self-reports/{id}", selfReportId).with(user(other))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(replacement))
				.andExpect(status().isNotFound());
		mvc.perform(put("/api/v1/reassessment-self-reports/{id}", selfReportId).with(user(owner))
				.header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(replacement))
				.andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
				.andExpect(jsonPath("$.currentExperience").value("BETTER"));

		var second = mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-history-0002")
				.contentType(MediaType.APPLICATION_JSON).content(summaryBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.selfReportedExperience.source.currentExperience").value("BETTER"))
				.andExpect(jsonPath("$.selfReportedExperience.source.sourceRevision").value(1)).andReturn();
		String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).path("summaryId").asText();

		mvc.perform(delete("/api/v1/reassessment-self-reports/{id}", selfReportId).with(user(other))
				.header("If-Match", "\"1\""))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/reassessment-self-reports/{id}", selfReportId).with(user(owner))
				.header("If-Match", "\"1\""))
				.andExpect(status().isNoContent());
		assertThat(jdbc.sql("""
				select current_experience is null and helpful_context is null and difficult_context is null
				from reassessment_self_report where id=:id
				""").param("id", selfReportId).query(Boolean.class).single()).isTrue();

		mvc.perform(get("/api/v1/reassessment-summaries/{id}", firstId).with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.selfReportedExperience.source.currentExperience").value("MORE_DIFFICULT"));
		mvc.perform(get("/api/v1/reassessment-summaries/{id}", secondId).with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.selfReportedExperience.source.currentExperience").value("BETTER"));
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-history-0003")
				.contentType(MediaType.APPLICATION_JSON).content(summaryBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.selfReportedExperience.state").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.selfReportedExperience.unavailableReason").value("SOURCE_DELETED"))
				.andExpect(jsonPath("$.selfReportedExperience.source").doesNotExist());
	}

	@Test
	void returnsCreatedUnavailableSummaryWithinTheThreeSecondCallerBudget() throws Exception {
		var owner = insertProfile();
		var evidence = assessments(owner);
		var analysisId = UUID.randomUUID();
		when(journal.read(any(), any(), any(), any(), anyString(), any())).thenAnswer(invocation -> {
			Thread.sleep(2100);
			return new Projection("UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", null);
		});

		long startedAt = System.nanoTime();
		mvc.perform(post("/api/v1/reassessment-summaries").with(user(owner))
				.header("Idempotency-Key", "reassessment-timeout-fallback")
				.contentType(MediaType.APPLICATION_JSON).content(body(evidence.currentPhq9(), evidence.currentGad7(),
						analysisId, PREVIOUS_START, PREVIOUS_END, CURRENT_START, CURRENT_END)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.screening.state").value("AVAILABLE"))
				.andExpect(jsonPath("$.journalContext.state").value("UNAVAILABLE"))
				.andExpect(jsonPath("$.journalContext.unavailableReason").value("DEPENDENCY_UNAVAILABLE"));
		var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
	}

	private AssessmentSet assessments(UUID owner) {
		var previousPhq9 = insertAssessment(owner, PHQ9_DEFINITION, 13, "MODERATE", "phq9-standard-bands-v1",
				Instant.parse("2026-09-08T08:00:00Z"));
		var previousGad7 = insertAssessment(owner, GAD7_DEFINITION, 6, "MILD", "gad7-standard-bands-v1",
				Instant.parse("2026-09-08T09:00:00Z"));
		var currentPhq9 = insertAssessment(owner, PHQ9_DEFINITION, 8, "MILD", "phq9-standard-bands-v1",
				Instant.parse("2026-09-22T08:00:00Z"));
		var currentGad7 = insertAssessment(owner, GAD7_DEFINITION, 10, "MODERATE", "gad7-standard-bands-v1",
				Instant.parse("2026-09-22T09:00:00Z"));
		return new AssessmentSet(previousPhq9, previousGad7, currentPhq9, currentGad7);
	}

	private UUID createSelfReport(UUID owner, String key, String experience, String helpful, String difficult)
			throws Exception {
		String body = """
				{"currentPeriod":{"startAt":"%s","endAt":"%s"},"currentExperience":"%s",
				 "helpfulContext":%s,"difficultContext":%s}
				""".formatted(CURRENT_START, CURRENT_END, experience, json(helpful), json(difficult));
		var result = mvc.perform(post("/api/v1/reassessment-self-reports").with(user(owner))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.sourceVersion")
						.value("reassessment-self-report-v1"))
				.andExpect(jsonPath("$.version").value(0)).andReturn();
		return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.path("selfReportId").asText());
	}

	private String json(String value) throws Exception {
		return value == null ? "null" : objectMapper.writeValueAsString(value);
	}

	private AssessmentSet currentAssessments(UUID owner) {
		var currentPhq9 = insertAssessment(owner, PHQ9_DEFINITION, 8, "MILD", "phq9-standard-bands-v1",
				Instant.parse("2026-09-22T08:00:00Z"));
		var currentGad7 = insertAssessment(owner, GAD7_DEFINITION, 10, "MODERATE", "gad7-standard-bands-v1",
				Instant.parse("2026-09-22T09:00:00Z"));
		return new AssessmentSet(null, null, currentPhq9, currentGad7);
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id,display_name) values (:id,'Reassessment user')")
				.param("id", userId).update();
		return userId;
	}

	private UUID insertAssessment(UUID userId, UUID definitionId, int score, String level, String scoringVersion,
			Instant submittedAt) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into assessment_submission
					(id,user_id,definition_id,idempotency_key,request_hash,privacy_policy_version,submitted_at)
				values (:id,:userId,:definitionId,:key,:hash,'privacy-capstone-v3',:submittedAt)
				""").param("id", id).param("userId", userId).param("definitionId", definitionId)
				.param("key", "reassessment-assessment-" + UUID.randomUUID()).param("hash", "0".repeat(64))
				.param("submittedAt", OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC)).update();
		boolean gad7 = definitionId.equals(GAD7_DEFINITION);
		jdbc.sql("""
				insert into assessment_result
					(submission_id,total_score,screening_level,scoring_version,safety_item_positive,
					 safety_status,safety_policy_version,disclaimer_code,calculated_at)
				values (:id,:score,:level,:scoringVersion,:positive,:safetyStatus,:safetyPolicy,
					'SCREENING_NOT_DIAGNOSIS',:submittedAt)
				""").param("id", id).param("score", score).param("level", level)
				.param("scoringVersion", scoringVersion).param("positive", gad7 ? null : false)
				.param("safetyStatus", gad7 ? "NOT_APPLICABLE" : "NEGATIVE_SAFETY_SCREEN")
				.param("safetyPolicy", gad7 ? null : "MB-SAFETY-PHQ9-001-v1")
				.param("submittedAt", OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC)).update();
		return id;
	}

	private void insertReusableEngagement(UUID userId, UUID phq9AssessmentId, UUID gad7AssessmentId) {
		UUID evaluationId = UUID.randomUUID();
		jdbc.sql("""
				insert into support_evaluation_v2
					(id,user_id,phq9_assessment_id,gad7_assessment_id,policy_version,evaluated_at)
				values (:id,:userId,:phq9,:gad7,'mb-support-routing-capstone-v2',:now)
				""").param("id", evaluationId).param("userId", userId).param("phq9", phq9AssessmentId)
				.param("gad7", gad7AssessmentId).param("now", OffsetDateTime.parse("2026-09-22T10:00:00Z")).update();
		UUID planId = UUID.randomUUID();
		jdbc.sql("""
				insert into support_plan
					(id,user_id,support_evaluation_id,status,version,evaluation_policy_version,evaluated_at,
					 selection_policy_version,resource_policy_version,resources_resolved_at,entitlement_package,
					 entitlement_source,entitlement_policy_version,entitlement_version,entitlement_decided_at,
					 rationale_code,rationale_text,safety_status,safety_reason_code,safety_policy_version,
					 safety_guidance_code,safety_guidance,selected_resource_count,created_at,updated_at,activated_at)
				values
					(:id,:userId,:evaluation,'ACTIVE',1,'mb-support-routing-capstone-v2',:now,
					 'mb-support-plan-selection-v1','content-eligibility-v1',:now,'PLUS','DEMO',
					 'service-entitlement-v1',1,:now,'REASSESSMENT_TEST','Synthetic rationale',
					 'NEGATIVE_SAFETY_SCREEN','PHQ9_ITEM9_NEGATIVE','MB-SAFETY-PHQ9-001-v1',
					 'STANDARD_SAFETY_REMINDER','Synthetic guidance',1,:now,:now,:now)
				""").param("id", planId).param("userId", userId).param("evaluation", evaluationId)
				.param("now", OffsetDateTime.parse("2026-09-22T10:00:00Z")).update();
		UUID scheduleId = UUID.randomUUID();
		UUID resourceId = UUID.randomUUID();
		jdbc.sql("""
				insert into support_plan_activity_schedule
					(id,support_plan_id,user_id,ordinal,schedule_version,source_plan_version,source_slot_key,
					 source_resource_id,source_content_version,source_title,recurrence_type,local_time,timezone,
					 effective_from,status,created_at,updated_at)
				values (:id,:planId,:userId,1,1,1,'breathing',:resourceId,1,'Breathing','DAILY','08:00',
					'UTC','2026-08-27','ACTIVE',:now,:now)
				""").param("id", scheduleId).param("planId", planId).param("userId", userId)
				.param("resourceId", resourceId).param("now", OffsetDateTime.parse("2026-09-22T10:00:00Z")).update();
		insertOccurrence(scheduleId, planId, userId, resourceId, Instant.parse("2026-09-05T08:00:00Z"),
				"COMPLETED", "HELPFUL", null, "Breathing helped me settle.");
		insertOccurrence(scheduleId, planId, userId, resourceId, Instant.parse("2026-09-18T08:00:00Z"),
				"SKIPPED", null, "LOW_ENERGY", "I had less energy this week.");
	}

	private void insertOccurrence(UUID scheduleId, UUID planId, UUID userId, UUID resourceId, Instant scheduledAt,
			String state, String helpfulness, String barrier, String reflection) {
		UUID id = UUID.randomUUID();
		LocalDate localDate = scheduledAt.atZone(ZoneOffset.UTC).toLocalDate();
		jdbc.sql("""
				insert into support_plan_activity_occurrence
					(id,activity_schedule_id,support_plan_id,user_id,schedule_version,local_date,local_time,
					 timezone,scheduled_at,state,state_reason,source_plan_version,source_slot_key,source_resource_id,
					 source_content_version,source_title,version,created_at,updated_at,completed_at,skipped_at,
					 hidden,helpfulness,barrier_code,reflection,summary_reuse_approved,engagement_updated_at)
				values
					(:id,:scheduleId,:planId,:userId,1,:localDate,'08:00','UTC',:scheduledAt,:state,null,1,
					 'breathing',:resourceId,1,'Breathing',1,:scheduledAt,:scheduledAt,:completedAt,:skippedAt,
					 false,:helpfulness,:barrier,:reflection,true,:scheduledAt)
				""").param("id", id).param("scheduleId", scheduleId).param("planId", planId)
				.param("userId", userId).param("localDate", localDate)
				.param("scheduledAt", OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC)).param("state", state)
				.param("resourceId", resourceId)
				.param("completedAt", "COMPLETED".equals(state) ? OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC) : null)
				.param("skippedAt", "SKIPPED".equals(state) ? OffsetDateTime.ofInstant(scheduledAt, ZoneOffset.UTC) : null)
				.param("helpfulness", helpfulness).param("barrier", barrier).param("reflection", reflection).update();
	}

	private Evidence journalEvidence(UUID analysisId, boolean sufficient, int previousCount, int currentCount) {
		var sources = new ArrayList<SourceRevision>();
		for (int index = 0; index < previousCount; index++) sources.add(new SourceRevision(UUID.randomUUID(), 1, "PREVIOUS"));
		for (int index = 0; index < currentCount; index++) sources.add(new SourceRevision(UUID.randomUUID(), 1, "CURRENT"));
		return new Evidence(analysisId, new Period(PREVIOUS_START, PREVIOUS_END), new Period(CURRENT_START, CURRENT_END),
				sources, List.of("work-pressure"), List.of("stress"), List.of("deadlines"),
				List.of(new Change("stress", sufficient ? "MORE_FREQUENT" : "INSUFFICIENT_DATA")), List.of(),
				List.of("low-energy"), List.of(), new Coverage(previousCount, currentCount, sufficient),
				"DETERMINISTIC_FAKE", "deterministic-longitudinal-v1", "longitudinal-v1", 1,
				Instant.parse("2026-09-23T12:00:00Z"));
	}

	private String body(UUID phq9, UUID gad7, UUID analysisId, Instant previousStart, Instant previousEnd,
			Instant currentStart, Instant currentEnd) {
		return body(phq9, gad7, analysisId, previousStart, previousEnd, currentStart, currentEnd, null);
	}

	private String jobBody(UUID phq9, UUID gad7, UUID jobId, Instant previousStart, Instant previousEnd,
			Instant currentStart, Instant currentEnd) {
		return jobBody(phq9, gad7, jobId, previousStart, previousEnd, currentStart, currentEnd, null);
	}

	private String jobBody(UUID phq9, UUID gad7, UUID jobId, Instant previousStart, Instant previousEnd,
			Instant currentStart, Instant currentEnd, UUID selfReportId) {
		return """
				{"phq9AssessmentId":"%s","gad7AssessmentId":"%s","journalJobId":"%s",
				 "previousPeriod":{"startAt":"%s","endAt":"%s"},
				 "currentPeriod":{"startAt":"%s","endAt":"%s"}%s}
				""".formatted(phq9, gad7, jobId, previousStart, previousEnd, currentStart, currentEnd,
				selfReportId == null ? "" : ",\"selfReportId\":\"" + selfReportId + "\"");
	}

	private String body(UUID phq9, UUID gad7, UUID analysisId, Instant previousStart, Instant previousEnd,
			Instant currentStart, Instant currentEnd, UUID selfReportId) {
		return """
				{"phq9AssessmentId":"%s","gad7AssessmentId":"%s","journalAnalysisId":"%s",
				 "previousPeriod":{"startAt":"%s","endAt":"%s"},
				 "currentPeriod":{"startAt":"%s","endAt":"%s"}%s}
				""".formatted(phq9, gad7, analysisId, previousStart, previousEnd, currentStart, currentEnd,
				selfReportId == null ? "" : ",\"selfReportId\":\"" + selfReportId + "\"");
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()).tokenValue("synthetic-user-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
		return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).tokenValue("synthetic-admin-token"))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private record AssessmentSet(UUID previousPhq9, UUID previousGad7, UUID currentPhq9, UUID currentGad7) { }
}
