package com.mentalbridge.identity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

class IdentityLiquibaseChangelogTests {

	@Test
	void masterChangelogResolvesEveryBaselineChangeset() throws Exception {
		var path = "db/changelog/db.changelog-master.yaml";
		try (var resources = new ClassLoaderResourceAccessor()) {
			var parser = ChangeLogParserFactory.getInstance().getParser(path, resources);
			var changelog = parser.parse(path, new ChangeLogParameters(), resources);

			assertThat(changelog.getChangeSets()).hasSize(6);
			assertThat(changelog.getChangeSets()).extracting(changeSet -> changeSet.getId()).containsExactly(
					"identity-001-extensions",
					"identity-003-account-and-roles",
					"identity-004-refresh-sessions",
					"identity-005-challenges-idempotency",
					"identity-006-outbox-and-audit",
					"identity-007-single-account-role");
		}
	}

}
