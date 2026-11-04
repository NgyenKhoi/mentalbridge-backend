package com.mentalbridge.consultation.earnings;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class PayoutBankDirectory {

	private static final URI BANKS = URI.create("https://api.vietqr.io/v2/banks");
	private static final int MAX_RESPONSE_BYTES = 1_048_576;
	private final HttpClient client;
	private final ObjectMapper json;
	private final Clock clock;
	private volatile BankList cached;

	@Autowired
	public PayoutBankDirectory(ObjectMapper json, Clock clock) {
		this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
				.followRedirects(HttpClient.Redirect.NEVER).build(), json, clock);
	}

	PayoutBankDirectory(HttpClient client, ObjectMapper json, Clock clock) {
		this.client = client;
		this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.clock = clock;
	}

	public synchronized BankList banks() {
		if (cached != null && clock.instant().isBefore(cached.generatedAt().plus(Duration.ofHours(24)))) {
			return cached;
		}
		var request = HttpRequest.newBuilder(BANKS).timeout(Duration.ofSeconds(5))
				.header("Accept", "application/json").GET().build();
		try {
			var response = client.send(request, boundedBody());
			{
				if (response.statusCode() != 200) throw unavailable();
				var bytes = response.body();
				if (bytes.length > MAX_RESPONSE_BYTES) throw unavailable();
				var root = json.readTree(bytes);
				var data = root.path("data");
				if (!"00".equals(root.path("code").asText()) || !data.isArray()
						|| data.isEmpty() || data.size() > 1000) throw unavailable();
				var bins = new HashSet<String>();
				var items = new ArrayList<Bank>();
				for (var item : data) {
					if (!item.path("bin").isTextual() || !item.path("name").isTextual()
							|| !item.path("shortName").isTextual()) throw unavailable();
					var bin = item.path("bin").textValue();
					var name = item.path("name").textValue().trim();
					var shortName = item.path("shortName").textValue().trim();
					if (!bin.matches("[0-9]{6}") || name.isBlank() || shortName.isBlank()
							|| name.length() > 300 || shortName.length() > 100 || !bins.add(bin)) throw unavailable();
					items.add(new Bank(bin, name, shortName));
				}
				cached = new BankList(clock.instant(), List.copyOf(items));
				return cached;
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (Exception exception) {
			throw unavailable();
		}
	}

	static HttpResponse.BodyHandler<byte[]> boundedBody() {
		return ignored -> new HttpResponse.BodySubscriber<>() {
			private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
			private Flow.Subscription subscription;
			private long bytes;
			private boolean exceeded;

			@Override
			public CompletionStage<byte[]> getBody() { return body.getBody(); }

			@Override
			public void onSubscribe(Flow.Subscription value) {
				subscription = value;
				body.onSubscribe(value);
			}

			@Override
			public void onNext(List<ByteBuffer> buffers) {
				if (exceeded) return;
				for (var buffer : buffers) bytes += buffer.remaining();
				if (bytes > MAX_RESPONSE_BYTES) {
					exceeded = true;
					subscription.cancel();
					body.onError(new IOException("Payout bank directory response exceeds limit"));
				}
				else body.onNext(buffers);
			}

			@Override
			public void onError(Throwable error) { if (!exceeded) body.onError(error); }

			@Override
			public void onComplete() { if (!exceeded) body.onComplete(); }
		};
	}

	static ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOUT_BANK_LIST_UNAVAILABLE",
				"Payout bank directory is unavailable");
	}

	public record BankList(Instant generatedAt, List<Bank> items) {}
	public record Bank(String bin, String name, String shortName) {}
}
