package com.mentalbridge.consultation.earnings;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

final class StagingDemoEarningSource {

	static final String POLICY_VERSION = "staging-demo-paid-v1";
	private static final String VERIFIED_PAYMENT = """
			from service_credit c
			join service_credit_period p on p.id=c.period_id
			join subscription_plan_version v on v.id::text=p.plan_version
			join user_subscription s on s.id::text=p.source_reference and s.user_id=p.account_id
			join payment_transaction pt on pt.subscription_id=s.id and pt.target_plan_version_id=v.id
			where p.source='PAID' and v.pricing_policy_version='sandbox-full-price-v1'
			  and p.package_code=v.plan_code and p.allocated_count=v.consultation_credits_per_period
			  and v.consultation_credits_per_period>0 and pt.amount_minor>=v.consultation_credits_per_period
			  and pt.payment_provider='PAYOS' and pt.status='SUCCEEDED' and pt.paid_at=p.period_start
			  and pt.payos_reference is not null and pt.payos_payment_link_id is not null
			  and exists (
			    select 1 from payos_payment_webhook r
			    where r.payment_id=pt.id and r.contract_version='payos-collect-v1'
			      and r.processing_status='PROCESSED' and r.success=true and r.code='00'
			      and r.signature_verified_at is not null and r.processed_at is not null and r.failure_code is null
			      and r.order_code=pt.order_code and r.amount_minor=pt.amount_minor
			      and r.payment_link_id=pt.payos_payment_link_id and r.reference=pt.payos_reference
			  )
			""";
	static final String ELIGIBLE_EARNING = "exists (select 1 " + VERIFIED_PAYMENT + """
			  and c.id=e.consumed_credit_id and e.plan_version=p.plan_version
			  and e.earning_policy_version='staging-demo-paid-v1' and e.funding_payment_id=pt.id
			  and e.credit_allocation_minor=pt.amount_minor/v.consultation_credits_per_period
			  and e.specialist_share_bps=7000
			)
			""";

	private StagingDemoEarningSource() { }

	static Optional<Funding> verifiedFunding(JdbcClient jdbc, UUID creditId) {
		return jdbc.sql("select pt.id,pt.amount_minor/v.consultation_credits_per_period as allocation "
				+ VERIFIED_PAYMENT + " and c.id=:creditId").param("creditId", creditId)
				.query((row, ignored) -> new Funding(row.getObject("id", UUID.class), row.getLong("allocation")))
				.optional();
	}

	record Funding(UUID paymentId, long creditAllocationVnd) { }
}
