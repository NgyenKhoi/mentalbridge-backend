package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.mentalbridge.consultation.shared.ApiException;

class LiveKitVideoProviderTests {

	@Test
	void tokenRestrictsIdentityRoomSourcesAndShortLifetimeWithoutMetadata() {
		var properties = configured();
		var provider = new LiveKitVideoProvider(properties);
		var now = Instant.now().minusSeconds(1);
		var expires = now.plusSeconds(120);
		var jwt = JWT.require(Algorithm.HMAC256(properties.getApiSecret())).withIssuer(properties.getApiKey())
				.build().verify(provider.issueToken("opaque-room", "opaque-participant", now, expires));
		assertThat(jwt.getSubject()).isEqualTo("opaque-participant");
		assertThat(jwt.getExpiresAtAsInstant()).isEqualTo(expires.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		var grants = jwt.getClaim("video").asMap();
		assertThat(grants).containsEntry("room", "opaque-room").containsEntry("roomJoin", true)
				.containsEntry("canPublishData", false).containsEntry("canUpdateOwnMetadata", false)
				.containsEntry("roomAdmin", false).containsEntry("roomCreate", false)
				.containsEntry("roomRecord", false).containsEntry("agent", false);
		assertThat(grants.get("canPublishSources")).isEqualTo(java.util.List.of("camera", "microphone"));
		assertThat(jwt.getClaim("metadata").isMissing()).isTrue();
	}

	@Test
	void officialReceiverVerifiesSignatureAndRawBodyHash() throws Exception {
		var properties = configured();
		var provider = new LiveKitVideoProvider(properties);
		var body = """
				{"id":"event-1","event":"participant_joined","createdAt":1700000000,
				 "room":{"name":"opaque-room","sid":"RM_1"},
				 "participant":{"identity":"opaque-participant","sid":"PA_1"}}
				""";
		var header = signature(properties, body, Instant.now().plusSeconds(120));
		var event = provider.verifyWebhook(body, header);
		assertThat(event.type()).isEqualTo("participant_joined");
		assertThat(event.participantSid()).isEqualTo("PA_1");
		assertThatThrownBy(() -> provider.verifyWebhook(body + " ", header)).isInstanceOf(ApiException.class)
				.hasMessage("Video webhook authentication failed");
		assertThatThrownBy(() -> provider.verifyWebhook(body, "invalid")).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> provider.verifyWebhook(body, signature(properties, body, Instant.now().minusSeconds(60))))
				.isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> provider.verifyWebhook(body, null)).isInstanceOf(ApiException.class);
		var noExpiry = JWT.create().withIssuer(properties.getApiKey()).sign(Algorithm.HMAC256(properties.getApiSecret()));
		assertThatThrownBy(() -> provider.verifyWebhook(body, noExpiry)).isInstanceOf(ApiException.class);
	}

	@Test
	void missingCredentialsAndNonCloudOriginsFailClosedWithoutSecretErrors() {
		var missing = new LiveKitProperties();
		assertThat(missing.configured()).isFalse();
		missing.validateEnabledConfiguration();
		missing.setEnabled(true);
		assertThatThrownBy(missing::validateEnabledConfiguration).isInstanceOf(IllegalStateException.class)
				.hasMessage("Enabled video requires valid LiveKit Cloud configuration");
		assertThatThrownBy(() -> new LiveKitVideoProvider(missing).issueToken("room", "identity", Instant.now(), Instant.now()))
				.isInstanceOf(ApiException.class).hasMessage("Video is temporarily unavailable");
		var properties = configured();
		properties.setUrl("wss://project.livekit.cloud.evil.example");
		assertThat(properties.configured()).isFalse();
		properties.setUrl("wss://project.livekit.cloud?secret=test");
		assertThat(properties.configured()).isFalse();
		properties.setUrl("wss://project.livekit.cloud");
		properties.setTokenTtl(java.time.Duration.ofHours(1));
		assertThat(properties.configured()).isFalse();
	}

	@Test
	void remoteRoomCreationAndClosureUseOfficialSdkAndExplicitSecondBasedRevocation() throws Exception {
		var calls = new java.util.ArrayList<String>();
		var bodies = new java.util.ArrayList<byte[]>();
		var http = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
			var request = chain.request();
			calls.add(request.url().encodedPath());
			var buffer = new okio.Buffer();
			request.body().writeTo(buffer);
			bodies.add(buffer.readByteArray());
			var payload = request.url().encodedPath().endsWith("CreateRoom")
					? livekit.LivekitModels.Room.newBuilder().setSid("RM_SYNTHETIC").setName("opaque-room").build().toByteArray()
					: new byte[0];
			return new okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
					.body(okhttp3.ResponseBody.create(payload, okhttp3.MediaType.get("application/protobuf"))).build();
		}).build();
		var provider = new LiveKitVideoProvider(configured(), http);
		var before = Instant.now().getEpochSecond();
		provider.prepareRoom("opaque-room");
		provider.closeRoom("opaque-room", java.util.List.of("user-opaque", "specialist-opaque"), Instant.now());
		assertThat(calls).hasSize(4);
		assertThat(calls.getFirst()).endsWith("CreateRoom");
		assertThat(calls.getLast()).endsWith("DeleteRoom");
		var created = livekit.LivekitRoom.CreateRoomRequest.parseFrom(bodies.getFirst());
		assertThat(created.getName()).isEqualTo("opaque-room");
		assertThat(created.getMaxParticipants()).isEqualTo(2);
		for (var index : java.util.List.of(1, 2)) {
			var removed = livekit.LivekitRoom.RoomParticipantIdentity.parseFrom(bodies.get(index));
			assertThat(removed.getRoom()).isEqualTo("opaque-room");
			var cutoff = removed.getRevokeTokenTs();
			assertThat(cutoff).isBetween(before + 1, Instant.now().getEpochSecond() + 1);
		}
	}

	@Test
	void providerHttpFailuresAreSanitizedAndCircuitOpensWithoutAutomaticRetry() {
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var http = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
			calls.incrementAndGet();
			throw new java.io.IOException("synthetic-provider-sensitive-error");
		}).build();
		var provider = new LiveKitVideoProvider(configured(), http);
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThatThrownBy(() -> provider.prepareRoom("opaque-room")).isInstanceOf(ApiException.class)
					.hasMessage("Video is temporarily unavailable");
		}
		assertThat(calls.get()).isEqualTo(3);
	}

	static LiveKitProperties configured() {
		var properties = new LiveKitProperties();
		properties.setUrl("wss://synthetic-project.livekit.cloud");
		properties.setApiKey("synthetic-api-key");
		properties.setApiSecret("synthetic-api-secret-not-a-real-key");
		return properties;
	}

	static String signature(LiveKitProperties properties, String body, Instant expiry) throws Exception {
		var hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
				.digest(body.getBytes(StandardCharsets.UTF_8)));
		return JWT.create().withIssuer(properties.getApiKey()).withExpiresAt(Date.from(expiry))
				.withClaim("sha256", hash).sign(Algorithm.HMAC256(properties.getApiSecret()));
	}
}
