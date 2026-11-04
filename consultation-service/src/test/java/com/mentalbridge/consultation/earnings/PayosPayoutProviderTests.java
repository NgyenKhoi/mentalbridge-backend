package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import vn.payos.core.ClientOptions;

class PayosPayoutProviderTests {

	private final PayoutProperties properties = PayoutPropertiesTests.payosProperties();
	private final ObjectMapper json = new ObjectMapper();
	private final PayoutDestinationCipher cipher = new PayoutDestinationCipher(properties);
	private final UUID payoutId = UUID.randomUUID();
	private final UUID attemptId = UUID.randomUUID();
	private final String requestId = UUID.randomUUID().toString();

	@Test
	void optionsDisableAutomaticRetriesRedirectsAndSensitiveLogging() {
		var options = PayosPayoutProvider.sdkOptions(properties);
		assertThat(options.getMaxRetries()).isZero();
		assertThat(options.getLogLevel()).isEqualTo(ClientOptions.LogLevel.NONE);
		assertThat(options.getHttpClient().retryOnConnectionFailure()).isFalse();
		assertThat(options.getHttpClient().followRedirects()).isFalse();
		assertThat(options.getHttpClient().followSslRedirects()).isFalse();
		assertThat(options.getHttpClient().callTimeoutMillis()).isEqualTo(10_000);
	}

	@Test
	void sendsExactlyOneValidatedBatchWithPersistedKeyAndMatchesSignedChild() throws Exception {
		try (var fixture = new Fixture()) {
			var result = fixture.provider.submit(command());
			assertThat(result.status()).isEqualTo("SUCCEEDED");
			assertThat(result.providerReference()).isEqualTo("outer-payout");
			assertThat(result.childReference()).isEqualTo("child-transaction");
			assertThat(fixture.paths).containsExactly("/v1/payouts/batch");
			assertThat(fixture.keys).containsExactly(requestId);
			var request = json.readTree(fixture.bodies.getFirst());
			assertThat(request.path("referenceId").asText()).isEqualTo(requestId);
			assertThat(request.path("validateDestination").booleanValue()).isTrue();
			assertThat(request.path("payouts").size()).isOne();
			var child = request.path("payouts").get(0);
			assertThat(child.path("referenceId").asText()).isEqualTo(attemptId.toString());
			assertThat(child.path("amount").isIntegralNumber()).isTrue();
			assertThat(child.path("amount").longValue()).isEqualTo(210_000);
			assertThat(child.path("toBin").asText()).isEqualTo("970436");
			assertThat(child.path("toAccountNumber").asText()).isEqualTo("1234567890");
			assertThat(fixture.signatures.getFirst()).isEqualTo(sign(json.convertValue(request, Map.class)));
		}
	}

	@Test
	void lostCreateIsRecoveredByOuterReferenceListThenGetWithoutSecondCreate() throws Exception {
		try (var fixture = new Fixture()) {
			fixture.httpStatus = 500;
			assertThat(fixture.provider.submit(command()).status()).isEqualTo("UNKNOWN");
			fixture.httpStatus = 200;
			properties.setMode("DISABLED");
			var provider = new ConfiguredPayoutProvider(properties, org.mockito.Mockito.mock(MomoPayoutProvider.class), fixture.provider);
			var result = provider.query(query(null));
			assertThat(result.status()).isEqualTo("SUCCEEDED");
			assertThat(result.providerReference()).isEqualTo("outer-payout");
			assertThat(fixture.paths).hasSize(3);
			assertThat(fixture.paths.getFirst()).isEqualTo("/v1/payouts/batch");
			assertThat(fixture.paths.get(1)).startsWith("/v1/payouts?").contains("referenceId=" + requestId, "limit=2", "offset=0");
			assertThat(fixture.paths.getLast()).isEqualTo("/v1/payouts/outer-payout");
			assertThat(fixture.paths.stream().filter(path -> path.equals("/v1/payouts/batch")).count()).isOne();
		}
	}

	@Test
	void explicitRetryUsesTheOriginalProviderWhileNewSubmissionsUseSelectedMode() throws Exception {
		try (var fixture = new Fixture()) {
			properties.setMode("FAKE");
			var provider = new ConfiguredPayoutProvider(properties, org.mockito.Mockito.mock(MomoPayoutProvider.class), fixture.provider);
			assertThat(provider.submit("PAYOS", command()).providerReference()).isEqualTo("outer-payout");
			assertThat(provider.submit(command()).providerReference()).isEqualTo("fake-" + payoutId);
			assertThat(fixture.paths).containsExactly("/v1/payouts/batch");
		}
	}

