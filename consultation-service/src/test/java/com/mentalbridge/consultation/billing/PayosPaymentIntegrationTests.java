package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.credits.ServiceCreditService;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("staging")
class PayosPaymentIntegrationTests extends ConsultationTestProperties {

    private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");
    private static final UUID PREMIUM = UUID.fromString("62200000-0000-4000-8000-000000000003");
    private static final String CHECKSUM_KEY = "synthetic-payos-checksum-key";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired SubscriptionBillingService billing;
    @Autowired CollectPaymentGateway collectGateway;
    @Autowired PayosPaymentWebhookService webhooks;
    @Autowired ServiceCreditService credits;
    @Autowired BillingProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean PayosCheckoutGateway gateway;
    @MockitoSpyBean PaymentSettlementService settlement;
    @MockitoSpyBean Clock clock;

    @DynamicPropertySource
    static void payosProperties(DynamicPropertyRegistry properties) {
        properties.add("mentalbridge.consultation.billing.payos-client-id", () -> "synthetic-client");
        properties.add("mentalbridge.consultation.billing.payos-api-key", () -> "synthetic-api-key");
        properties.add("mentalbridge.consultation.billing.payos-checksum-key", () -> CHECKSUM_KEY);
    }

    @BeforeEach
    void syntheticProvider() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var order = invocation.getArgument(0, Long.class);
            return jdbc.sql("select payos_payment_link_id,amount_minor from payment_transaction where order_code=:code")
                    .param("code", order).query((row, ignored) -> new PayosCheckoutGateway.Status(order,
                    row.getString(1), row.getLong(2), 0, row.getLong(2), "PENDING", List.of())).single();
        }).when(gateway).query(anyLong());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(gateway).cancel(anyLong());
    }

    @Test
    void publicWebhookRejectsSignatureAndMalformedPayloadWithoutReceipts() throws Exception {
        var payment = checkout(PLUS);
        var event = event(payment, "invalid-signature");
        event.put("signature", "0".repeat(64));
        send(event, 401);
        assertThat(receipts(payment)).isZero();
        send(json.createObjectNode().put("code", "00"), 400);
        assertThat(receipts(payment)).isZero();
        assertThat(current(payment).status()).isEqualTo("PENDING");
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void completeSignedWebhookSettlesPurchaseExactlyOnceAndDistinctTerminalEventNeedsReview() throws Exception {
        var payment = checkout(PLUS);
        var event = event(payment, "settled-reference");
        send(event, 204);
        send(event, 204);
        assertThat(current(payment).status()).isEqualTo("SUCCEEDED");
        assertThat(current(payment).terminalReason()).isNull();
        assertThat(credits.current(payment.userId()).balance().available()).isEqualTo(4);
        assertThat(receipts(payment)).isOne();
        send(event(payment, "distinct-after-final"), 204);
        assertThat(outcome("distinct-after-final")).isEqualTo("VERIFIED_UNMATCHED:PAYMENT_ALREADY_FINAL");
        assertThat(credits.current(payment.userId()).balance().available()).isEqualTo(4);
        assertThat(jdbc.sql("select count(*) from service_credit_ledger where account_id=:id and event_type='PROVISIONED'")
                .param("id", payment.userId()).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void payosCheckoutDurablyPreparesProviderIdentityThenPersistsQrAndReplaysWithoutResubmission() {
        var userId = UUID.randomUUID();
        var key = "payos-mode-" + UUID.randomUUID();
        var link = UUID.randomUUID().toString().replace("-", "");
        properties.setPayosWebhookUrl("https://staging.mentalbridge.online/internal/v1/payments/payos/webhook");
        properties.setPayosReturnUrl("https://staging.mentalbridge.online/subscription");
        properties.setPayosCancelUrl("https://staging.mentalbridge.online/subscription");
        properties.setMode("PAYOS");
        try {
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var order = invocation.getArgument(0, Long.class);
                assertThat(invocation.getArgument(1, Long.class)).isEqualTo(5_000);
                assertThat(jdbc.sql("""
                        select payment_provider='PAYOS' and momo_order_id is null and momo_request_id is null
                         and status='PENDING' and checkout_url is null and qr_code is null
                        from payment_transaction where order_code=:orderCode
                        """).param("orderCode", order).query(Boolean.class).single()).isTrue();
                return new CollectPaymentGateway.Checkout(null, null, link, "test-payos-qr", "970418",
                        "123456789", "TEST HOLDER", "MB0000001");
            }).when(gateway).create(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(Instant.class));
            var payment = billing.checkout(userId, PLUS, key);
            assertThat(payment.provider()).isEqualTo("PAYOS");
            assertThat(payment.qrCode()).isEqualTo("test-payos-qr");
            assertThat(payment.bin()).isEqualTo("970418");
            assertThat(payment.accountNumber()).isEqualTo("123456789");
            assertThat(payment.accountName()).isEqualTo("TEST HOLDER");
            assertThat(payment.amount()).isEqualTo(payment.amountVnd());
            assertThat(payment.expiredAt()).isEqualTo(payment.expiresAt());
            assertThat(billing.checkout(userId, PLUS, key)).isEqualTo(payment);
            verify(gateway, times(1)).create(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(Instant.class));
        }
        finally {
            properties.setMode("FAKE");
        }
    }

    @Test
    void signedEnvelopeAmountOrderLinkAndReusedReferenceNeverGrantCredits() throws Exception {
        var payment = checkout(PLUS);
        var envelope = event(payment, "unsuccessful-envelope");
        envelope.put("success", false);
        send(envelope, 204);
        var code = event(payment, "unsuccessful-data-code");
        ((ObjectNode) code.get("data")).put("code", "01");
        sign(code);
        send(code, 204);
        var amount = event(payment, "wrong-amount");
        ((ObjectNode) amount.get("data")).put("amount", payment.original().amountVnd() - 1);
        sign(amount);
        send(amount, 204);
        var link = event(payment, "wrong-link");
        ((ObjectNode) link.get("data")).put("paymentLinkId", UUID.randomUUID().toString().replace("-", ""));
        sign(link);
        send(link, 204);
        var unknown = event(payment, "unknown-order");
        ((ObjectNode) unknown.get("data")).put("orderCode", 8_000_000_000_000L);
        sign(unknown);
        send(unknown, 204);
        assertThat(outcome("wrong-amount")).isEqualTo("VERIFIED_UNMATCHED:AMOUNT_MISMATCH");
        assertThat(outcome("wrong-link")).isEqualTo("VERIFIED_UNMATCHED:LINK_MISMATCH");
        assertThat(outcome("unknown-order")).isEqualTo("VERIFIED_UNMATCHED:ORDER_NOT_FOUND");
        assertThat(outcome("unsuccessful-envelope")).isEqualTo("VERIFIED_UNMATCHED:PROVIDER_RESULT_NOT_SUCCESSFUL");
        assertThat(outcome("unsuccessful-data-code")).isEqualTo("VERIFIED_UNMATCHED:PROVIDER_RESULT_NOT_SUCCESSFUL");
        var other = checkout(PLUS);
        send(event(other, "shared-reference"), 204);
        send(event(payment, "shared-reference"), 204);
        assertThat(jdbc.sql("select failure_code from payos_payment_webhook where payment_id=:id and reference='shared-reference'")
                .param("id", payment.original().paymentId()).query(String.class).single()).isEqualTo("REFERENCE_CONFLICT");
        assertThat(current(payment).status()).isEqualTo("PENDING");
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void signedUnknownConfirmationSampleIsAcknowledgedWithoutActivation() throws Exception {
        var payment = checkout(PLUS);
        var sample = event(payment, "confirmation-sample");
        ((ObjectNode) sample.get("data")).put("orderCode", 123).put("amount", 0);
        sign(sample);
        send(sample, 204);
        assertThat(outcome("confirmation-sample")).isEqualTo("VERIFIED_UNMATCHED:ORDER_NOT_FOUND");
        assertThat(current(payment).status()).isEqualTo("PENDING");
    }

    @Test
    void simultaneousExactDeliveryUsesOneReceiptAndOneCreditGrant() throws Exception {
        var payment = checkout(PLUS);
        var originalBody = json.writeValueAsString(event(payment, "concurrent-reference"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> delivery = () -> { start.await(); webhooks.receive(originalBody); return null; };
            var first = executor.submit(delivery);
            var second = executor.submit(delivery);
            start.countDown();
            first.get();
            second.get();
        }
        assertThat(receipts(payment)).isOne();
        assertThat(credits.current(payment.userId()).balance().available()).isEqualTo(4);
    }

    @Test
    void authenticatedQueryRecoversMissedWebhookAndUnconfirmedLinkWithoutRemoteTransaction() throws Exception {
        var payment = checkout(PLUS);
        jdbc.sql("update payment_transaction set payos_payment_link_id=null,reconciliation_next_at=null where id=:id")
                .param("id", payment.original().paymentId()).update();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return paid(payment, "missed-webhook");
        }).when(gateway).query(payment.orderCode());
        assertThat(current(payment).status()).isEqualTo("SUCCEEDED");
        assertThat(credits.current(payment.userId()).balance().available()).isEqualTo(4);
        assertThat(jdbc.sql("select payos_payment_link_id from payment_transaction where id=:id")
                .param("id", payment.original().paymentId()).query(String.class).single()).isEqualTo(payment.link());
        assertThat(jdbc.sql("select source from payos_payment_webhook where reference='missed-webhook'")
                .query(String.class).single()).isEqualTo("RECONCILIATION");
        current(payment);
        verify(gateway, times(1)).query(payment.orderCode());
    }

    @Test
    void queryUnderpaymentOrderMismatchAndOutageStayPendingWithCadenceAndBackoff() throws Exception {
        var underpaid = checkout(PLUS);
        due(underpaid);
        doReturn(new PayosCheckoutGateway.Status(underpaid.orderCode(), underpaid.link(), 5_000, 4_999, 1,
                "PAID", List.of(new PayosCheckoutGateway.Transaction(4_999, "partial-reference"))))
                .when(gateway).query(underpaid.orderCode());
        assertThat(current(underpaid).status()).isEqualTo("PENDING");
        assertThat(outcome("partial-reference")).isEqualTo("VERIFIED_UNMATCHED:PROVIDER_RESULT_NOT_SUCCESSFUL");
        current(underpaid);
        verify(gateway, times(1)).query(underpaid.orderCode());
        var wrongOrder = checkout(PLUS);
        due(wrongOrder);
        doReturn(paid(underpaid, "wrong-order-query")).when(gateway).query(wrongOrder.orderCode());
        assertThat(current(wrongOrder).status()).isEqualTo("PENDING");
        assertThat(current(underpaid).status()).isEqualTo("PENDING");
        assertThat(outcome("wrong-order-query")).isEqualTo("VERIFIED_UNMATCHED:ORDER_MISMATCH");
        var unavailable = checkout(PLUS);
        due(unavailable);
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_STATUS_UNAVAILABLE", "Unavailable"))
                .when(gateway).query(unavailable.orderCode());
        assertThat(current(unavailable).status()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select reconciliation_failures from payment_transaction where id=:id")
                .param("id", unavailable.original().paymentId()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("select reconciliation_next_at > current_timestamp + interval '40 seconds' from payment_transaction where id=:id")
                .param("id", unavailable.original().paymentId()).query(Boolean.class).single()).isTrue();
        current(unavailable);
        verify(gateway, times(1)).query(unavailable.orderCode());
    }

    @Test
    void cancelledUpgradeRestoresExactHeldCreditsAndCannotReopenOnLateWebhook() throws Exception {
        var plus = checkout(PLUS);
        send(event(plus, "upgrade-plus-purchase"), 204);
        var upgrade = checkout(plus.userId(), PREMIUM);
        assertThat(credits.current(plus.userId()).balance().available()).isZero();
        due(upgrade);
        doReturn(new PayosCheckoutGateway.Status(upgrade.orderCode(), upgrade.link(), 10_000, 0, 10_000,
                "CANCELLED", List.of())).when(gateway).query(upgrade.orderCode());
        assertThat(current(upgrade).status()).isEqualTo("FAILED");
        assertThat(current(upgrade).terminalReason()).isEqualTo("CANCELLED");
        assertThat(credits.current(plus.userId()).balance().available()).isEqualTo(4);
        var retry = checkout(plus.userId(), PREMIUM);
        send(event(retry, "cancelled-upgrade-retry"), 204);
        assertThat(current(retry).status()).isEqualTo("SUCCEEDED");
        assertThat(credits.current(plus.userId()).balance().available()).isEqualTo(10);
        assertThat(jdbc.sql("""
                select count(*) from subscription_upgrade_credit uc join subscription_upgrade u on u.id=uc.upgrade_id
                where u.payment_id in (:first,:retry)
                """).param("first", upgrade.original().paymentId()).param("retry", retry.original().paymentId())
                .query(Long.class).single()).isEqualTo(8);
        send(event(upgrade, "late-cancelled"), 204);
        assertThat(outcome("late-cancelled")).isEqualTo("VERIFIED_UNMATCHED:PAYMENT_ALREADY_FINAL");
        assertThat(current(upgrade).terminalReason()).isEqualTo("CANCELLED");
        assertThat(credits.current(plus.userId()).balance().available()).isEqualTo(10);
    }

    @Test
    void authenticatedFailureUsesProviderReasonAndConflictingCancellationLinkStaysPending() throws Exception {
        var failed = checkout(PLUS);
        due(failed);
        doReturn(new PayosCheckoutGateway.Status(failed.orderCode(), failed.link(), 5_000, 0, 5_000,
                "FAILED", List.of())).when(gateway).query(failed.orderCode());
        assertThat(current(failed).status()).isEqualTo("FAILED");
        assertThat(current(failed).terminalReason()).isEqualTo("PROVIDER_FAILED");
        var conflicting = checkout(PLUS);
        jdbc.sql("update payment_transaction set payos_payment_link_id=null,reconciliation_next_at=null where id=:id")
                .param("id", conflicting.original().paymentId()).update();
        doReturn(new PayosCheckoutGateway.Status(conflicting.orderCode(), failed.link(), 5_000, 0, 5_000,
                "CANCELLED", List.of())).when(gateway).query(conflicting.orderCode());
        assertThat(current(conflicting).status()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select failure_code from payos_payment_webhook where payment_id=:id")
                .param("id", conflicting.original().paymentId()).query(String.class).single()).isEqualTo("LINK_CONFLICT");
        assertThat(credits.current(conflicting.userId()).balance().available()).isZero();
    }

    @Test
    void verifiedWebhookRecoversUnconfirmedCreationAndSuccessAfterDeadlineCannotActivate() throws Exception {
        var uncertain = checkout(PLUS);
        jdbc.sql("update payment_transaction set payos_payment_link_id=null,qr_code=null where id=:id")
                .param("id", uncertain.original().paymentId()).update();
        send(event(uncertain, "uncertain-create-webhook"), 204);
        assertThat(current(uncertain).status()).isEqualTo("SUCCEEDED");
        assertThat(credits.current(uncertain.userId()).balance().available()).isEqualTo(4);
        var overdue = checkout(PLUS);
        jdbc.sql("""
                update payment_transaction set created_at=current_timestamp-interval '20 minutes',
                 expires_at=current_timestamp-interval '1 second' where id=:id
                """).param("id", overdue.original().paymentId()).update();
        send(event(overdue, "overdue-pending-webhook"), 204);
        assertThat(outcome("overdue-pending-webhook")).isEqualTo("VERIFIED_UNMATCHED:PAYMENT_EXPIRED");
        assertThat(current(overdue).terminalReason()).isEqualTo("EXPIRED");
        assertThat(credits.current(overdue.userId()).balance().available()).isZero();
    }

    @Test
    void expiryCancelsProviderOutsideTransactionAndLatePaymentStaysUnmatched() throws Exception {
        var payment = checkout(PLUS);
        jdbc.sql("""
                update payment_transaction set created_at=current_timestamp-interval '20 minutes',
                 expires_at=current_timestamp-interval '1 second',reconciliation_next_at=null where id=:id
                """).param("id", payment.original().paymentId()).update();
        var expired = current(payment);
        assertThat(expired.status()).isEqualTo("FAILED");
        assertThat(expired.terminalReason()).isEqualTo("EXPIRED");
        verify(gateway).cancel(payment.orderCode());
        send(event(payment, "late-expired"), 204);
        assertThat(outcome("late-expired")).isEqualTo("VERIFIED_UNMATCHED:PAYMENT_ALREADY_FINAL");
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void committedExpiryCancellationSurvivesStopBeforeProviderCallAndResumesOnFreshScheduler() throws Exception {
        var plus = checkout(PLUS);
        send(event(plus, "restart-plus-purchase"), 204);
        var upgrade = checkout(plus.userId(), PREMIUM);
        jdbc.sql("""
                update payment_transaction set created_at=current_timestamp-interval '20 minutes',
                 expires_at=current_timestamp-interval '1 second',reconciliation_next_at=null where id=:id
                """).param("id", upgrade.original().paymentId()).update();
        doAnswer(invocation -> {
            var changed = (Boolean) invocation.callRealMethod();
            if (changed) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new IllegalStateException("Simulated stop after expiry commit");
                    }
                });
            }
            return changed;
        }).when(settlement).fail(argThat(payment -> payment.id().equals(upgrade.original().paymentId())),
                any(Instant.class), eq("EXPIRED"));

        assertThatThrownBy(() -> current(upgrade)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated stop after expiry commit");
        assertThat(jdbc.sql("""
                select status='FAILED' and terminal_reason='EXPIRED'
                 and reconciliation_failure_code='PAYOS_CANCEL_PENDING' and reconciliation_failures=0
                 and reconciliation_next_at>current_timestamp
                 and reconciliation_next_at<=current_timestamp+interval '31 seconds'
                from payment_transaction where id=:id
                """).param("id", upgrade.original().paymentId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select status from subscription_upgrade where payment_id=:id")
                .param("id", upgrade.original().paymentId()).query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(credits.current(plus.userId()).balance().available()).isEqualTo(4);
        verify(gateway, times(0)).cancel(upgrade.orderCode());

        var restartedWebhooks = new PayosPaymentWebhookService(jdbc, clock, gateway, settlement, transactionManager);
        var restartedBilling = new SubscriptionBillingService(jdbc, clock, properties, collectGateway,
                restartedWebhooks, settlement, transactionManager);
        restartedBilling.expirePendingCheckouts();
        verify(gateway, times(0)).cancel(upgrade.orderCode());
        doReturn(Instant.now().plusSeconds(31)).when(clock).instant();
        restartedBilling.expirePendingCheckouts();
        verify(gateway, times(1)).cancel(upgrade.orderCode());
        assertThat(jdbc.sql("""
                select status='FAILED' and terminal_reason='EXPIRED' and reconciliation_failure_code is null
                 and reconciliation_next_at is null and reconciliation_failures=0
                from payment_transaction where id=:id
                """).param("id", upgrade.original().paymentId()).query(Boolean.class).single()).isTrue();
        restartedBilling.expirePendingCheckouts();
        verify(gateway, times(1)).cancel(upgrade.orderCode());
        assertThat(credits.current(plus.userId()).balance().available()).isEqualTo(4);
    }

    @Test
    void cancelFailureRetriesAfterBackoffWithoutReopeningExpiredPayment() {
        var payment = checkout(PLUS);
        jdbc.sql("""
                update payment_transaction set created_at=current_timestamp-interval '20 minutes',
                 expires_at=current_timestamp-interval '1 second',reconciliation_next_at=null where id=:id
                """).param("id", payment.original().paymentId()).update();
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_STATUS_UNAVAILABLE", "Unavailable"))
                .when(gateway).cancel(payment.orderCode());
        assertThat(current(payment).terminalReason()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("select reconciliation_failure_code from payment_transaction where id=:id")
                .param("id", payment.original().paymentId()).query(String.class).single()).isEqualTo("PAYOS_CANCEL_UNAVAILABLE");
        current(payment);
        verify(gateway, times(1)).cancel(payment.orderCode());
        due(payment);
        doNothing().when(gateway).cancel(payment.orderCode());
        assertThat(current(payment).status()).isEqualTo("FAILED");
        verify(gateway, times(2)).cancel(payment.orderCode());
        assertThat(jdbc.sql("select reconciliation_failure_code is null and reconciliation_failures=0 from payment_transaction where id=:id")
                .param("id", payment.original().paymentId()).query(Boolean.class).single()).isTrue();
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void paymentLockWaitCrossingDeadlineCannotSettleUsingReceiptArrivalTime() throws Exception {
        var payment = checkout(PLUS);
        var event = json.writeValueAsString(event(payment, "lock-expiry-reference"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        doAnswer(invocation -> {
            attempting.countDown();
            return invocation.callRealMethod();
        }).when(settlement).lockPayos(payment.orderCode());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                jdbc.sql("select id from payment_transaction where id=:id for update")
                        .param("id", payment.original().paymentId()).query(UUID.class).single();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Lock was not released");
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var delivery = executor.submit(() -> webhooks.receive(event));
            assertThat(attempting.await(10, TimeUnit.SECONDS)).isTrue();
            doReturn(Instant.now().plusSeconds(3_600)).when(clock).instant();
            release.countDown();
            holder.get();
            delivery.get();
        }
        finally {
            release.countDown();
        }
        assertThat(outcome("lock-expiry-reference")).isEqualTo("VERIFIED_UNMATCHED:PAYMENT_EXPIRED");
        assertThat(current(payment).terminalReason()).isEqualTo("EXPIRED");
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void repeatedCancelOutageStopsAfterSixAttemptsForAdminReview() {
        var payment = checkout(PLUS);
        jdbc.sql("""
                update payment_transaction set created_at=current_timestamp-interval '20 minutes',
                 expires_at=current_timestamp-interval '1 second',reconciliation_next_at=null,
                 reconciliation_failures=4,reconciliation_failure_code='PAYOS_STATUS_UNAVAILABLE' where id=:id
                """).param("id", payment.original().paymentId()).update();
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_STATUS_UNAVAILABLE", "Unavailable"))
                .when(gateway).query(payment.orderCode());
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYOS_STATUS_UNAVAILABLE", "Unavailable"))
                .when(gateway).cancel(payment.orderCode());
        for (var attempt = 0; attempt < 7; attempt++) {
            if (attempt > 0) due(payment);
            assertThat(current(payment).terminalReason()).isEqualTo("EXPIRED");
        }
        verify(gateway, times(6)).cancel(payment.orderCode());
        assertThat(jdbc.sql("select reconciliation_failure_code from payment_transaction where id=:id")
                .param("id", payment.original().paymentId()).query(String.class).single()).isEqualTo("PAYOS_CANCEL_REQUIRES_REVIEW");
        assertThat(credits.current(payment.userId()).balance().available()).isZero();
    }

    @Test
    void ownerStatusPollingChecksOwnershipBeforeQueryAndExposesQrAliases() throws Exception {
        var payment = checkout(PLUS);
        due(payment);
        mvc.perform(get("/api/v1/payments/" + payment.original().paymentId()).with(jwt()
                .jwt(token -> token.subject(UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isNotFound());
        verify(gateway, times(0)).query(payment.orderCode());
        mvc.perform(get("/api/v1/payments/" + payment.original().paymentId()).with(jwt()
                .jwt(token -> token.subject(payment.userId().toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("PAYOS"))
                .andExpect(jsonPath("$.amount").value(5_000)).andExpect(jsonPath("$.bin").value("000000"))
                .andExpect(jsonPath("$.qrCode").isString()).andExpect(jsonPath("$.expiredAt").isString());
    }

    private Fixture checkout(UUID planId) { return checkout(UUID.randomUUID(), planId); }

    private Fixture checkout(UUID userId, UUID planId) {
        var original = billing.checkout(userId, planId, "payos-test-" + UUID.randomUUID());
        var link = UUID.randomUUID().toString().replace("-", "");
        var order = jdbc.sql("""
                update payment_transaction set payment_provider='PAYOS',momo_order_id=null,momo_request_id=null,
                 payos_payment_link_id=:link,reconciliation_next_at=current_timestamp+interval '1 hour'
                where id=:id returning order_code
                """).param("link", link).param("id", original.paymentId()).query(Long.class).single();
        return new Fixture(userId, original, order, link);
    }

    private PaymentResponse current(Fixture payment) { return billing.payment(payment.userId(), payment.original().paymentId()); }

    private void due(Fixture payment) {
        jdbc.sql("update payment_transaction set reconciliation_next_at=current_timestamp-interval '1 second' where id=:id")
                .param("id", payment.original().paymentId()).update();
    }

    private PayosCheckoutGateway.Status paid(Fixture payment, String reference) {
        return new PayosCheckoutGateway.Status(payment.orderCode(), payment.link(), payment.original().amountVnd(),
                payment.original().amountVnd(), 0, "PAID", List.of(new PayosCheckoutGateway.Transaction(payment.original().amountVnd(), reference)));
    }

    private ObjectNode event(Fixture payment, String reference) throws Exception {
        var root = json.createObjectNode().put("code", "00").put("desc", "success").put("success", true);
        root.putObject("data").put("orderCode", payment.orderCode()).put("amount", payment.original().amountVnd())
                .put("description", "MBTEST001").put("accountNumber", "123456789").put("reference", reference)
                .put("transactionDateTime", "2026-10-30 10:00:00").put("currency", "VND")
                .put("paymentLinkId", payment.link()).put("code", "00").put("desc", "success")
                .put("counterAccountBankId", "").put("counterAccountBankName", "").put("counterAccountName", "")
                .put("counterAccountNumber", "").put("virtualAccountName", "").put("virtualAccountNumber", "");
        sign(root);
        return root;
    }

    private void sign(ObjectNode root) throws Exception {
        var values = new TreeMap<String, String>();
        root.get("data").fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        var raw = values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CHECKSUM_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        root.put("signature", HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
    }

    private void send(ObjectNode event, int expected) throws Exception {
        mvc.perform(post("/internal/v1/payments/payos/webhook").contentType("application/json")
                .content(json.writeValueAsBytes(event))).andExpect(status().is(expected));
    }

    private long receipts(Fixture payment) {
        return jdbc.sql("select count(*) from payos_payment_webhook where payment_id=:id")
                .param("id", payment.original().paymentId()).query(Long.class).single();
    }

    private String outcome(String reference) {
        return jdbc.sql("select processing_status||':'||coalesce(failure_code,'') from payos_payment_webhook where reference=:reference")
                .param("reference", reference).query(String.class).single();
    }

    private record Fixture(UUID userId, PaymentResponse original, long orderCode, String link) { }
}
