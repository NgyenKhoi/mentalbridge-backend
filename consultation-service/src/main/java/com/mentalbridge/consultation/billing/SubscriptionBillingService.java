package com.mentalbridge.consultation.billing;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SubscriptionBillingService {

	private static final int HISTORY_LIMIT = 50;
	private static final String SANDBOX_POLICY = "sandbox-full-price-v1";
	private final JdbcClient jdbc;
	private final Clock clock;
	private final BillingProperties properties;
	private final CollectPaymentGateway gateway;
	private final PayosPaymentWebhookService payos;
	private final PaymentSettlementService settlement;
	private final TransactionTemplate transactions;

	public SubscriptionBillingService(JdbcClient jdbc, Clock clock, BillingProperties properties,
			CollectPaymentGateway gateway, PayosPaymentWebhookService payos, PaymentSettlementService settlement,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.properties = properties;
		this.gateway = gateway;
		this.payos = payos;
		this.settlement = settlement;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Transactional(readOnly = true)
	public PlanCatalogueResponse catalogue() {
		var now = databaseNow();
		var plans = jdbc.sql("""
				select v.id, v.plan_code, v.version, p.display_name, v.currency, v.price_minor,
				       v.billing_period_months, v.consultation_credits_per_period,
				       v.max_active_reservations, v.credit_value_minor, v.ai_quota_code,
				       v.support_plan_enabled, v.effective_from, v.pricing_policy_version
				from subscription_plan_version v join subscription_plan p on p.code=v.plan_code
				where v.effective_from <= :now and (v.retired_at is null or v.retired_at > :now)
				order by p.sort_order
				""").param("now", now).query((row, ignored) -> new PlanCatalogueResponse.Plan(
				row.getObject("id", UUID.class), row.getString("plan_code"), row.getString("version"),
				row.getString("display_name"), row.getString("currency"), row.getString("pricing_policy_version"), row.getLong("price_minor"),
				row.getInt("billing_period_months"), row.getInt("consultation_credits_per_period"),
				row.getInt("max_active_reservations"), row.getLong("credit_value_minor"),
				row.getString("ai_quota_code"), row.getBoolean("support_plan_enabled"),
				entitlements(row.getObject("id", UUID.class)), upgrades(row.getString("plan_code")),
				row.getTimestamp("effective_from").toInstant())).list();
		var checkoutEnabled = properties.checkoutEnabled() && plans.stream().allMatch(plan ->
				!SANDBOX_POLICY.equals(plan.pricingPolicyVersion()) || properties.sandboxCheckoutAllowed());
		return new PlanCatalogueResponse(plans, checkoutEnabled, gateway.provider(), clock.instant());
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PaymentResponse checkout(UUID userId, UUID targetVersionId, String idempotencyKey) {
		validateKey(idempotencyKey);
		if (!properties.checkoutEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"PAYMENT_CHECKOUT_DISABLED", "Payment checkout is not enabled");
		var prepared = transactions.execute(ignored -> prepareCheckout(userId, targetVersionId, idempotencyKey));
		if (!prepared.created()) return prepared.payment();
		var payment = prepared.payment();
		var request = jdbc.sql("select momo_order_id, momo_request_id, order_code from payment_transaction where id=:id")
                .param("id", payment.paymentId()).query((row, ignored) -> new PreparedIdentity(row.getString(1),
                row.getString(2), row.getObject(3, Long.class))).single();
        var checkout = gateway.create(request.orderId(), request.requestId(), request.orderCode(), payment.amountVnd(), payment.expiresAt());
		return transactions.execute(ignored -> {
			jdbc.sql("""
					update payment_transaction set checkout_url=:url, qr_code_url=:qr,
                     payos_payment_link_id=coalesce(payos_payment_link_id,:link), qr_code=:payload,
                     bank_bin=:bin, bank_account_number=:account, bank_account_name=:name,
                     payment_description=:description, updated_at=:now, version=version+1
                    where id=:id and checkout_url is null and qr_code is null
                     and (payos_payment_link_id is null or payos_payment_link_id=:link)
					""").param("url", checkout.checkoutUrl()).param("qr", checkout.qrCodeUrl())
                    .param("link", checkout.paymentLinkId()).param("payload", checkout.qrCode())
                    .param("bin", checkout.bin()).param("account", checkout.accountNumber())
                    .param("name", checkout.accountName()).param("description", checkout.description())
					.param("now", databaseNow()).param("id", payment.paymentId()).update();
			return findPaymentById(payment.paymentId());
		});
	}

	private CheckoutPreparation prepareCheckout(UUID userId, UUID targetVersionId, String idempotencyKey) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))")
				.param("lockKey", "subscription-checkout:" + userId).query(String.class).single();
		var replay = findByUserAndKey(userId, idempotencyKey);
		if (replay != null) {
			if (!replay.planVersionId().equals(targetVersionId)) throw conflict("IDEMPOTENCY_KEY_REUSED",
					"Idempotency key was reused for another plan");
			return new CheckoutPreparation(replay, false);
		}
		var target = activePlan(targetVersionId);
		if (SANDBOX_POLICY.equals(target.pricingPolicyVersion()) && !properties.sandboxCheckoutAllowed()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_CHECKOUT_DISABLED",
					"Sandbox plans cannot use a production checkout provider");
		}
		if ("FREE".equals(target.planCode())) throw conflict("FREE_PLAN_CHECKOUT_NOT_REQUIRED",
				"The free plan does not require checkout");
		if (jdbc.sql("select count(*) from user_subscription where user_id=:userId and status='PENDING_PAYMENT'")
				.param("userId", userId).query(Long.class).single() > 0) {
			throw conflict("SUBSCRIPTION_PAYMENT_PENDING", "A purchase checkout is already pending");
		}
		var current = currentSubscription(userId);
		if (current == null) {
			var projected = jdbc.sql("""
					select package_code from current_service_entitlement
					where account_id=:userId and effective_from <= :now and effective_until > :now
					""").param("userId", userId).param("now", databaseNow()).query(String.class).optional();
			if (projected.isPresent()) throw conflict("SUBSCRIPTION_NOT_MANAGED",
					"The current paid or demo entitlement is not managed by this checkout flow");
			return new CheckoutPreparation(purchase(userId, target, idempotencyKey), true);
		}
		if ("PLUS".equals(current.planCode()) && "PREMIUM".equals(target.planCode())) {
			return new CheckoutPreparation(upgrade(current, target, idempotencyKey), true);
		}
		if ("PREMIUM".equals(current.planCode()) && "PLUS".equals(target.planCode())) {
			throw conflict("SUBSCRIPTION_DOWNGRADE_NOT_SUPPORTED", "Subscription downgrade is not supported");
		}
		throw conflict("SUBSCRIPTION_PLAN_CHANGE_NOT_ALLOWED", "The requested plan change is not allowed");
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PaymentResponse payment(UUID userId, UUID paymentId) {
        var payment = findPayment(userId, paymentId);
        if ("PAYOS".equals(payment.provider()) && ("PENDING".equals(payment.status())
                || ("FAILED".equals(payment.status()) && "EXPIRED".equals(payment.terminalReason())))) payos.reconcile(paymentId);
        return findPayment(userId, paymentId);
    }

	@Transactional(readOnly = true)
	public PaymentResponse.History history(UUID userId) {
		var items = jdbc.sql("""
				select pt.id from payment_transaction pt join user_subscription s on s.id=pt.subscription_id
				where s.user_id=:userId order by pt.created_at desc, pt.id desc limit :limit
				""").param("userId", userId).param("limit", HISTORY_LIMIT).query(UUID.class).list().stream()
				.map(id -> findPayment(userId, id)).toList();
		return new PaymentResponse.History(items, items.size(), clock.instant());
	}

    @Scheduled(fixedDelayString = "${mentalbridge.consultation.billing.expiry-interval:PT1M}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void expirePendingCheckouts() {
        var now = clock.instant();
        var paymentIds = jdbc.sql("""
                select id from payment_transaction
                where (status='PENDING' and (expires_at <= :now or (payment_provider='PAYOS'
                 and (reconciliation_next_at is null or reconciliation_next_at<=:now))))
                 or (payment_provider='PAYOS' and status='FAILED' and terminal_reason='EXPIRED'
                  and reconciliation_failure_code in ('PAYOS_CANCEL_PENDING','PAYOS_CANCEL_UNAVAILABLE')
                  and reconciliation_next_at<=:now)
                order by expires_at,id limit 100
                """).param("now", databaseInstant(now)).query(UUID.class).list();
        for (var paymentId : paymentIds) {
            var provider = jdbc.sql("select payment_provider from payment_transaction where id=:id")
                    .param("id", paymentId).query(String.class).single();
            if ("PAYOS".equals(provider)) payos.reconcile(paymentId);
            else transactions.executeWithoutResult(ignored -> {
                var payment = settlement.lock(paymentId);
                if (payment != null && !payment.expiresAt().isAfter(now)) settlement.fail(payment, now, "EXPIRED");
            });
        }
    }

	private PaymentResponse purchase(UUID userId, Plan target, String idempotencyKey) {
		var now = clock.instant();
		var subscriptionId = UUID.randomUUID();
		jdbc.sql("""
				insert into user_subscription (id, user_id, plan_version_id, status, idempotency_key,
				 created_at, updated_at) values (:id, :userId, :planVersionId, 'PENDING_PAYMENT', :key, :now, :now)
				""").param("id", subscriptionId).param("userId", userId).param("planVersionId", target.id())
				.param("key", idempotencyKey).param("now", databaseInstant(now)).update();
		jdbc.sql("""
				insert into subscription_status_history (id, subscription_id, from_status, to_status, reason_code, changed_at)
				values (:id, :subscriptionId, null, 'PENDING_PAYMENT', 'PURCHASE_CHECKOUT_CREATED', :now)
				""").param("id", UUID.randomUUID()).param("subscriptionId", subscriptionId)
				.param("now", databaseInstant(now)).update();
		return createPayment(subscriptionId, target, "PURCHASE", idempotencyKey, target.priceMinor(), now);
	}

	private PaymentResponse upgrade(CurrentSubscription current, Plan target, String idempotencyKey) {
		if (!current.pricingPolicyVersion().equals(target.pricingPolicyVersion())) {
			throw conflict("SUBSCRIPTION_PRICING_POLICY_MISMATCH", "Historical and sandbox pricing cannot be combined");
		}
		var now = clock.instant();
		if (!current.periodEnd().isAfter(now)) throw conflict("SUBSCRIPTION_PERIOD_EXPIRED",
				"The current subscription period has ended");
		var existing = jdbc.sql("""
				select pt.id from subscription_upgrade u join payment_transaction pt on pt.id=u.payment_id
				where u.subscription_id=:subscriptionId and u.status='PENDING_PAYMENT'
				""").param("subscriptionId", current.id()).query(UUID.class).optional();
		if (existing.isPresent()) throw conflict("SUBSCRIPTION_UPGRADE_PENDING", "An upgrade checkout is already pending");
		var totalSeconds = ChronoUnit.SECONDS.between(current.periodStart(), current.periodEnd());
		var remainingSeconds = ChronoUnit.SECONDS.between(now, current.periodEnd());
		var availableValue = jdbc.sql("""
				select coalesce(sum(p.credit_allocation_minor), 0) from service_credit c
				join service_credit_period p on p.id=c.period_id
				where p.account_id=:userId and p.source_reference=:source and c.state='AVAILABLE'
				""").param("userId", current.userId()).param("source", current.id().toString())
				.query(Long.class).single();
		var remainingFeature = Math.floorDiv(Math.multiplyExact(current.nonConsultationValueMinor(), remainingSeconds),
				totalSeconds);
		var offset = SANDBOX_POLICY.equals(target.pricingPolicyVersion()) ? 0L
				: Math.addExact(remainingFeature, availableValue);
		var due = target.priceMinor() - offset;
		if (due <= 0) throw conflict("SUBSCRIPTION_UPGRADE_AMOUNT_INVALID", "Upgrade amount must be positive");
		var payment = createPayment(current.id(), target, "UPGRADE", idempotencyKey, due, now);
		var upgradeId = UUID.randomUUID();
		jdbc.sql("""
				insert into subscription_upgrade (id, subscription_id, from_plan_version_id, to_plan_version_id,
				 payment_id, old_period_start, old_period_end, total_period_seconds, remaining_period_seconds,
				 remaining_feature_value_minor, available_credit_value_minor, offset_minor, amount_due_minor,
				 currency, status, idempotency_key, quoted_at, quote_expires_at, created_at, updated_at, pricing_policy_version)
				values (:id, :subscriptionId, :fromVersion, :toVersion, :paymentId, :periodStart, :periodEnd,
				 :totalSeconds, :remainingSeconds, :featureValue, :creditValue, :offset, :due, 'VND',
				 'PENDING_PAYMENT', :key, :now, :expiresAt, :now, :now, :pricingPolicy)
				""").param("id", upgradeId).param("subscriptionId", current.id())
				.param("fromVersion", current.planVersionId()).param("toVersion", target.id())
				.param("paymentId", payment.paymentId()).param("periodStart", databaseInstant(current.periodStart()))
				.param("periodEnd", databaseInstant(current.periodEnd())).param("totalSeconds", totalSeconds)
				.param("remainingSeconds", remainingSeconds).param("featureValue", remainingFeature)
				.param("creditValue", availableValue).param("offset", offset).param("due", due)
				.param("key", idempotencyKey).param("now", databaseInstant(now))
				.param("expiresAt", databaseInstant(payment.expiresAt()))
				.param("pricingPolicy", target.pricingPolicyVersion()).update();
		jdbc.sql("""
				insert into subscription_upgrade_credit (upgrade_id, credit_id, allocated_value_minor)
				select :upgradeId, c.id, p.credit_allocation_minor from service_credit c
				join service_credit_period p on p.id=c.period_id
				where p.account_id=:userId and p.source_reference=:source and c.state='AVAILABLE'
				""").param("upgradeId", upgradeId).param("userId", current.userId())
				.param("source", current.id().toString()).update();
		jdbc.sql("""
				update service_credit c set state='UPGRADE_HELD', updated_at=:now, version=version+1
				from subscription_upgrade_credit uc where uc.upgrade_id=:upgradeId and uc.credit_id=c.id
				""").param("now", databaseInstant(now)).param("upgradeId", upgradeId).update();
		return payment;
	}

	private PaymentResponse createPayment(UUID subscriptionId, Plan target, String purpose, String idempotencyKey,
			long amount, Instant now) {
		var paymentId = UUID.randomUUID();
		var orderId = "MB" + paymentId.toString().replace("-", "");
		var requestId = UUID.randomUUID().toString();
		var expiresAt = now.plus(properties.getCheckoutTtl()).truncatedTo(ChronoUnit.SECONDS);
        var provider = gateway.provider();
        var numericOrder = "MOMO".equals(provider) ? null
                : jdbc.sql("select nextval('payment_order_code_seq')").query(Long.class).single();
		jdbc.sql("""
				insert into payment_transaction (id, subscription_id, target_plan_version_id, payment_provider,
				 momo_order_id, momo_request_id, order_code, amount_minor, currency, status, purpose, idempotency_key,
				 expires_at, checkout_url, qr_code_url, created_at, updated_at)
				values (:id, :subscriptionId, :planVersionId, :provider, :orderId, :requestId, :orderCode, :amount,
				 'VND', 'PENDING', :purpose, :key, :expiresAt, :checkoutUrl, :qrCodeUrl, :now, :now)
				""").param("id", paymentId).param("subscriptionId", subscriptionId)
				.param("planVersionId", target.id()).param("provider", provider)
                .param("orderId", "PAYOS".equals(provider) ? null : orderId)
                .param("requestId", "PAYOS".equals(provider) ? null : requestId)
                .param("orderCode", numericOrder).param("amount", amount)
				.param("purpose", purpose).param("key", idempotencyKey)
				.param("expiresAt", databaseInstant(expiresAt)).param("checkoutUrl", null)
				.param("qrCodeUrl", null).param("now", databaseInstant(now)).update();
		return findPaymentById(paymentId);
	}


	private PaymentResponse findByUserAndKey(UUID userId, String key) {
		return jdbc.sql("""
				select pt.id from payment_transaction pt join user_subscription s on s.id=pt.subscription_id
				where s.user_id=:userId and pt.idempotency_key=:key
				""").param("userId", userId).param("key", key).query(UUID.class).optional()
				.map(this::findPaymentById).orElse(null);
	}

	private PaymentResponse findPayment(UUID userId, UUID paymentId) {
		var owned = jdbc.sql("""
				select pt.id from payment_transaction pt join user_subscription s on s.id=pt.subscription_id
				where pt.id=:paymentId and s.user_id=:userId
				""").param("paymentId", paymentId).param("userId", userId).query(UUID.class).optional();
		if (owned.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment was not found");
		return findPaymentById(paymentId);
	}

	private PaymentResponse findPaymentById(UUID paymentId) {
		return jdbc.sql("""
				select pt.id, pt.subscription_id, pt.target_plan_version_id, v.plan_code, v.version, v.pricing_policy_version,
				 pt.purpose, pt.payment_provider, pt.status, pt.amount_minor, pt.currency,
				 pt.checkout_url, pt.qr_code_url, pt.expires_at, pt.created_at, pt.paid_at, pt.failed_at,
                 pt.qr_code,pt.bank_bin,pt.bank_account_number,pt.bank_account_name,pt.payment_description,pt.terminal_reason
				from payment_transaction pt join subscription_plan_version v on v.id=pt.target_plan_version_id
				where pt.id=:id
				""").param("id", paymentId).query((row, ignored) -> new PaymentResponse(
				row.getObject("id", UUID.class), row.getObject("subscription_id", UUID.class),
				row.getObject("target_plan_version_id", UUID.class), row.getString("plan_code"),
				row.getString("version"), row.getString("pricing_policy_version"), row.getString("purpose"), row.getString("payment_provider"),
				row.getString("status"), row.getLong("amount_minor"), row.getString("currency"),
				row.getString("checkout_url"), row.getString("qr_code_url"), row.getTimestamp("expires_at").toInstant(),
				row.getTimestamp("created_at").toInstant(), instant(row, "paid_at"), instant(row, "failed_at"), row.getString("qr_code"), row.getString("bank_bin"),
                row.getString("bank_account_number"), row.getString("bank_account_name"), row.getString("payment_description"),
                row.getLong("amount_minor"), row.getTimestamp("expires_at").toInstant(), row.getString("terminal_reason"))).single();
	}

	private CurrentSubscription currentSubscription(UUID userId) {
		return jdbc.sql("""
				select s.id, s.user_id, s.plan_version_id, v.plan_code, v.non_consultation_value_minor, v.pricing_policy_version,
				 s.current_period_start, s.current_period_end from user_subscription s
				join subscription_plan_version v on v.id=s.plan_version_id
				where s.user_id=:userId and s.status='ACTIVE' and s.current_period_end > :now for update
				""").param("userId", userId).param("now", databaseNow()).query((row, ignored) -> new CurrentSubscription(
				row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
				row.getObject("plan_version_id", UUID.class), row.getString("plan_code"), row.getString("pricing_policy_version"),
				row.getLong("non_consultation_value_minor"), row.getTimestamp("current_period_start").toInstant(),
				row.getTimestamp("current_period_end").toInstant())).optional().orElse(null);
	}

	private Plan activePlan(UUID id) {
		return jdbc.sql("""
				select id, plan_code, version, pricing_policy_version, price_minor, non_consultation_value_minor,
				 consultation_credits_per_period, credit_value_minor from subscription_plan_version
				where id=:id and currency='VND' and effective_from <= :now and (retired_at is null or retired_at > :now)
				""").param("id", id).param("now", databaseNow()).query((row, ignored) -> new Plan(
				row.getObject("id", UUID.class), row.getString("plan_code"), row.getString("version"), row.getString("pricing_policy_version"),
				row.getLong("price_minor"), row.getLong("non_consultation_value_minor"),
				row.getInt("consultation_credits_per_period"), row.getLong("credit_value_minor"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_VERSION_NOT_AVAILABLE",
						"Plan version is not available"));
	}

	private List<String> entitlements(UUID planVersionId) {
		return jdbc.sql("select entitlement_code from subscription_plan_entitlement where plan_version_id=:id order by entitlement_code")
				.param("id", planVersionId).query(String.class).list();
	}

	private List<String> upgrades(String planCode) {
		return switch (planCode) {
			case "FREE" -> List.of("PLUS", "PREMIUM");
			case "PLUS" -> List.of("PREMIUM");
			default -> List.of();
		};
	}

	private void validateKey(String key) {
		if (key == null || key.length() < 16 || key.length() > 128 || !key.chars().allMatch(c -> c >= 33 && c <= 126)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
					"Idempotency-Key must contain 16 to 128 visible ASCII characters");
		}
	}

	private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
		var value = row.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime databaseNow() { return databaseInstant(clock.instant()); }
	private OffsetDateTime databaseInstant(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
	private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

	private record Plan(UUID id, String planCode, String version, String pricingPolicyVersion, long priceMinor,
			long nonConsultationValueMinor, int credits, long creditValueMinor) { }

	private record CurrentSubscription(UUID id, UUID userId, UUID planVersionId, String planCode,
			String pricingPolicyVersion, long nonConsultationValueMinor, Instant periodStart, Instant periodEnd) { }

	private record PreparedIdentity(String orderId, String requestId, Long orderCode) { }
	private record CheckoutPreparation(PaymentResponse payment, boolean created) { }
}
