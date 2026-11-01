package com.mentalbridge.consultation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

class ConsultationLiquibaseChangelogTests {

	private static final String MASTER = "db/changelog/db.changelog-master.yaml";
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
			.withResolverStyle(ResolverStyle.STRICT);

	@Test
	void masterPreservesAppliedIdentitiesChecksumsAndOrderBeforeNewChangesets() throws Exception {
		var baseline = legacyHistory();
		try (var resources = new ClassLoaderResourceAccessor()) {
			var parser = ChangeLogParserFactory.getInstance().getParser(MASTER, resources);
			var changes = parser.parse(MASTER, new ChangeLogParameters(), resources).getChangeSets();
			assertThat(changes).hasSizeGreaterThanOrEqualTo(baseline.size());
			assertThat(changes).extracting(change -> change.getFilePath() + "::" + change.getId() + "::" + change.getAuthor())
					.doesNotHaveDuplicates();
			for (int index = 0; index < baseline.size(); index++) {
				var expected = baseline.get(index);
				var actual = changes.get(index);
				assertThat(actual.getId()).isEqualTo(expected.id());
				assertThat(actual.getAuthor()).isEqualTo(expected.author());
				assertThat(actual.getFilePath()).isEqualTo(expected.filename());
				assertThat(actual.generateCheckSum(ChecksumVersion.V9).toString()).isEqualTo(expected.checksum());
				assertThat(actual.isAlwaysRun()).isFalse();
				assertThat(actual.isRunOnChange()).isFalse();
			}
			var legacyPaths = baseline.stream().map(LegacyChange::filename).toList();
			assertThat(changes.subList(baseline.size(), changes.size()))
					.allSatisfy(change -> assertThat(change.getFilePath()).isNotIn(legacyPaths));
			assertThat(changes.subList(baseline.size(), changes.size()).stream()
					.map(change -> change.getFilePath()).distinct()
					.map(path -> timestampPrefix(Path.of(path).getFileName().toString())))
					.doesNotHaveDuplicates();
			try (var files = Files.list(Path.of("src/main/resources/db/changelog/changes"))) {
				var sqlPaths = files.filter(file -> file.toString().endsWith(".sql"))
						.map(file -> "db/changelog/changes/" + file.getFileName()).toList();
				assertThat(changes).extracting(change -> change.getFilePath()).containsAll(sqlPaths);
				var newFiles = sqlPaths.stream().filter(path -> !legacyPaths.contains(path)).toList();
				assertThat(newFiles.stream().map(path -> timestampPrefix(Path.of(path).getFileName().toString())))
						.doesNotHaveDuplicates();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"20261022210000-add-owner-audit.sql", "20280229091500-add-booking-index.sql"})
	void acceptsUtcTimestampNamesForFutureMigrations(String name) {
		assertThat(timestampPrefix(name)).hasSize(14);
	}

	@ParameterizedTest
	@ValueSource(strings = {"029-next-change.sql", "021-another-change.sql", "20260230091500-invalid-day.sql",
			"20261022250000-invalid-hour.sql", "20261022210000.sql", "20261022210000-Uppercase.sql"})
	void rejectsNewNumericNamesAndInvalidTimestamps(String name) {
		assertThatThrownBy(() -> timestampPrefix(name)).isInstanceOf(IllegalArgumentException.class);
	}

	static List<LegacyChange> legacyHistory() throws Exception {
		try (var stream = ConsultationLiquibaseChangelogTests.class
				.getResourceAsStream("/db/changelog/consultation-legacy-v1.tsv")) {
			assertThat(stream).isNotNull();
			try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				var rows = reader.lines().skip(1).map(line -> line.split("\t", -1))
						.map(row -> new LegacyChange(row[0], row[1], row[2], row[3])).toList();
				assertThat(rows).hasSize(29);
				return rows;
			}
		}
	}

	private static String timestampPrefix(String name) {
		if (!name.matches("[0-9]{14}-[a-z0-9]+(?:-[a-z0-9]+)*\\.sql"))
			throw new IllegalArgumentException("Use UTC yyyyMMddHHmmss-description.sql");
		var timestamp = name.substring(0, 14);
		try {
			LocalDateTime.parse(timestamp, TIMESTAMP);
		}
		catch (java.time.format.DateTimeParseException cause) {
			throw new IllegalArgumentException("Invalid UTC migration timestamp", cause);
		}
		return timestamp;
	}

	record LegacyChange(String id, String author, String filename, String checksum) {}
}
