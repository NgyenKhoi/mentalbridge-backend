package com.mentalbridge.consultation.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.consultation.shared.ApiException;

@Service
class PayosPaymentWebhookService {

    private static final String CONTRACT_VERSION = "payos-collect-v1";
    private static final int CANCEL_ATTEMPT_LIMIT = 6;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final PayosCheckoutGateway gateway;
    private final PaymentSettlementService settlement;
    private final TransactionTemplate transactions;

    PayosPaymentWebhookService(JdbcClient jdbc, Clock clock, PayosCheckoutGateway gateway,
            PaymentSettlementService settlement, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.gateway = gateway;
        this.settlement = settlement;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void receive(String originalBody) {
        var verified = gateway.verify(originalBody);
        var evidence = new Evidence(verified.orderCode(), verified.amount(), verified.paymentLinkId(),
                verified.reference(), verified.successful());
        transactions.executeWithoutResult(ignored -> process(evidence, "WEBHOOK", sha256(originalBody), null));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcile(UUID paymentId) {
        var now = clock.instant();
        var claimed = jdbc.sql("""
                update payment_transaction set reconciliation_next_at=:next
                where id=:id and payment_provider='PAYOS' and status='PENDING'
                 and (reconciliation_next_at is null or reconciliation_next_at<=:now)
                returning order_code
                """).param("next", databaseInstant(now.plusSeconds(30))).param("now", databaseInstant(now))
                .param("id", paymentId).query(Long.class).optional().orElse(null);
        if (claimed != null) {
            try {
                var status = gateway.query(claimed);
                transactions.executeWithoutResult(ignored -> applyStatus(paymentId, status));
                jdbc.sql("""
                        update payment_transaction set reconciliation_failures=0, reconciliation_failure_code=null
                        where id=:id and (reconciliation_failure_code is null
                         or reconciliation_failure_code='PAYOS_STATUS_UNAVAILABLE')
                        """).param("id", paymentId).update();
            }
            catch (ApiException exception) {
                jdbc.sql("""
                        update payment_transaction set reconciliation_failures=least(reconciliation_failures+1,10),
                         reconciliation_failure_code='PAYOS_STATUS_UNAVAILABLE',
                         reconciliation_next_at=:now + make_interval(secs => least(300,30*power(2,least(reconciliation_failures+1,4)))::int)
                        where id=:id and status='PENDING'
                        """).param("now", databaseInstant(clock.instant())).param("id", paymentId).update();
            }
        }
        expire(paymentId);
        retryCancellation(paymentId);
    }

    private void applyStatus(UUID paymentId, PayosCheckoutGateway.Status status) {
        if ("PAID".equals(status.status()) || "UNDERPAID".equals(status.status())) {
            var validTransaction = status.transactions() != null && status.transactions().size() == 1;
            var transaction = validTransaction ? status.transactions().getFirst() : null;
            var exact = "PAID".equals(status.status()) && validTransaction && transaction.amount() == status.amount()
                    && status.amountPaid() == status.amount() && status.amountRemaining() == 0;
            var evidence = new Evidence(status.orderCode(), status.amount(), status.paymentLinkId(),
                    transaction == null ? "" : transaction.reference(), exact);
            process(evidence, "RECONCILIATION", sha256(status.toString()), paymentId);
            return;
        }
        if ("CANCELLED".equals(status.status()) || "EXPIRED".equals(status.status()) || "FAILED".equals(status.status())) {
            applyTerminalStatus(paymentId, status);
        }
    }

    private void applyTerminalStatus(UUID paymentId, PayosCheckoutGateway.Status status) {
        var now = clock.instant();
        var reference = status.transactions() == null || status.transactions().isEmpty() ? "" : status.transactions().getFirst().reference();
        var evidence = new Evidence(status.orderCode(), status.amount(), status.paymentLinkId(), reference, false);
        var receiptId = insertReceipt(evidence, "RECONCILIATION", sha256(status.toString()), now);
        if (receiptId == null) return;
        lockLink(status.paymentLinkId());
        var payment = settlement.lock(paymentId);
        now = clock.instant();
        var failure = identityMismatch(payment, status.orderCode(), status.paymentLinkId(), status.amount());
        if (failure == null && !"PENDING".equals(payment.status())) failure = "PAYMENT_ALREADY_FINAL";
        if (payment != null) correlate(receiptId, payment.id());
        if (failure != null) {
            finish(receiptId, "VERIFIED_UNMATCHED", failure, now);
            return;
        }
        bindLink(payment, status.paymentLinkId());
        var reason = switch (status.status()) {
            case "EXPIRED" -> "EXPIRED";
            case "CANCELLED" -> "CANCELLED";
            default -> "PROVIDER_FAILED";
        };
        settlement.fail(payment, now, reason);
        finish(receiptId, "PROCESSED", reason, now);
    }

    private void process(Evidence evidence, String source, String payloadHash, UUID expectedPaymentId) {
        var now = clock.instant();
        var receiptId = insertReceipt(evidence, source, payloadHash, now);
        if (receiptId == null) return;
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "payos-reference:" + evidence.reference()).query(String.class).single();
        lockLink(evidence.paymentLinkId());
        var payment = expectedPaymentId == null ? settlement.lockPayos(evidence.orderCode()) : settlement.lock(expectedPaymentId);
        now = clock.instant();
        var mismatch = mismatch(payment, evidence, now);
        if (payment != null) correlate(receiptId, payment.id());
        if (mismatch != null) {
            finish(receiptId, "VERIFIED_UNMATCHED", mismatch, now);
            return;
        }
        bindLink(payment, evidence.paymentLinkId());
        jdbc.sql("update payment_transaction set payos_reference=:reference where id=:id")
                .param("reference", evidence.reference()).param("id", payment.id()).update();
        settlement.succeed(payment, now);
        finish(receiptId, "PROCESSED", null, now);
    }

    private UUID insertReceipt(Evidence evidence, String source, String payloadHash, Instant now) {
        var receiptId = UUID.randomUUID();
        var dedup = sha256(CONTRACT_VERSION + "|" + source + "|" + payloadHash);
        var inserted = jdbc.sql("""
                insert into payos_payment_webhook(id,contract_version,source,deduplication_key,order_code,
                 amount_minor,payment_link_id,reference,payload_sha256,signature_key_version,
                 signature_verified_at,processing_status,received_at,acknowledged_at,code,success)
                values(:id,:contract,:source,:dedup,:orderCode,:amount,:link,:reference,:hash,'v1',
                 :now,'RECEIVED',:now,:now,:code,:success)
                on conflict(deduplication_key) do nothing
                """).param("id", receiptId).param("contract", CONTRACT_VERSION).param("source", source)
                .param("dedup", dedup).param("orderCode", evidence.orderCode()).param("amount", evidence.amount())
                .param("link", evidence.paymentLinkId()).param("reference", evidence.reference())
                .param("hash", payloadHash).param("now", databaseInstant(now))
                .param("code", evidence.successful() ? "00" : null).param("success", evidence.successful()).update();
        return inserted == 0 ? null : receiptId;
    }

    private void lockLink(String link) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key", "payos-link:" + link).query(String.class).single();
    }

