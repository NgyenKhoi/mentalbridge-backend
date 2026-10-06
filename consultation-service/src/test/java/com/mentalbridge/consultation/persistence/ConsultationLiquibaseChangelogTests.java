package com.mentalbridge.consultation.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

class ConsultationLiquibaseChangelogTests {

	@Test
	void masterChangelogResolvesAllConsultationChangesets() throws Exception {
		var path = "db/changelog/db.changelog-master.yaml";
		try (var resources = new ClassLoaderResourceAccessor()) {
			var parser = ChangeLogParserFactory.getInstance().getParser(path, resources);
			var changelog = parser.parse(path, new ChangeLogParameters(), resources);
			assertThat(changelog.getChangeSets()).extracting(changeSet -> changeSet.getId())
					.containsExactly("consultation-001-specialist-profile-approval",
							"consultation-002-current-service-entitlement",
							"consultation-003-online-specialist-availability",
							"consultation-004-service-plan-consultation-credits",
							"consultation-005-online-appointment-request",
							"consultation-006-specialist-lifecycle",
							"consultation-007-consultation-credit-policy-v2",
							"consultation-008-appointment-decisions",
							"consultation-009-appointment-changes",
							"consultation-010-chat-session-completion",
							"consultation-011-session-summary",
							"consultation-012-resource-proposal",
							"consultation-013-appointment-notification-outbox",
							"consultation-014-specialist-client-continuity",
							"consultation-015-appointment-rating",
							"consultation-016-appointment-disputes",
							"consultation-016-specialist-earning-payout");
		}
	}
}
