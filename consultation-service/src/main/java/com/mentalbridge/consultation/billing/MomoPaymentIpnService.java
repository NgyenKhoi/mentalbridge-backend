package com.mentalbridge.consultation.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class MomoPaymentIpnService {

	static final String CONTRACT_VERSION = "momo-one-time-v2";
	private static final Set<String> REQUIRED = Set.of("partnerCode", "orderId", "requestId", "amount",
			"orderInfo", "orderType", "transId", "resultCode", "message", "payType", "responseTime",
			"extraData", "signature");
	private final BillingProperties properties;
	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final Clock clock;
	private final PaymentSettlementService settlement;

	public MomoPaymentIpnService(BillingProperties properties, JdbcClient jdbc, ObjectMapper json, Clock clock,
			PaymentSettlementService settlement) {
		this.properties = properties;
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
		this.settlement = settlement;
	}

	@Transactional
	public void receive(Map<String, Object> input) {
		var payload = json.valueToTree(input);
		var ipn = parse(payload);
		if (!configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"MOMO_PAYMENT_CONFIGURATION_UNAVAILABLE", "MoMo payment callback is not configured");
		var expected = signature(ipn);
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				ipn.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "MOMO_PAYMENT_SIGNATURE_INVALID",
					"MoMo payment callback signature is invalid");
		}
		var dedup = sha256(CONTRACT_VERSION + "|" + ipn.partnerCode() + "|" + ipn.orderId() + "|"
				+ ipn.requestId() + "|" + ipn.transId() + "|" + ipn.resultCode() + "|" + ipn.responseTime());
        var now = clock.instant();
        var receiptId = UUID.randomUUID();
        if (!insertReceipt(receiptId, null, dedup, ipn, payload, now, "RECEIVED", null)) return;

		if (ipn.transId() > 0) jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "momo-reference:" + ipn.transId()).query(String.class).single();
        var payment = settlement.lockMomo(ipn.orderId());
        now = clock.instant();
		var transactionUsable = ipn.resultCode() != 0 || (ipn.transId() > 0 && jdbc.sql("""
				select count(*) from payment_transaction where momo_trans_id=:transId and id<>:paymentId
				""").param("transId", ipn.transId()).param("paymentId", payment == null ? UUID.randomUUID() : payment.id())
				.query(Long.class).single() == 0);
		var matched = payment != null && properties.getMomoPartnerCode().equals(ipn.partnerCode())
				&& payment.requestId().equals(ipn.requestId()) && payment.amount() == ipn.amount() && transactionUsable;
        if (!matched) {
            finishReceipt(receiptId, "VERIFIED_UNMATCHED", mismatch(payment, ipn), now);
            return;
        }
        jdbc.sql("update momo_payment_ipn set payment_id=:paymentId where id=:id")
                .param("paymentId", payment.id()).param("id", receiptId).update();
		if (!"PENDING".equals(payment.status())) {
			finishReceipt(receiptId, "PROCESSED", "PAYMENT_ALREADY_FINAL", now);
			return;
		}
		if (payment.responseTime() != null && ipn.responseTime() <= payment.responseTime()) {
			finishReceipt(receiptId, "PROCESSED", "OUT_OF_ORDER_IGNORED", now);
			return;
		}
		if (ipn.resultCode() == 9000) {
			updateProviderFacts(payment.id(), ipn, now);
			finishReceipt(receiptId, "PROCESSED", "PAYMENT_AUTHORIZED_PENDING", now);
			return;
		}
		if (ipn.resultCode() == 0 && payment.expiresAt().isAfter(now)) {
			succeed(payment, ipn, now);
			finishReceipt(receiptId, "PROCESSED", null, now);
			return;
		}
		fail(payment, ipn, now, payment.expiresAt().isAfter(now) ? "MOMO_PAYMENT_FAILED" : "PAYMENT_EXPIRED");
		finishReceipt(receiptId, "PROCESSED", payment.expiresAt().isAfter(now) ? "MOMO_PAYMENT_FAILED" : "PAYMENT_EXPIRED", now);
	}

    private void succeed(PaymentSettlementService.Payment payment, Ipn ipn, Instant now) {
        updateProviderFacts(payment.id(), ipn, now);
        jdbc.sql("update payment_transaction set momo_trans_id=:transId where id=:id")
                .param("transId", ipn.transId()).param("id", payment.id()).update();
        settlement.succeed(payment, now);
    }

    private void fail(PaymentSettlementService.Payment payment, Ipn ipn, Instant now, String reason) {
        updateProviderFacts(payment.id(), ipn, now);
        settlement.fail(payment, now, "PAYMENT_EXPIRED".equals(reason) ? "EXPIRED" : "PROVIDER_FAILED");
    }

	private void updateProviderFacts(UUID paymentId, Ipn ipn, Instant now) {
		jdbc.sql("""
				update payment_transaction set momo_result_code=:resultCode, momo_pay_type=:payType,
				 momo_response_time_epoch_ms=:responseTime, provider_occurred_at=:occurredAt,
				 updated_at=:now, version=version+1 where id=:id
				""").param("resultCode", ipn.resultCode()).param("payType", ipn.payType())
				.param("responseTime", ipn.responseTime()).param("occurredAt", databaseInstant(ipn.providerTime()))
				.param("now", databaseInstant(now)).param("id", paymentId).update();
	}

	private boolean insertReceipt(UUID id, UUID paymentId, String dedup, Ipn ipn, JsonNode payload, Instant now,
			String status, String failure) {
		var optional = json.createObjectNode();
		copyOptional(payload, optional, "paymentOption");
		copyOptional(payload, optional, "userFee");
		return jdbc.sql("""
				insert into momo_payment_ipn (id, payment_id, contract_version, deduplication_key, partner_code,
				 order_id, request_id, amount_minor, currency, order_info_sha256, order_type, trans_id,
				 result_code, result_message, pay_type, response_time_epoch_ms, extra_data_sha256,
				 provider_occurred_at, safe_optional_details, payload_sha256, signature_key_version,
				 signature_verified_at, processing_status, failure_code, received_at, processed_at, acknowledged_at)
				values (:id, :paymentId, :contract, :dedup, :partner, :orderId, :requestId, :amount, 'VND',
				 :orderInfoHash, :orderType, :transId, :resultCode, :message, :payType, :responseTime,
				 :extraHash, :occurredAt, cast(:optional as jsonb), :payloadHash, :keyVersion, :now,
					 :status, :failure, :now, :processedAt, :acknowledgedAt)
				on conflict (deduplication_key) do nothing
				""").param("id", id).param("paymentId", paymentId).param("contract", CONTRACT_VERSION)
				.param("dedup", dedup).param("partner", ipn.partnerCode()).param("orderId", ipn.orderId())
				.param("requestId", ipn.requestId()).param("amount", ipn.amount())
				.param("orderInfoHash", sha256(ipn.orderInfo())).param("orderType", ipn.orderType())
				.param("transId", ipn.transId()).param("resultCode", ipn.resultCode()).param("message", ipn.message())
				.param("payType", ipn.payType()).param("responseTime", ipn.responseTime())
				.param("extraHash", sha256(ipn.extraData())).param("occurredAt", databaseInstant(ipn.providerTime()))
				.param("optional", optional.toString()).param("payloadHash", sha256(bytes(payload)))
				.param("keyVersion", properties.getMomoSignatureKeyVersion()).param("now", databaseInstant(now))
				.param("status", status).param("failure", failure)
				.param("processedAt", "RECEIVED".equals(status) ? null : databaseInstant(now))
				.param("acknowledgedAt", databaseInstant(now)).update() > 0;
	}

	private void finishReceipt(UUID id, String status, String failure, Instant now) {
		jdbc.sql("""
				update momo_payment_ipn set processing_status=:status, failure_code=:failure,
				 processed_at=:now, acknowledged_at=:now where id=:id
				""").param("status", status).param("failure", failure).param("now", databaseInstant(now))
				.param("id", id).update();
	}

	private Ipn parse(JsonNode payload) {
		if (!(payload instanceof ObjectNode object) || !REQUIRED.stream().allMatch(object::has)) {
			throw malformed();
		}
		var partner = text(object, "partnerCode", 64, false);
		var orderId = text(object, "orderId", 128, false);
		var requestId = text(object, "requestId", 128, false);
		var amount = integer(object, "amount", 1, 1_000_000_000L);
		var orderInfo = text(object, "orderInfo", 1024, true);
		var orderType = text(object, "orderType", 64, false);
		var transId = integer(object, "transId", Long.MIN_VALUE, Long.MAX_VALUE);
		var resultCode = Math.toIntExact(integer(object, "resultCode", Integer.MIN_VALUE, Integer.MAX_VALUE));
		var message = text(object, "message", 256, true);
		var payType = text(object, "payType", 32, false);
		var responseTime = integer(object, "responseTime", 1, Long.MAX_VALUE);
		var extraData = text(object, "extraData", 8192, true);
		var signature = text(object, "signature", 128, false);
		if (!signature.matches("[0-9a-fA-F]{64}")) throw malformed();
		try {
			return new Ipn(partner, orderId, requestId, amount, orderInfo, orderType, transId, resultCode,
					message, payType, responseTime, extraData, signature, Instant.ofEpochMilli(responseTime));
		}
		catch (RuntimeException exception) {
			throw malformed();
		}
	}

	private String signature(Ipn value) {
		var raw = "accessKey=" + properties.getMomoAccessKey() + "&amount=" + value.amount()
				+ "&extraData=" + value.extraData() + "&message=" + value.message()
				+ "&orderId=" + value.orderId() + "&orderInfo=" + value.orderInfo()
				+ "&orderType=" + value.orderType() + "&partnerCode=" + value.partnerCode()
				+ "&payType=" + value.payType() + "&requestId=" + value.requestId()
				+ "&responseTime=" + value.responseTime() + "&resultCode=" + value.resultCode()
				+ "&transId=" + value.transId();
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.getMomoSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to verify MoMo payment callback", exception);
		}
	}

	private String mismatch(PaymentSettlementService.Payment payment, Ipn ipn) {
		if (!properties.getMomoPartnerCode().equals(ipn.partnerCode())) return "PARTNER_MISMATCH";
		if (payment == null) return "ORDER_NOT_FOUND";
		if (!payment.requestId().equals(ipn.requestId())) return "REQUEST_MISMATCH";
		if (payment.amount() != ipn.amount()) return "AMOUNT_MISMATCH";
		return ipn.transId() <= 0 ? "TRANS_ID_INVALID" : "TRANS_ID_CONFLICT";
	}


	private void copyOptional(JsonNode source, ObjectNode target, String field) {
		var value = source.get(field);
		if (value != null && (value.isTextual() || value.isIntegralNumber())) target.set(field, value);
	}

	private String text(ObjectNode object, String field, int max, boolean allowEmpty) {
		var value = object.get(field);
		if (value == null || !value.isTextual() || value.textValue().length() > max
				|| (!allowEmpty && value.textValue().isBlank())) throw malformed();
		return value.textValue();
	}

	private long integer(ObjectNode object, String field, long min, long max) {
		var value = object.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw malformed();
		var result = value.longValue();
		if (result < min || result > max) throw malformed();
		return result;
	}

	private boolean configured() {
		return properties.getMomoPartnerCode() != null && !properties.getMomoPartnerCode().isBlank()
				&& properties.getMomoAccessKey() != null && !properties.getMomoAccessKey().isBlank()
				&& properties.getMomoSecretKey() != null && !properties.getMomoSecretKey().isBlank();
	}

	private byte[] bytes(JsonNode value) {
		try { return json.writeValueAsBytes(value); }
		catch (Exception exception) { throw new IllegalStateException("Unable to hash MoMo callback", exception); }
	}

	private String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
	private String sha256(byte[] value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
		catch (Exception exception) { throw new IllegalStateException("Unable to hash MoMo callback", exception); }
	}
	private OffsetDateTime databaseInstant(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
	private ApiException malformed() { return new ApiException(HttpStatus.BAD_REQUEST, "MOMO_PAYMENT_PAYLOAD_INVALID", "MoMo payment callback is invalid"); }

	private record Ipn(String partnerCode, String orderId, String requestId, long amount, String orderInfo,
			String orderType, long transId, int resultCode, String message, String payType, long responseTime,
			String extraData, String signature, Instant providerTime) { }
}
