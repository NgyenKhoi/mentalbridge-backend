package com.mentalbridge.consultation.billing;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Serializes owner-level commercial admission and cancellation decisions in one transaction. */
@Component
public class CommercialAccountLock {

	private final JdbcClient jdbc;

	public CommercialAccountLock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void lock(UUID accountId) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", "commercial-account:" + accountId)
				.query((row, ignored) -> {
					row.getObject(1);
					return Boolean.TRUE;
				}).single();
	}
}
