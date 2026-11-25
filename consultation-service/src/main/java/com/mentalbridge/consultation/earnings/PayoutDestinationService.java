package com.mentalbridge.consultation.earnings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.ConsultationAdminAuditEvent;
import com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox;

@Service
public class PayoutDestinationService {

	private final JdbcClient jdbc;
	private final PayoutProperties properties;
	private final PayoutDestinationCipher cipher;
	private final PayoutBankDirectory banks;
	private final ObjectMapper json;
	private final Clock clock;
	private final TransactionTemplate transaction;
	private final ConsultationAdminAuditOutbox audit;

	public PayoutDestinationService(JdbcClient jdbc, PayoutProperties properties, PayoutDestinationCipher cipher,
			PayoutBankDirectory banks, ObjectMapper json, Clock clock, PlatformTransactionManager transactionManager,
			ConsultationAdminAuditOutbox audit) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.cipher = cipher;
		this.banks = banks;
		this.json = json;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
		this.audit = audit;
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public SpecialistEarningsResponse.Destination save(UUID specialistId, SavePayoutDestinationRequest request) {
		assertApproved(specialistId, false);
		assertReady();
		var reference = request.accountReference() == null ? "" : request.accountReference().trim();
		var holder = request.accountHolderName() == null ? "" : request.accountHolderName().trim();
		var bankCode = request.bankCode() == null ? "" : request.bankCode().trim();
		var type = request.destinationType();
		if (!reference.matches("[0-9]{6,32}") || holder.isBlank() || holder.length() > 100
				|| !Set.of("MOMO_WALLET", "BANK_ACCOUNT").contains(type == null ? "" : type)
				|| ("BANK_ACCOUNT".equals(type) && bankCode.isBlank())) throw invalid();
		String bankName = null;
		if ("PAYOS".equals(properties.getMode())) {
			if (!"BANK_ACCOUNT".equals(type) || !bankCode.matches("[0-9]{6}")) throw invalid();
			bankName = banks.banks().items().stream().filter(bank -> bank.bin().equals(bankCode))
					.findFirst().orElseThrow(PayoutDestinationService::invalid).name();
		}
		var details = new Details(reference, holder, bankCode, bankName);
		var encoded = encode(details);
		var encrypted = cipher.encrypt(encoded);
		var provider = properties.getMode();
		var fingerprint = cipher.fingerprint(provider + ":" + type + ":" + bankCode + ":" + reference + ":" + holder);
		return transaction.execute(status -> {
			assertApproved(specialistId, true);
			assertReady();
			var existing = jdbc.sql("""
					select * from specialist_payout_destination
					where specialist_account_id=:owner and is_current for update
					""").param("owner", specialistId).query(this::row).optional();
			if (existing.isPresent() && existing.orElseThrow().fingerprint().equals(fingerprint)) {
				return view(existing.orElseThrow());
			}
			var now = database(clock.instant());
			jdbc.sql("""
					update specialist_payout_destination set is_current=false, status='DISABLED',
					 updated_at=:now, version=version+1 where specialist_account_id=:owner and is_current
					""").param("now", now).param("owner", specialistId).update();
			var id = UUID.randomUUID();
			jdbc.sql("""
					insert into specialist_payout_destination (id,specialist_account_id,payout_provider,destination_type,
					 destination_ciphertext,encryption_key_version,destination_fingerprint,display_hint,status,
					 verified_at,created_at,updated_at,is_current)
					values (:id,:owner,:provider,:type,:ciphertext,:keyVersion,:fingerprint,:hint,:status,
					 :verifiedAt,:now,:now,true)
					""").param("id", id).param("owner", specialistId).param("provider", provider).param("type", type)
					.param("ciphertext", encrypted).param("keyVersion", properties.getEncryptionKeyVersion())
					.param("fingerprint", fingerprint).param("hint", "\u2022\u2022\u2022\u2022 " + reference.substring(reference.length() - 4))
					.param("status", "PAYOS".equals(provider) ? "PENDING_CONFIRMATION" : "VERIFIED")
					.param("verifiedAt", "PAYOS".equals(provider) ? null : now).param("now", now).update();
			return destination(specialistId, id);
		});
	}

	public SpecialistEarningsResponse.Destination current(UUID specialistId) {
		return jdbc.sql("select * from specialist_payout_destination where specialist_account_id=:owner and is_current")
				.param("owner", specialistId).query(this::row).optional().map(this::view).orElse(null);
	}