	@Test
	void onlySignedMatchingChildSuccessPaysAndOuterApprovalNeverPaysAlone() throws Exception {
		try (var fixture = new Fixture()) {
			fixture.tamper = true;
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			fixture.tamper = false;
			fixture.omitSignature = true;
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			fixture.omitSignature = false;
			for (var mismatch : List.of("amount", "referenceId", "toBin", "toAccountNumber")) {
				fixture.data = payout("SUCCEEDED");
				var child = transaction(fixture.data);
				child.put(mismatch, mismatch.equals("amount") ? 210_001 : "mismatch");
				assertThat(fixture.provider.query(query("outer-payout")).status()).as(mismatch).isEqualTo("UNKNOWN");
			}
			fixture.data = payout("SUCCEEDED");
			fixture.data.put("referenceId", UUID.randomUUID().toString());
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			fixture.data = payout("SUCCEEDED");
			fixture.data.put("id", "different-outer");
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			for (var state : List.of("RECEIVED", "PROCESSING", "ON_HOLD")) {
				fixture.data = payout(state);
				assertThat(fixture.provider.query(query("outer-payout")).status()).as(state).isEqualTo("PROCESSING");
			}
			fixture.data.put("approvalState", "REJECTED");
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
		}
	}

	@Test
	void incompleteDuplicateAndContradictorySignedChildrenNeverProveSettlement() throws Exception {
		try (var fixture = new Fixture()) {
			for (var transactions : List.of(List.of(), List.of(transaction(payout("SUCCEEDED")), transaction(payout("SUCCEEDED"))))) {
				fixture.data = payout("SUCCEEDED");
				fixture.data.put("transactions", transactions);
				assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			}
			for (var missing : List.of("id", "referenceId", "amount", "toBin", "toAccountNumber", "state")) {
				fixture.data = payout("SUCCEEDED");
				transaction(fixture.data).remove(missing);
				assertThat(fixture.provider.query(query("outer-payout")).status()).as(missing).isEqualTo("UNKNOWN");
			}
			fixture.data = payout("SUCCEEDED");
			transaction(fixture.data).put("errorCode", "624");
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			for (var approval : List.of("REJECTED", "CANCELLED", "FAILED", "UNFAMILIAR")) {
				fixture.data = payout("SUCCEEDED");
				fixture.data.put("approvalState", approval);
				assertThat(fixture.provider.query(query("outer-payout")).status()).as(approval).isEqualTo("UNKNOWN");
			}
		}
	}

	@Test
	void responseMoneyMustBeSignedIntegerAndDuplicateJsonOrUnknownStatesAreUncertain() throws Exception {
		try (var fixture = new Fixture()) {
			for (var amount : List.of("210000", 210000.0, 210000.5)) {
				fixture.data = payout("SUCCEEDED");
				transaction(fixture.data).put("amount", amount);
				assertThat(fixture.provider.query(query("outer-payout")).status()).as(amount.toString()).isEqualTo("UNKNOWN");
			}
			fixture.data = payout("UNFAMILIAR");
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			fixture.data = payout("SUCCEEDED");
			fixture.duplicateAmount = true;
			assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
		}
	}

	@Test
	void definiteNoTransferSubmitRejectionsMapSafeCodesEvenOnHttp200() throws Exception {
		try (var fixture = new Fixture()) {
			var expected = Map.of("607", "PAYOUT_PROVIDER_REJECTED", "623", "PAYOUT_PROVIDER_REJECTED",
					"636", "PAYOUT_PROVIDER_REJECTED", "624", "PAYOUT_INSUFFICIENT_BALANCE",
					"217", "PAYOUT_SERVICE_CREDITS_INSUFFICIENT");
			for (var entry : expected.entrySet()) {
				fixture.code = entry.getKey();
				var result = fixture.provider.submit(command());
				assertThat(result.status()).isEqualTo("FAILED");
				assertThat(result.failureCode()).isEqualTo(entry.getValue());
			}
			fixture.httpStatus = 400;
			fixture.code = "624";
			assertThat(fixture.provider.submit(command()).failureCode()).isEqualTo("PAYOUT_INSUFFICIENT_BALANCE");
			assertThat(fixture.paths).hasSize(6);
		}
	}

