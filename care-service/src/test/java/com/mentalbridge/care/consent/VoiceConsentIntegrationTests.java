package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.care.voice-consent.journal-service-token=synthetic-journal-to-care-at-least-32-characters")
@AutoConfigureMockMvc
class VoiceConsentIntegrationTests extends CareTestProperties {
	private static final String PUBLIC = "/api/v1/consents/ai-voice-processing";
	private static final String TOKEN = "synthetic-journal-to-care-at-least-32-characters";
	@Autowired VoiceConsentService voice;
	@Autowired ConsentService existing;
	@Autowired JdbcClient jdbc;
	@Autowired MockMvc mvc;
	@Autowired ConsentDecisionRepository decisions;
	@Autowired VoiceConsentProperties voiceProperties;

	@Test
	void v1HistoryNeedsV2ReconsentAndExactReplayCannotRelabelOrRestoreIt() throws Exception {
		var owner = profile();
		var session = UUID.randomUUID();
		existing.record(owner, "synthetic-v2-reconsent-ai-key", new ConsentService.DecisionCommand(
				"AI_PROCESSING", AiProcessingDisclosureService.VERSION, true));
		var key = UUID.randomUUID().toString();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(voiceProperties.idempotencyHmacKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		var storedKey = HexFormat.of().formatHex(mac.doFinal(("voice-consent-key|" + owner + "|" + key).getBytes(StandardCharsets.UTF_8)));
		var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				("AI_VOICE_PROCESSING|" + VoiceConsentService.LEGACY_VERSION + "|true").getBytes(StandardCharsets.UTF_8)));
		var old = decisions.saveAndFlush(new ConsentDecisionEntity(owner, "AI_VOICE_PROCESSING",
				VoiceConsentService.LEGACY_VERSION, true, storedKey, hash, Instant.parse("2026-10-31T00:00:00Z")));
		assertThat(voice.current(owner).current()).isFalse();
		assertThat(voice.current(owner).granted()).isFalse();
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		var legacyReplay = voice.record(owner, key, VoiceConsentService.LEGACY_VERSION, true);
		assertThat(legacyReplay.consentVersion()).isEqualTo(VoiceConsentService.LEGACY_VERSION);
		assertThat(legacyReplay.current()).isFalse();
		assertThat(legacyReplay.acceptedAt()).isEqualTo(old.decidedAt());
		assertThatThrownBy(() -> voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.LEGACY_VERSION, true))
				.isInstanceOfSatisfying(VoiceApiException.class, exception -> assertThat(exception.code()).isEqualTo("INVALID_REQUEST"));
		assertThatThrownBy(() -> voice.record(owner, key, VoiceConsentService.VERSION, true))
				.isInstanceOfSatisfying(VoiceApiException.class, exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
		voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, true);
		assertThat(voice.authorize(owner, session).authorized()).isTrue();
		voice.record(owner, key, VoiceConsentService.LEGACY_VERSION, true);
		assertThat(voice.current(owner).consentVersion()).isEqualTo(VoiceConsentService.VERSION);
		assertThat(voice.current(owner).granted()).isTrue();
		voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, false);
		voice.record(owner, key, VoiceConsentService.LEGACY_VERSION, true);
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		assertThat(existing.authorizeAiProcessing(owner).authorized()).isTrue();
		assertThat(jdbc.sql("select policy_version from consent_decision where id=:id").param("id", old.id()).query(String.class).single())
				.isEqualTo(VoiceConsentService.LEGACY_VERSION);
		assertThat(jdbc.sql("select count(*) from consent_decision where user_id=:owner and consent_type='AI_VOICE_PROCESSING'")
				.param("owner", owner).query(Long.class).single()).isEqualTo(3);
	}

	@Test
	void acceptsWithdrawsAndKeepsTextAndGenericCollectionUnchanged() throws Exception {
		var owner = profile();
		var session = UUID.randomUUID();
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		var key = UUID.randomUUID().toString();
		var original = voice.record(owner, key, VoiceConsentService.VERSION, true);
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		existing.record(owner, "synthetic-ai-consent-key-001", new ConsentService.DecisionCommand(
				"AI_PROCESSING", AiProcessingDisclosureService.VERSION, true));
		assertThat(voice.authorize(owner, session).authorized()).isTrue();
		assertThat(existing.current(owner)).hasSize(1).allSatisfy(decision -> assertThat(decision.consentType()).isEqualTo("AI_PROCESSING"));
		var withdrawn = voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, false);
		assertThat(withdrawn.revokedAt()).isNotNull();
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		assertThat(existing.authorizeAiProcessing(owner).authorized()).isTrue();
		assertThat(voice.record(owner, key, VoiceConsentService.VERSION, true)).isEqualTo(original);
		assertThat(voice.current(owner).granted()).isFalse();
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		assertThat(jdbc.sql("select count(*) from consent_decision where user_id=:owner and consent_type='AI_VOICE_PROCESSING'")
				.param("owner", owner).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select idempotency_key from consent_decision where user_id=:owner and consent_type='AI_VOICE_PROCESSING'")
				.param("owner", owner).query(String.class).list()).allSatisfy(stored -> assertThat(stored).matches("[0-9a-f]{64}").isNotEqualTo(key));
		mvc.perform(get("/api/v1/consents").with(user(owner))).andExpect(status().isOk())
				.andExpect(jsonPath("$.decisions.length()").value(1));
	}

	@Test
	void concurrentExactRetryAppendsOnceAndChangedIntentConflicts() throws Exception {
		var owner = profile();
		var key = UUID.randomUUID().toString();
		try (var pool = Executors.newFixedThreadPool(6)) {
			var calls = new ArrayList<Callable<VoiceConsentService.Consent>>();
			for (int i = 0; i < 6; i++) calls.add(() -> voice.record(owner, key, VoiceConsentService.VERSION, true));
			var results = pool.invokeAll(calls);
			var first = results.getFirst().get();
			for (var result : results) assertThat(result.get()).isEqualTo(first);
		}
		assertThat(jdbc.sql("select count(*) from consent_decision where user_id=:owner")
				.param("owner", owner).query(Long.class).single()).isEqualTo(1);
		assertThatThrownBy(() -> voice.record(owner, key, VoiceConsentService.VERSION, false))
				.isInstanceOfSatisfying(VoiceApiException.class, exception -> assertThat(exception.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
	}

	@Test
	void voiceUsesCurrentChatAndVoiceWithoutContextAndContextWithdrawalCannotEndIt() {
		var owner = profile();
		var session = UUID.randomUUID();
		existing.record(owner, "synthetic-l1-voice-grant", new ConsentService.DecisionCommand("AI_PROCESSING", AiProcessingDisclosureService.VERSION, true));
		voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, true);
		assertThat(existing.authorizeAiPurposes(owner).context().reason()).isEqualTo("MISSING");
		assertThat(voice.authorize(owner, session).authorized()).isTrue();
		existing.record(owner, "synthetic-l2-voice-grant", new ConsentService.DecisionCommand("AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, true));
		existing.record(owner, "synthetic-l2-voice-revoke", new ConsentService.DecisionCommand("AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, false));
		assertThat(voice.authorize(owner, session).authorized()).isTrue();
		existing.record(owner, "synthetic-l1-voice-revoke", new ConsentService.DecisionCommand("AI_PROCESSING", AiProcessingDisclosureService.VERSION, false));
		assertThat(voice.authorize(owner, session).authorized()).isFalse();
		existing.record(owner, "synthetic-l1-voice-regrant", new ConsentService.DecisionCommand("AI_PROCESSING", AiProcessingDisclosureService.VERSION, true));
		assertThat(voice.authorize(owner, session).authorized()).isTrue();
		assertThat(existing.authorizeAiPurposes(owner).context().reason()).isEqualTo("REVOKED");
	}

	@Test
	void constraintAllowsVoiceButStillRejectsUnknownPurposeAndPreservesLegacyDecision() {
		var owner = profile();
		existing.record(owner, "synthetic-privacy-consent-001", new ConsentService.DecisionCommand("PRIVACY_POLICY", "privacy-capstone-v3", true));
		voice.record(owner, UUID.randomUUID().toString(), VoiceConsentService.VERSION, true);
		assertThatThrownBy(() -> jdbc.sql("""
				insert into consent_decision(user_id,consent_type,policy_version,granted,idempotency_key,request_hash,decided_at)
				values(:owner,'UNKNOWN_PURPOSE','invalid-v1',true,'synthetic-invalid-key',:hash,now())
				""").param("owner", owner).param("hash", "a".repeat(64)).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(existing.current(owner)).hasSize(1);
		assertThat(jdbc.sql("select count(*) from databasechangelog where id='care-033-ai-voice-processing-consent'")
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void realHttpBoundaryUsesOwnedSubjectAndServiceAssertionWithoutUserBearer() throws Exception {
		var owner = profile();
		var other = profile();
		var body = "{\"consentVersion\":\"ai-voice-processing-capstone-v2\",\"granted\":true}";
		mvc.perform(post(PUBLIC).with(user(owner)).header("Idempotency-Key", UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.acceptedAt").isString());
		mvc.perform(get(PUBLIC).with(user(other))).andExpect(status().isOk()).andExpect(jsonPath("$.current").value(false));
		var session = UUID.randomUUID();
		mvc.perform(post("/internal/v1/ai-companion/voice-consent-authorizations")
				.header("X-MentalBridge-Voice-Care-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"ownerId\":\"" + owner + "\",\"voiceSessionId\":\"" + session + "\",\"purpose\":\"AI_COMPANION_VOICE\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.ownerId").value(owner.toString()))
				.andExpect(jsonPath("$.voiceSessionId").value(session.toString()))
				.andExpect(jsonPath("$.voiceProcessingGranted").value(true))
				.andExpect(jsonPath("$.aiProcessingGranted").value(false)).andExpect(jsonPath("$.authorized").value(false));
	}

	private UUID profile() {
		var owner = UUID.randomUUID();
		jdbc.sql("insert into user_profile(account_id,display_name) values(:id,'Synthetic voice user')").param("id", owner).update();
		return owner;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID owner) {
		return jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