	public SpecialistEarningsResponse.Destination destination(UUID specialistId, UUID id) {
		return jdbc.sql("select * from specialist_payout_destination where id=:id and specialist_account_id=:owner")
				.param("id", id).param("owner", specialistId).query(this::row).optional().map(this::view)
				.orElseThrow(PayoutDestinationService::notFound);
	}

	@Transactional(readOnly = true)
	public AdminList list(String status, int limit, int offset) {
		if (!Set.of("ALL", "PENDING_CONFIRMATION", "VERIFIED", "DISABLED").contains(status)
				|| limit < 1 || limit > 200 || offset < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "PAYOUT_DESTINATION_INVALID",
					"Payout destination filter is invalid");
		}
		var items = jdbc.sql("""
				select * from specialist_payout_destination where is_current and (:status='ALL' or status=:status)
				order by updated_at desc, id desc limit :limit offset :offset
				""").param("status", status).param("limit", limit).param("offset", offset)
				.query(this::row).list().stream().map(row -> new AdminItem(view(row), row.owner())).toList();
		return new AdminList(clock.instant(), items.size(), items);
	}

	@Transactional
	public AdminDetail detail(UUID adminId, UUID id) {
		var destination = find(id);
		var details = details(destination);
		audit(adminId, destination, "consultation.payout-destination.inspected", "PAYOUT_DESTINATION_INSPECTED",
				"MANUAL_ACCOUNT_HOLDER_CHECK", "payout-destination-inspect:" + UUID.randomUUID());
		return new AdminDetail(view(destination), destination.owner(), details.accountReference(),
				details.accountHolderName(), destination.confirmedBy());
	}

	@Transactional
	public SpecialistEarningsResponse.Destination confirm(UUID adminId, UUID id, String key,
			ConfirmPayoutDestinationRequest request) {
		if (key == null || key.length() < 16 || key.length() > 128 || key.isBlank()
				|| request.expectedVersion() == null || request.expectedVersion() < 0
				|| !"ACCOUNT_HOLDER_CHECKED".equals(request.reasonCode())) throw invalid();
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:scope,0))")
				.param("scope", "payout-destination-confirm:" + adminId + ":" + key).query().singleRow();
		var replay = jdbc.sql("""
				select destination_id, expected_version, reason_code from payout_destination_confirmation_command
				where actor_account_id=:actor and idempotency_key=:key
				""").param("actor", adminId).param("key", key).query().listOfRows().stream().findFirst();
		if (replay.isPresent()) {
			var stored = replay.orElseThrow();
			if (!id.equals(stored.get("destination_id"))
					|| ((Number) stored.get("expected_version")).longValue() != request.expectedVersion()
					|| !request.reasonCode().equals(stored.get("reason_code"))) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
						"Idempotency key was used for a different command");
			}
			return view(find(id));
		}
		var initial = find(id);
		jdbc.sql("select account_id from specialist_profile where account_id=:owner for update")
				.param("owner", initial.owner()).query(UUID.class).single();
		var destination = jdbc.sql("select * from specialist_payout_destination where id=:id for update")
				.param("id", id).query(this::row).single();
		if (!destination.current() || !"PENDING_CONFIRMATION".equals(destination.status())
				|| destination.version() != request.expectedVersion() || !"PAYOS".equals(destination.provider())) {
			throw new ApiException(HttpStatus.CONFLICT, "PAYOUT_DESTINATION_VERSION_CONFLICT",
					"Payout destination changed or is no longer pending confirmation");
		}
		details(destination);
		var now = database(clock.instant());
		jdbc.sql("""
				update specialist_payout_destination set status='VERIFIED', verified_at=:now, confirmed_by=:actor,
				 updated_at=:now, version=version+1 where id=:id
				""").param("now", now).param("actor", adminId).param("id", id).update();
		jdbc.sql("""
				insert into payout_destination_confirmation_command
				 (id,actor_account_id,idempotency_key,destination_id,expected_version,reason_code,result_version,created_at)
				values (:id,:actor,:key,:destination,:version,:reason,:resultVersion,:now)
				""").param("id", UUID.randomUUID()).param("actor", adminId).param("key", key).param("destination", id)
				.param("version", request.expectedVersion()).param("reason", request.reasonCode())
				.param("resultVersion", destination.version() + 1).param("now", now).update();
		audit(adminId, destination, "consultation.payout-destination.confirmed", "PAYOUT_DESTINATION_CONFIRMED",
				request.reasonCode(), "payout-destination-confirm:" + adminId + ":" + key);
		return view(find(id));
	}

	private void audit(UUID adminId, Stored destination, String eventType, String action, String reason, String key) {
		audit.record(key, new ConsultationAdminAuditEvent(UUID.randomUUID(), eventType, clock.instant(),
				"consultation-service", "1.0", "CONSULTATION", "SPECIALIST_REVIEW", adminId, "ADMIN", action,
				"SUCCEEDED", reason, destination.id(), destination.owner(), "account:" + destination.owner()));
	}

	private Stored find(UUID id) {
		return jdbc.sql("select * from specialist_payout_destination where id=:id").param("id", id)
				.query(this::row).optional().orElseThrow(PayoutDestinationService::notFound);
	}

	private SpecialistEarningsResponse.Destination view(Stored row) {
		var details = details(row);
		return new SpecialistEarningsResponse.Destination(row.id(), row.provider(), row.type(), row.hint(), row.status(),
				row.verifiedAt(), details.bankCode().isBlank() ? null : details.bankCode(), details.bankName(),
				row.version(), row.updatedAt(), row.current() && "VERIFIED".equals(row.status())
						&& row.provider().equals(properties.getMode()) && properties.ready());
	}

	private Details details(Stored row) {
		try {
			if (!properties.getEncryptionKeyVersion().equals(row.keyVersion())) throw configurationUnavailable();
			return json.readValue(cipher.decrypt(row.ciphertext()), Details.class);
		}
		catch (Exception exception) {
			throw configurationUnavailable();
		}
	}

	private String encode(Details details) {
		try {
			return json.writeValueAsString(details);
		}
		catch (Exception exception) {
			throw configurationUnavailable();
		}
	}

	private void assertApproved(UUID id, boolean lock) {
		var approved = jdbc.sql("select approval_status from specialist_profile where account_id=:id"
				+ (lock ? " for update" : "")).param("id", id).query(String.class).optional();
		if (approved.isEmpty() || !"APPROVED".equals(approved.orElseThrow())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SPECIALIST_EARNINGS_ACCESS_DENIED",
					"Specialist earnings are unavailable");
		}
	}

	private void assertReady() {
		if (!properties.ready()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"DISABLED".equals(properties.getMode()) ? "REAL_PAYOUT_DISABLED" : "PAYOUT_PROVIDER_UNAVAILABLE",
					"Payout destination setup is unavailable");
		}
	}

	private Stored row(ResultSet row, int ignored) throws SQLException {
		return new Stored(row.getObject("id", UUID.class), row.getObject("specialist_account_id", UUID.class),
				row.getString("payout_provider"), row.getString("destination_type"), row.getString("destination_ciphertext"),
				row.getString("encryption_key_version"), row.getString("destination_fingerprint"), row.getString("display_hint"),
				row.getString("status"), instant(row, "verified_at"), row.getLong("version"), instant(row, "updated_at"),
				row.getBoolean("is_current"), row.getObject("confirmed_by", UUID.class));
	}

	private Instant instant(ResultSet row, String column) throws SQLException {
		var timestamp = row.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private OffsetDateTime database(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "PAYOUT_DESTINATION_INVALID", "Payout destination is invalid");
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "PAYOUT_DESTINATION_NOT_FOUND", "Payout destination was not found");
	}

	private static ApiException configurationUnavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOUT_CONFIGURATION_UNAVAILABLE",
				"Payout destination configuration is unavailable");
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Details(String accountReference, String accountHolderName, String bankCode, String bankName) {
		private Details {
			bankCode = bankCode == null ? "" : bankCode;
		}
	}

	private record Stored(UUID id, UUID owner, String provider, String type, String ciphertext, String keyVersion,
			String fingerprint, String hint, String status, Instant verifiedAt, long version, Instant updatedAt,
			boolean current, UUID confirmedBy) {}

	public record AdminList(Instant generatedAt, int count, List<AdminItem> items) {}
	public record AdminItem(SpecialistEarningsResponse.Destination destination, UUID specialistAccountId) {}
	public record AdminDetail(SpecialistEarningsResponse.Destination destination, UUID specialistAccountId,
			String accountReference, String accountHolderName, UUID confirmedBy) {}
}
