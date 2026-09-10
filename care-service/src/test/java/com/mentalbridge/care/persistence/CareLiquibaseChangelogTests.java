package com.mentalbridge.care.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

class CareLiquibaseChangelogTests {

	@Test
	void masterChangelogResolvesEveryFoundationChangeset() throws Exception {
		var path = "db/changelog/db.changelog-master.yaml";
		try (var resources = new ClassLoaderResourceAccessor()) {
			var parser = ChangeLogParserFactory.getInstance().getParser(path, resources);
			var changelog = parser.parse(path, new ChangeLogParameters(), resources);

			assertThat(changelog.getChangeSets()).extracting(changeSet -> changeSet.getId()).containsExactly(
					"care-001-pgcrypto",
					"care-002-profile-consent",
					"care-003-assessment-foundation",
					"care-004-phq9-reference-data",
					"care-005-assessment-safety-policy-provenance",
					"care-006-phq9-vi-vn-reference-data",
					"care-010-assessment-disclosure-provenance",
					"care-007-profile-preference-defaults",
					"care-008-gad7-and-phq9-v2-reference-data",
					"care-009-combined-support-routing");
		}
	}

}
