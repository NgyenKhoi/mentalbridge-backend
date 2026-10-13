package com.mentalbridge.consultation.video;

import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.mentalbridge.consultation.shared.ApiException;

import io.livekit.server.AccessToken;
import io.livekit.server.Agent;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.IngressAdmin;
import io.livekit.server.RoomAdmin;
import io.livekit.server.RoomCreate;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomList;
import io.livekit.server.RoomName;
import io.livekit.server.RoomRecord;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import okhttp3.OkHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

@Component
public class LiveKitVideoProvider implements VideoProvider {

	private final LiveKitProperties properties;
	private final OkHttpClient http;
	private final CircuitBreaker breaker = CircuitBreaker.of("livekit-room-api", CircuitBreakerConfig.custom()
			.minimumNumberOfCalls(3).slidingWindowSize(10).failureRateThreshold(50)
			.waitDurationInOpenState(Duration.ofSeconds(30)).permittedNumberOfCallsInHalfOpenState(1).build());

	@Autowired
	public LiveKitVideoProvider(LiveKitProperties properties) {
		this(properties, new OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS)
				.readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false).build());
	}

	LiveKitVideoProvider(LiveKitProperties properties, OkHttpClient http) {
		this.properties = properties;
		this.http = http;
	}

	@Override
	public String issueToken(String room, String identity, Instant issuedAt, Instant expiresAt) {
		requireConfiguration();
		var token = new AccessToken(properties.getApiKey(), properties.getApiSecret());
		token.setIdentity(identity);
		token.setNotBefore(Date.from(issuedAt));
		token.setExpiration(Date.from(expiresAt));
		token.addGrants(new RoomJoin(true), new RoomName(room), new CanPublish(true),
				new CanPublishSources(List.of("camera", "microphone")), new CanSubscribe(true),
				new CanPublishData(false), new CanUpdateOwnMetadata(false), new RoomAdmin(false),
				new RoomCreate(false), new RoomList(false), new RoomRecord(false),
				new IngressAdmin(false), new Agent(false));
		return token.toJwt();
	}

	@Override
	public void prepareRoom(String room) {
		remote(() -> prepare(room));
	}

	private void prepare(String room) {
		try {
			var response = client().createRoom(room, 300, 2).execute();
			if (!response.isSuccessful() || response.body() == null) throw unavailable();
		}
		catch (IOException exception) { throw unavailable(); }
	}

	@Override
	public void closeRoom(String room, List<String> identities, Instant cutoff) {
		remote(() -> close(room, identities, cutoff));
	}

	private void close(String room, List<String> identities, Instant cutoff) {
		try {
			var client = client();
			for (var identity : identities) {
				var revocation = Math.max(cutoff.getEpochSecond(), Instant.now().getEpochSecond()) + 1;
				var response = client.removeParticipant(room, identity, revocation).execute();
				if (!response.isSuccessful()) throw unavailable();
			}
			var response = client.deleteRoom(room).execute();
			if (!response.isSuccessful() && response.code() != 404) throw unavailable();
		}
		catch (IOException exception) { throw unavailable(); }
	}

	@Override
	public VerifiedVideoEvent verifyWebhook(String body, String authorization) {
		requireConfiguration();
		if (body == null || body.length() > 65_536 || authorization == null || authorization.length() > 8_192) {
			throw invalidWebhook();
		}
		try {
			var event = new WebhookReceiver(properties.getApiKey(), properties.getApiSecret()).receive(body, authorization);
			var occurredAt = Instant.ofEpochSecond(event.getCreatedAt());
			String identity = null;
			String participantSid = null;
			switch (event.getEvent()) {
				case "participant_joined", "participant_left" -> {
					var participant = event.getParticipant();
					if (participant == null || participant.getIdentity().isBlank() || participant.getSid().isBlank()) {
						throw invalidWebhook();
					}
					identity = participant.getIdentity();
					participantSid = participant.getSid();
				}
				case "room_started", "room_finished" -> { }
				default -> {
					return new VerifiedVideoEvent(event.getId(), event.getEvent(), null, null, null, null, occurredAt);
				}
			}
			var room = event.getRoom();
			if (room == null || room.getName().isBlank() || room.getSid().isBlank()) throw invalidWebhook();
			return new VerifiedVideoEvent(event.getId(), event.getEvent(), room.getName(), room.getSid(),
					identity, participantSid, occurredAt);
		}
		catch (RuntimeException exception) { throw invalidWebhook(); }
	}

	private RoomServiceClient client() {
		requireConfiguration();
		return RoomServiceClient.createClient(properties.getUrl(), properties.getApiKey(), properties.getApiSecret(),
				() -> http, false);
	}

	private void remote(Runnable command) {
		requireConfiguration();
		try { breaker.executeRunnable(command); }
		catch (RuntimeException exception) { throw unavailable(); }
	}

	private void requireConfiguration() {
		if (!properties.configured()) throw unavailable();
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_PROVIDER_UNAVAILABLE", "Video is temporarily unavailable");
	}

	private ApiException invalidWebhook() {
		return new ApiException(HttpStatus.UNAUTHORIZED, "VIDEO_WEBHOOK_INVALID", "Video webhook authentication failed");
	}
}