	@Test
	void transportServerDuplicateAndInterruptionErrorsNeverReleaseOrAutomaticallyResubmit() throws Exception {
		try (var fixture = new Fixture()) {
			for (var code : List.of("604", "605", "606", "632", "633", "634", "duplicate-key", "unfamiliar")) {
				fixture.code = code;
				assertThat(fixture.provider.submit(command()).status()).as(code).isEqualTo("UNKNOWN");
			}
			fixture.httpStatus = 503;
			fixture.code = "624";
			assertThat(fixture.provider.submit(command()).status()).isEqualTo("UNKNOWN");
			fixture.disconnect = true;
			assertThat(fixture.provider.submit(command()).status()).isEqualTo("UNKNOWN");
			assertThat(fixture.paths).hasSize(10);
		}
	}

	@Test
	void failedCancelledOrReversedChildRequiresExactNoPaymentEvidence() throws Exception {
		try (var fixture = new Fixture()) {
			for (var state : List.of("FAILED", "CANCELLED", "REVERSED")) {
				fixture.data = payout(state);
				assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
				transaction(fixture.data).put("errorCode", "624");
				var rejected = fixture.provider.query(query("outer-payout"));
				assertThat(rejected.status()).isEqualTo("FAILED");
				assertThat(rejected.failureCode()).isEqualTo("PAYOUT_INSUFFICIENT_BALANCE");
				transaction(fixture.data).put("reference", "transfer-evidence");
				assertThat(fixture.provider.query(query("outer-payout")).status()).isEqualTo("UNKNOWN");
			}
		}
	}

	@Test
	void recoveryRequiresUniqueMatchingOuterAndCannotCreateAnAttempt() throws Exception {
		try (var fixture = new Fixture()) {
			fixture.tamper = true;
			assertThat(fixture.provider.query(query(null)).status()).isEqualTo("UNKNOWN");
			fixture.tamper = false;
			fixture.listItems = List.of();
			assertThat(fixture.provider.query(query(null)).status()).isEqualTo("UNKNOWN");
			fixture.listItems = List.of(payout("SUCCEEDED"), payout("SUCCEEDED"));
			assertThat(fixture.provider.query(query(null)).status()).isEqualTo("UNKNOWN");
			var mismatch = payout("SUCCEEDED");
			mismatch.put("referenceId", "other-reference");
			fixture.listItems = List.of(mismatch);
			assertThat(fixture.provider.query(query(null)).status()).isEqualTo("UNKNOWN");
			fixture.listItems = null;
			fixture.hasMore = true;
			assertThat(fixture.provider.query(query(null)).status()).isEqualTo("UNKNOWN");
			assertThat(fixture.paths).hasSize(5).allMatch(path -> path.startsWith("/v1/payouts?"));
		}
	}

	@Test
	void disabledReadinessOrInvalidPersistedKeyCannotMakeAnOutboundCreate() throws Exception {
		try (var fixture = new Fixture()) {
			var command = command();
			var invalidKey = new PayoutProvider.Command(command.payoutId(), command.attemptId(), command.payoutId() + ":1",
					command.amountVnd(), command.currency(), command.destinationType(), command.destinationCiphertext());
			assertThat(fixture.provider.submit(invalidKey).status()).isEqualTo("FAILED");
			properties.setMode("DISABLED");
			assertThat(fixture.provider.submit(command).failureCode()).isEqualTo("REAL_PAYOUT_DISABLED");
			assertThat(fixture.paths).isEmpty();
		}
	}

	private PayoutProvider.Command command() throws Exception {
		var encrypted = cipher.encrypt(json.writeValueAsString(Map.of("bankCode", "970436", "accountReference", "1234567890",
				"accountHolderName", "SYNTHETIC HOLDER")));
		return new PayoutProvider.Command(payoutId, attemptId, requestId, 210_000, "VND", "BANK_ACCOUNT", encrypted);
	}

	private PayoutProvider.Query query(String reference) throws Exception {
		var command = command();
		return new PayoutProvider.Query("PAYOS", payoutId, attemptId, requestId, command.amountVnd(), command.currency(),
				command.destinationType(), command.destinationCiphertext(), reference);
	}

