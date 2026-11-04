package com.mentalbridge.consultation.earnings;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import okhttp3.OkHttpClient;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.exception.APIException;
import vn.payos.model.v1.payouts.GetPayoutListParams;
import vn.payos.model.v1.payouts.Payout;
import vn.payos.model.v1.payouts.batch.PayoutBatchItem;
import vn.payos.model.v1.payouts.batch.PayoutBatchRequest;

@Component
class PayosPayoutProvider {

	private static final Set<String> DEFINITE_REJECTION_CODES = Set.of("607", "623", "636", "624", "217");
	private static final Set<String> REJECTED_APPROVALS = Set.of("REJECTED", "CANCELLED", "FAILED");
	private final PayoutProperties properties;
	private final PayoutDestinationCipher cipher;
	private final ObjectMapper json;
	private final PayOS client;

	@Autowired
	PayosPayoutProvider(PayoutProperties properties, PayoutDestinationCipher cipher, ObjectMapper json) {
		this(properties, cipher, json, sdkClient(sdkOptions(properties)));
	}

	PayosPayoutProvider(PayoutProperties properties, PayoutDestinationCipher cipher, ObjectMapper json, PayOS client) {
		this.properties = properties;
		this.cipher = cipher;
		this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.client = client;
	}

	static ClientOptions sdkOptions(PayoutProperties properties) {
		var http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
				.readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10))
				.callTimeout(Duration.ofSeconds(10)).retryOnConnectionFailure(false)
				.followRedirects(false).followSslRedirects(false).build();
		return ClientOptions.builder().clientId(properties.getPayosClientId()).apiKey(properties.getPayosApiKey())
				.checksumKey(properties.getPayosChecksumKey()).baseURL(properties.getPayosBaseUrl())
				.timeoutMs(10_000).maxRetries(0).logLevel(ClientOptions.LogLevel.NONE).httpClient(http).build();
	}

	static PayOS sdkClient(ClientOptions options) { return new StrictPayoutClient(options); }

	PayoutProvider.Result submit(PayoutProvider.Command command) {
		if (!properties.submitReady("PAYOS")) return result("FAILED", null, "REAL_PAYOUT_DISABLED", null);
		Destination destination;
		try { destination = destination(command); }
		catch (Exception exception) { return result("FAILED", null, "PAYOUT_DESTINATION_INVALID", null); }
		try {
			var item = PayoutBatchItem.builder().referenceId(command.attemptId().toString())
					.amount(command.amountVnd()).description("MentalBridge payout")
					.toBin(destination.bin()).toAccountNumber(destination.accountNumber()).build();
			var request = PayoutBatchRequest.builder().referenceId(command.requestId()).validateDestination(true)
					.payouts(List.of(item)).build();
			return matched(command, destination, client.payouts().batch().create(request, command.requestId()), null);
		}
		catch (APIException exception) {
			var status = exception.getStatusCode().orElse(0);
			var code = exception.getErrorCode().orElse("");
			if ((status == 200 || (status >= 400 && status < 500)) && DEFINITE_REJECTION_CODES.contains(code)) {
				return result("FAILED", null, rejectionCode(code), null);
			}
			return unknown(null);
		}
		catch (Exception exception) { return unknown(null); }
	}

	PayoutProvider.Result query(PayoutProvider.Query query) {
		if (!properties.payosVerificationReady()) {
			return result("UNKNOWN", query.providerReference(), "PAYOUT_PROVIDER_UNAVAILABLE", null);
		}
		var command = new PayoutProvider.Command(query.payoutId(), query.attemptId(), query.requestId(),
				query.amountVnd(), query.currency(), query.destinationType(), query.destinationCiphertext());
		var outerId = query.providerReference();
		try {
			var destination = destination(command);
			if (outerId == null) {
				var page = client.payouts().list(GetPayoutListParams.builder().referenceId(query.requestId())
						.limit(2).offset(0).build());
				if (page == null || page.getItems() == null || page.getItems().size() != 1 || page.hasNextPage()) {
					return unknown(null);
				}
				var discovered = page.getItems().getFirst();
				if (discovered == null || !query.requestId().equals(discovered.getReferenceId())
						|| !present(discovered.getId(), 128)) return unknown(null);
				outerId = discovered.getId();
			}
			if (!present(outerId, 128)) return unknown(query.providerReference());
			return matched(command, destination, client.payouts().get(outerId), outerId);
		}
		catch (Exception exception) { return unknown(outerId); }
	}

	private Destination destination(PayoutProvider.Command command) throws Exception {
		var key = UUID.fromString(command.requestId());
		if (key.version() != 4 || !key.toString().equals(command.requestId()) || command.attemptId() == null
				|| command.amountVnd() <= 0 || !"VND".equals(command.currency())
				|| !"BANK_ACCOUNT".equals(command.destinationType())) throw new IllegalArgumentException();
		var details = json.readTree(cipher.decrypt(command.destinationCiphertext()));
		var bin = details.path("bankCode").textValue();
		var account = details.path("accountReference").textValue();
		if (bin == null || !bin.matches("[0-9]{6}") || account == null || !account.matches("[0-9]{6,32}")) {
			throw new IllegalArgumentException();
		}
		return new Destination(bin, account);
	}

	private PayoutProvider.Result matched(PayoutProvider.Command command, Destination destination, Payout response,
			String expectedOuterId) {
		if (response == null || !present(response.getId(), 128) || !command.requestId().equals(response.getReferenceId())
				|| (expectedOuterId != null && !expectedOuterId.equals(response.getId()))
				|| response.getApprovalState() == null || response.getTransactions() == null
				|| response.getTransactions().size() != 1) return unknown(expectedOuterId);
		var child = response.getTransactions().getFirst();
		if (child == null || !present(child.getId(), 128) || !command.attemptId().toString().equals(child.getReferenceId())
				|| child.getAmount() == null || child.getAmount() != command.amountVnd()
				|| !destination.bin().equals(child.getToBin()) || !destination.accountNumber().equals(child.getToAccountNumber())
				|| child.getState() == null) return unknown(expectedOuterId);
		var outer = response.getId();
		var transaction = child.getId();
		var approvalRejected = REJECTED_APPROVALS.contains(response.getApprovalState().name());
		return switch (child.getState()) {
			case SUCCEEDED -> approvalRejected || (!blank(child.getErrorCode()) && !"00".equals(child.getErrorCode()))
					? unknown(outer) : result("SUCCEEDED", outer, null, transaction);
			case RECEIVED, PROCESSING, ON_HOLD -> approvalRejected ? unknown(outer)
					: result("PROCESSING", outer, null, transaction);
			case FAILED, CANCELLED, REVERSED -> child.getErrorCode() != null && DEFINITE_REJECTION_CODES.contains(child.getErrorCode())
					&& blank(child.getReference()) && blank(child.getTransactionDatetime())
					? result("FAILED", outer, rejectionCode(child.getErrorCode()), transaction) : unknown(outer);
		};
	}

	private String rejectionCode(String providerCode) {
		return switch (providerCode) {
			case "624" -> "PAYOUT_INSUFFICIENT_BALANCE";
			case "217" -> "PAYOUT_SERVICE_CREDITS_INSUFFICIENT";
			default -> "PAYOUT_PROVIDER_REJECTED";
		};
	}

	private PayoutProvider.Result unknown(String outerId) {
		return result("UNKNOWN", outerId, "PAYOUT_SUBMISSION_UNKNOWN", null);
	}

	private PayoutProvider.Result result(String status, String outer, String code, String child) {
		return new PayoutProvider.Result(status, outer, code, child);
	}

	private boolean present(String value, int length) { return value != null && !value.isBlank() && value.length() <= length; }
	private boolean blank(String value) { return value == null || value.isBlank(); }

	private record Destination(String bin, String accountNumber) { }

	private static final class StrictPayoutClient extends PayOS {
		StrictPayoutClient(ClientOptions options) {
			super(options);
			mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
			mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
			mapper.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail);
		}
	}
}
