package com.mentalbridge.consultation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Operator runbook SQL, executed by tests so the documented read-only audits stay valid against the schema. */
public final class OperatorAuditQueries {

	private static final Pattern SQL_BLOCK = Pattern.compile("```sql\\R(.*?)```", Pattern.DOTALL);

	private OperatorAuditQueries() {
	}

	/** MB-795 runbook: [0] due REQUESTED rows expiry cannot release, [1] due video rows with incompatible end history. */
	public static List<String> mb795() throws IOException {
		var runbook = Files.readString(Path.of("..", "docs", "requirements-traceability", "stories", "mb-795.md"));
		return SQL_BLOCK.matcher(runbook).results().map(match -> match.group(1).strip().replaceFirst(";$", "")).toList();
	}
}
