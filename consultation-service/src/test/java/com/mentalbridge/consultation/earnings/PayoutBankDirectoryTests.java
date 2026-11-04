package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

class PayoutBankDirectoryTests {

	@Test
	void proxiesOnlyBinAndNamesAndCachesForExactly24Hours() throws Exception {
		var client = mock(HttpClient.class);
		var response = response(200, """
				{"code":"00","data":[{"bin":"970436","name":"Synthetic bank","shortName":"SYN",
				"transferSupported":1,"logo":"https://irrelevant.invalid","code":"UNUSED"}]}
				""");
		doReturn(response).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		var now = new AtomicReference<>(Instant.parse("2026-11-04T00:00:00Z"));
		var clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(ignored -> now.get());
		var directory = new PayoutBankDirectory(client, new ObjectMapper(), clock);
		var first = directory.banks();
		assertThat(first.items()).containsExactly(new PayoutBankDirectory.Bank("970436", "Synthetic bank", "SYN"));
		now.set(now.get().plusSeconds(86_399));
		assertThat(directory.banks()).isSameAs(first);
		var request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
		assertThat(request.getValue().uri().toString()).isEqualTo("https://api.vietqr.io/v2/banks");
		assertThat(request.getValue().timeout().orElseThrow().toSeconds()).isEqualTo(5);
		now.set(now.get().plusSeconds(1));
		assertThat(directory.banks().generatedAt()).isEqualTo(now.get());
		verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
	}

	@Test
	void expiredDirectoryOutageFailsClosedAndNeverServesStaleBankData() throws Exception {
		var client = mock(HttpClient.class);
		doReturn(response(200, "{\"code\":\"00\",\"data\":[{\"bin\":\"970436\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"}]}"))
				.when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		var now = new AtomicReference<>(Instant.parse("2026-11-04T00:00:00Z"));
		var clock = mock(Clock.class);
		when(clock.instant()).thenAnswer(ignored -> now.get());
		var directory = new PayoutBankDirectory(client, new ObjectMapper(), clock);
		directory.banks();
		now.set(now.get().plusSeconds(86_400));
		doReturn(response(503, "untrusted provider text"))
				.when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
		assertThatThrownBy(directory::banks).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo("PAYOUT_BANK_LIST_UNAVAILABLE"));
		assertThatThrownBy(directory::banks).hasMessage("Payout bank directory is unavailable");
	}

	@Test
	void malformedEnvelopeDuplicateBinsAndUnboundedListsAreRejected() throws Exception {
		var client = mock(HttpClient.class);
		for (var payload : new String[] {
				"{\"code\":\"99\",\"data\":[]}",
				"{\"code\":\"00\",\"data\":[{\"bin\":\"INVALID\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"}]}",
				"{\"code\":\"00\",\"data\":[{\"bin\":970436,\"name\":\"Synthetic\",\"shortName\":\"SYN\"}]}",
				"{\"code\":\"00\",\"data\":[{\"bin\":\"970436\",\"name\":123,\"shortName\":\"SYN\"}]}",
				"{\"code\":\"00\",\"data\":[{\"bin\":\"970436\",\"bin\":\"970436\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"}]}",
				"{\"code\":\"00\",\"data\":[{\"bin\":\"970436\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"},"
						+ "{\"bin\":\"970436\",\"name\":\"Duplicate\",\"shortName\":\"DUP\"}]}",
				"{\"code\":\"00\",\"data\":[" + "{\"bin\":\"970436\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"},".repeat(1000)
						+ "{\"bin\":\"970436\",\"name\":\"Synthetic\",\"shortName\":\"SYN\"}]}",
				"not json"}) {
			doReturn(response(200, payload)).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
			var directory = new PayoutBankDirectory(client, new ObjectMapper(), Clock.systemUTC());
			assertThatThrownBy(directory::banks).isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.code()).isEqualTo("PAYOUT_BANK_LIST_UNAVAILABLE"));
		}
	}

	@Test
	void responseSubscriptionCancelsBeforeAccumulatingAnOversizedBody() {
		var subscription = mock(Flow.Subscription.class);
		var body = PayoutBankDirectory.boundedBody().apply(null);
		body.onSubscribe(subscription);
		body.onNext(List.of(ByteBuffer.wrap(new byte[1_048_576])));
		assertThat(body.getBody().toCompletableFuture()).isNotDone();
		body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
		verify(subscription).cancel();
		assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
				.hasCauseInstanceOf(java.io.IOException.class)
				.hasRootCauseMessage("Payout bank directory response exceeds limit");
	}

	@Test
	void interruptedRequestRestoresInterruptionAndReturnsOnlySafeUnavailableCode() throws Exception {
		var client = mock(HttpClient.class);
		when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
		try {
			var directory = new PayoutBankDirectory(client, new ObjectMapper(), Clock.systemUTC());
			assertThatThrownBy(directory::banks).isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.code()).isEqualTo("PAYOUT_BANK_LIST_UNAVAILABLE"));
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally { Thread.interrupted(); }
	}

	@SuppressWarnings("unchecked")
	private HttpResponse<byte[]> response(int status, String body) {
		var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(status);
		when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
		return response;
	}
}
