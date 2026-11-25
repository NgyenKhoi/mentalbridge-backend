package com.mentalbridge.consultation.video;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.mentalbridge.consultation.entitlement.EntitlementSource;

@Service
public class DemoVideoPolicy {

	private final LiveKitProperties properties;
	private final Environment environment;
	private final JdbcClient jdbc;
	private final Clock clock;

	public DemoVideoPolicy(LiveKitProperties properties, Environment environment, JdbcClient jdbc, Clock clock) {
		this.properties = properties;
		this.environment = environment;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@PostConstruct
	void validateEnvironment() {
		if (properties.isDemoImmediateAccessEnabled() && !developmentProfile()) {
			throw new IllegalStateException("Immediate demo video requires an explicit local, dev or test profile; staging/production is prohibited");
		}
	}

	public boolean allowsSource(EntitlementSource source) {
		return properties.isDemoImmediateAccessEnabled() && developmentProfile() && source == EntitlementSource.DEMO;
	}

	public boolean allowsUser(UUID userId) {
		if (userId == null || !allowsSource(EntitlementSource.DEMO)) return false;
		return jdbc.sql("""
				select exists(select 1 from current_service_entitlement
				 where account_id=:userId and source='DEMO' and package_code in ('PLUS','PREMIUM')
				 and effective_from<=:now and effective_until>:now)
				""").param("userId", userId).param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
				.query(Boolean.class).single();
	}

	public boolean allowsCredit(UUID userId, UUID creditId) {
		if (!allowsUser(userId)) return false;
		return jdbc.sql("""
				select exists(select 1 from service_credit c join service_credit_period p on p.id=c.period_id
				 where c.id=:creditId and p.account_id=:userId and p.source='DEMO' and c.state='HELD'
				 and p.period_start<=:now and p.period_end>:now)
				""").param("creditId", creditId).param("userId", userId)
				.param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).query(Boolean.class).single();
	}

	private boolean developmentProfile() {
		return environment.acceptsProfiles(Profiles.of("local", "dev", "test"))
				&& !environment.acceptsProfiles(Profiles.of("prod", "production", "staging"));
	}
}
