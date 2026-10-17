package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;

class ActivityDashboardContractTests {

	@Test
	void dashboardContractIsAggregateOnlyAndExcludesPrivateRecordData() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var operation = openApi.getPaths().get("/api/v1/activity-dashboard").getGet();
		var dashboard = openApi.getComponents().getSchemas().get("ActivityDashboard");
		var summary = openApi.getComponents().getSchemas().get("ActivityDashboardSummary");
		var bucket = openApi.getComponents().getSchemas().get("ActivityDashboardDailyBucket");

		assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
		assertThat(dashboard.getProperties()).containsKeys("summary", "daily", "sources", "partial", "bounded")
				.doesNotContainKeys("items", "events", "history", "records");
		assertThat(summary.getProperties()).containsKeys("assessmentSubmissions", "journalEntries",
				"journalActiveDays", "emotionCheckIns", "supportCompleted", "appointmentEvents")
				.doesNotContainKeys("score", "improvement", "adherence", "recovery");
		assertThat(bucket.getProperties()).containsOnlyKeys("localDate", "assessments", "journals", "emotions", "emotionLevel",
				"supportCompleted", "supportSkipped", "appointments", "total")
				.doesNotContainKeys("id", "journalText", "assessmentAnswers", "emotion", "note", "specialist");
	}
}