    private void correlate(UUID receiptId, UUID paymentId) {
        jdbc.sql("update payos_payment_webhook set payment_id=:paymentId where id=:id")
                .param("paymentId", paymentId).param("id", receiptId).update();
    }

    private String mismatch(PaymentSettlementService.Payment payment, Evidence evidence, Instant now) {
        if (!evidence.successful()) return "PROVIDER_RESULT_NOT_SUCCESSFUL";
        var identityFailure = identityMismatch(payment, evidence.orderCode(), evidence.paymentLinkId(), evidence.amount());
        if (identityFailure != null) return identityFailure;
        if (evidence.reference() == null || evidence.reference().isBlank()) return "REFERENCE_INVALID";
        if (!"PENDING".equals(payment.status())) return "PAYMENT_ALREADY_FINAL";
        if (!payment.expiresAt().isAfter(now)) return "PAYMENT_EXPIRED";
        if (jdbc.sql("select count(*) from payment_transaction where payos_reference=:reference and id<>:id")
                .param("reference", evidence.reference()).param("id", payment.id()).query(Long.class).single() > 0) return "REFERENCE_CONFLICT";
        return null;
    }

    private String identityMismatch(PaymentSettlementService.Payment payment, long orderCode, String link, long amount) {
        if (payment == null) return "ORDER_NOT_FOUND";
        if (!"PAYOS".equals(payment.provider()) || payment.orderCode() == null || payment.orderCode() != orderCode) return "ORDER_MISMATCH";
        if (payment.amount() != amount) return "AMOUNT_MISMATCH";
        if (payment.paymentLinkId() != null && !payment.paymentLinkId().equals(link)) return "LINK_MISMATCH";
        if (link == null || link.isBlank()) return "LINK_INVALID";
        if (jdbc.sql("select count(*) from payment_transaction where payos_payment_link_id=:link and id<>:id")
                .param("link", link).param("id", payment.id()).query(Long.class).single() > 0) return "LINK_CONFLICT";
        return null;
    }