	private Map<String, Object> payout(String state) {
		var child = new LinkedHashMap<String, Object>();
		child.put("id", "child-transaction"); child.put("referenceId", attemptId.toString()); child.put("amount", 210_000L);
		child.put("description", "MentalBridge payout"); child.put("toBin", "970436");
		child.put("toAccountNumber", "1234567890"); child.put("toAccountName", "SYNTHETIC HOLDER");
		child.put("reference", null); child.put("transactionDatetime", null); child.put("errorMessage", null);
		child.put("errorCode", null); child.put("state", state);
		var data = new LinkedHashMap<String, Object>();
		data.put("id", "outer-payout"); data.put("referenceId", requestId); data.put("transactions", List.of(child));
		data.put("category", List.of()); data.put("approvalState", "COMPLETED"); data.put("createdAt", "2026-11-04T00:00:00Z");
		return data;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> transaction(Map<String, Object> data) {
		return (Map<String, Object>) ((List<?>) data.get("transactions")).getFirst();
	}

	private String sign(Map<String, Object> data) {
		try {
			var pieces = new ArrayList<String>();
			for (var entry : new TreeMap<>(data).entrySet()) {
				var value = entry.getValue();
				var canonical = value == null ? "" : value instanceof List<?> || value instanceof Map<?, ?>
						? json.writeValueAsString(sort(value)) : value.toString();
				pieces.add(encode(entry.getKey()) + "=" + encode(canonical));
			}
			var mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(properties.getPayosChecksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return java.util.HexFormat.of().formatHex(mac.doFinal(String.join("&", pieces).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) { throw new AssertionError(exception); }
	}

	private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

	private Object sort(Object value) {
		if (value instanceof Map<?, ?> map) {
			var sorted = new TreeMap<String, Object>();
			map.forEach((key, item) -> sorted.put(key.toString(), sort(item)));
			return sorted;
		}
		if (value instanceof List<?> list) return list.stream().map(this::sort).toList();
		return value;
	}

	private final class Fixture implements AutoCloseable {
		final HttpServer server;
		final PayosPayoutProvider provider;
		final List<String> paths = new ArrayList<>();
		final List<String> keys = new ArrayList<>();
		final List<String> signatures = new ArrayList<>();
		final List<String> bodies = new ArrayList<>();
		volatile Map<String, Object> data = payout("SUCCEEDED");
		volatile List<Map<String, Object>> listItems;
		volatile String code = "00";
		volatile int httpStatus = 200;
		volatile boolean tamper;
		volatile boolean omitSignature;
		volatile boolean hasMore;
		volatile boolean duplicateAmount;
		volatile boolean disconnect;

		Fixture() throws Exception {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				paths.add(exchange.getRequestURI().toString());
				keys.add(exchange.getRequestHeaders().getFirst("x-idempotency-key"));
				signatures.add(exchange.getRequestHeaders().getFirst("x-signature"));
				bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				if (disconnect) { exchange.close(); return; }
				var responseData = data;
				if (exchange.getRequestURI().getPath().equals("/v1/payouts")) {
					var items = listItems == null ? List.of(data) : listItems;
					responseData = Map.of("payouts", items, "pagination", Map.of("limit", 2, "offset", 0,
							"total", items.size(), "count", items.size(), "hasMore", hasMore));
				}
				if (!omitSignature) exchange.getResponseHeaders().add("x-signature", tamper ? "invalid" : sign(responseData));
				var body = json.writeValueAsString(Map.of("code", code, "desc", "synthetic provider text must never escape", "data", responseData));
				if (duplicateAmount) body = body.replace("\"amount\":210000", "\"amount\":210000,\"amount\":210000");
				var bytes = body.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(httpStatus, bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			server.start();
			var options = ClientOptions.builder().clientId(properties.getPayosClientId()).apiKey(properties.getPayosApiKey())
					.checksumKey(properties.getPayosChecksumKey()).baseURL("http://127.0.0.1:" + server.getAddress().getPort())
					.maxRetries(0).timeoutMs(1000).logLevel(ClientOptions.LogLevel.NONE)
					.httpClient(PayosPayoutProvider.sdkOptions(properties).getHttpClient()).build();
			provider = new PayosPayoutProvider(properties, cipher, json, PayosPayoutProvider.sdkClient(options));
		}

		@Override
		public void close() { server.stop(0); }
	}
}
