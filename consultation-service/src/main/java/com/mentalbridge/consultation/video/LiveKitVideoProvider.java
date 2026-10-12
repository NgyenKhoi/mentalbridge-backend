package com.mentalbridge.consultation.video;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
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

@Component
public class LiveKitVideoProvider implements VideoProvider {

	private final LiveKitProperties properties;
	private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS)
			.readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
			.retryOnConnectionFailure(false).build();

	public LiveKitVideoProvider(LiveKitProperties properties) { this.properties = properties; }

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
		try {
			var response = client().createRoom(room, 300, 2).execute();
			if (!response.isSuccessful() || response.body() == null) throw unavailable();
		}
		catch (IOException exception) { throw unavailable(); }
	}

	@Override
	public void closeRoom(String room, List<String> identities, Instant cutoff) {
		try {
			var client = client();
			for (var identity : identities) {
				var response = client.removeParticipant(room, identity, cutoff.getEpochSecond()).execute();
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
			return new VerifiedVideoEvent(event.getId(), event.getEvent(), event.getRoom().getName(),
					event.getRoom().getSid(), event.getParticipant().getIdentity(),
					event.getParticipant().getSid(), Instant.ofEpochSecond(event.getCreatedAt()));
		}
		catch (RuntimeException exception) { throw invalidWebhook(); }
	}

	private RoomServiceClient client() {
		requireConfiguration();
		return RoomServiceClient.createClient(properties.getUrl(), properties.getApiKey(), properties.getApiSecret(),
				() -> http, false);
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