    private void bindLink(PaymentSettlementService.Payment payment, String link) {
        jdbc.sql("update payment_transaction set payos_payment_link_id=:link where id=:id and payos_payment_link_id is null")
                .param("link", link).param("id", payment.id()).update();
    }

    private void finish(UUID receiptId, String status, String failure, Instant now) {
        jdbc.sql("""
                update payos_payment_webhook set processing_status=:status,failure_code=:failure,
                 processed_at=:now,acknowledged_at=:now where id=:id
                """).param("status", status).param("failure", failure).param("now", databaseInstant(now))
                .param("id", receiptId).update();
    }

    private void expire(UUID paymentId) {
        var orderCode = transactions.execute(ignored -> {
            var payment = settlement.lock(paymentId);
            if (payment == null || !"PAYOS".equals(payment.provider()) || payment.expiresAt().isAfter(clock.instant())) return null;
            if (!settlement.fail(payment, clock.instant(), "EXPIRED")) return null;
            jdbc.sql("""
                    update payment_transaction set reconciliation_failures=0,reconciliation_failure_code='PAYOS_CANCEL_PENDING',
                     reconciliation_next_at=:next where id=:id
                    """).param("next", databaseInstant(clock.instant().plusSeconds(30))).param("id", paymentId).update();
            return payment.orderCode();
        });
        if (orderCode != null) cancelExpired(paymentId, orderCode);
    }

    private void retryCancellation(UUID paymentId) {
        var now = clock.instant();
        var orderCode = jdbc.sql("""
                update payment_transaction set reconciliation_next_at=:next
                where id=:id and payment_provider='PAYOS' and status='FAILED' and terminal_reason='EXPIRED'
                 and reconciliation_failure_code in ('PAYOS_CANCEL_PENDING','PAYOS_CANCEL_UNAVAILABLE')
                 and reconciliation_failures<:limit and reconciliation_next_at<=:now
                returning order_code
                """).param("next", databaseInstant(now.plusSeconds(30))).param("now", databaseInstant(now))
                .param("id", paymentId).param("limit", CANCEL_ATTEMPT_LIMIT).query(Long.class).optional().orElse(null);
        if (orderCode != null) cancelExpired(paymentId, orderCode);
    }

    private void cancelExpired(UUID paymentId, long orderCode) {
        try {
            gateway.cancel(orderCode);
            jdbc.sql("""
                    update payment_transaction set reconciliation_failures=0,reconciliation_failure_code=null,
                     reconciliation_next_at=null where id=:id
                    """).param("id", paymentId).update();
        }
        catch (ApiException exception) {
            jdbc.sql("""
                    update payment_transaction set reconciliation_failures=least(reconciliation_failures+1,:limit),
                     reconciliation_failure_code=case when reconciliation_failures+1>=:limit
                     then 'PAYOS_CANCEL_REQUIRES_REVIEW' else 'PAYOS_CANCEL_UNAVAILABLE' end,
                     reconciliation_next_at=:now + make_interval(secs => least(300,30*power(2,least(reconciliation_failures+1,4)))::int)
                    where id=:id
                    """).param("limit", CANCEL_ATTEMPT_LIMIT).param("now", databaseInstant(clock.instant()))
                    .param("id", paymentId).update();
        }
    }

    private String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Unable to hash payment evidence", exception); }
    }

    private OffsetDateTime databaseInstant(Instant value) { return OffsetDateTime.ofInstant(value, ZoneOffset.UTC); }
    private record Evidence(long orderCode, long amount, String paymentLinkId, String reference, boolean successful) { }
}
