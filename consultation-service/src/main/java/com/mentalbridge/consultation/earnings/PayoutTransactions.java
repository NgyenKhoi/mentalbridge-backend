package com.mentalbridge.consultation.earnings;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
class PayoutTransactions {

	private static final String ELIGIBLE_SOURCE = """
			exists (
			 select 1 from appointment a join service_credit c on c.id=a.service_credit_id
			 where a.id=specialist_earning.appointment_id and a.status='COMPLETED'
			   and a.session_outcome='COMPLETED' and a.session_settled_at is not null
			   and a.completion_fact_id=specialist_earning.completion_fact_id
			   and c.id=specialist_earning.consumed_credit_id and c.appointment_id=a.id and c.state='CONSUMED'
			   and not exists (select 1 from appointment_dispute d where d.appointment_id=a.id
			     and (d.status='OPEN' or d.resolution_outcome='RELEASE_USER_CREDIT'))
			)
			""";

	private final JdbcClient jdbc;
	private final PayoutProperties properties;
	private final Clock clock;
	private final com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox audit;

	PayoutTransactions(JdbcClient jdbc, PayoutProperties properties, Clock clock,
			com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox audit) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
		this.audit = audit;
	}

	@Transactional
	PreparedPayout prepare(UUID specialistId, UUID destinationId, String key) {
		lockSpecialist(specialistId);
		var replay = jdbc.sql("select id,amount_minor,currency,status,payout_provider,destination_id from specialist_payout where specialist_account_id=:s and idempotency_key=:key for update")
				.param("s",specialistId).param("key",key).query(this::existing).optional();
		if (replay.isPresent()) {
			var p=replay.orElseThrow();
			if (!p.destinationId().equals(destinationId)) throw conflict("IDEMPOTENCY_KEY_CONFLICT");
			return observe(p);
		}
		var providerName=properties.getMode();
		assertNoSyntheticEarnings(specialistId,providerName,null);
		assertSubmissionReady(providerName);
		promoteSettledEarnings(specialistId);
		if (jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:s and requested_on=:day")
				.param("s",specialistId).param("day",java.time.LocalDate.now(clock)).query(Long.class).single()>0)
			throw conflict("PAYOUT_DAILY_LIMIT_REACHED");
		var destination=usableDestination(specialistId,destinationId,providerName,false);
		var earnings=jdbc.sql("""
				select id,specialist_amount_minor from specialist_earning where specialist_account_id=:s and status='AVAILABLE'
				and not exists(select 1 from specialist_payout_item i where i.earning_id=specialist_earning.id and i.released_at is null)
				order by earned_at,id for update
				""").param("s",specialistId).query((r,n)->new EarningAmount(r.getObject("id",UUID.class),r.getLong("specialist_amount_minor"))).list();
		var amount=earnings.stream().mapToLong(EarningAmount::amount).sum();
		if(amount<properties.getMinimumWithdrawalVnd()) throw conflict("PAYOUT_MINIMUM_NOT_REACHED");
		var id=UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_payout(id,specialist_account_id,destination_id,currency,amount_minor,payout_provider,
				status,idempotency_key,requested_on,requested_at,created_at,updated_at)
				values(:id,:s,:d,'VND',:amount,:provider,'PROCESSING',:key,:day,:now,:now,:now)
				""").param("id",id).param("s",specialistId).param("d",destinationId).param("amount",amount)
				.param("provider",providerName).param("key",key).param("day",java.time.LocalDate.now(clock))
				.param("now",databaseInstant(clock.instant())).update();
		for(var e:earnings) jdbc.sql("insert into specialist_payout_item(payout_id,earning_id,amount_minor) values(:p,:e,:a)")
				.param("p",id).param("e",e.id()).param("a",e.amount()).update();
		reserveItems(id);
		var prepared=newAttempt(new ExistingPayout(id,amount,"VND","PROCESSING",providerName,destinationId),destination,1);
		insertHistory(id,null,"PROCESSING","SPECIALIST_REQUESTED",clock.instant());
		return prepared;
	}

	@Transactional
	PreparedPayout retry(UUID adminId,UUID payoutId,UUID expectedAttemptId,String key,UUID correlationId) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))").param("key","payout-retry:"+adminId+":"+key).query((r,n)->true).single();
		var replay=jdbc.sql("select payout_id,expected_attempt_id,result_attempt_id from payout_retry_command where actor_account_id=:actor and idempotency_key=:key")
				.param("actor",adminId).param("key",key).query().listOfRows().stream().findFirst();
		if(replay.isPresent()) {
			var c=replay.orElseThrow();
			if(!payoutId.equals(c.get("payout_id"))||!expectedAttemptId.equals(c.get("expected_attempt_id"))) throw conflict("IDEMPOTENCY_KEY_CONFLICT");
			return new PreparedPayout(payoutId,(UUID)c.get("result_attempt_id"),0,"VND",false,null,null);
		}
		var owner=jdbc.sql("select specialist_account_id from specialist_payout where id=:id").param("id",payoutId)
				.query(UUID.class).optional().orElseThrow(()->notFound("PAYOUT_NOT_FOUND"));
		lockSpecialist(owner);
		promoteSettledEarnings(owner);
		var p=jdbc.sql("select id,amount_minor,currency,status,payout_provider,destination_id from specialist_payout where id=:id for update")
				.param("id",payoutId).query(this::existing).single();
		var latest=jdbc.sql("select id,status,attempt_number,reconciliation_status from specialist_payout_attempt where payout_id=:id order by attempt_number desc limit 1 for update")
				.param("id",payoutId).query().singleRow();
		if(jdbc.sql("select closed_at is not null from specialist_payout where id=:id").param("id",payoutId).query(Boolean.class).single()
				||!"FAILED".equals(p.status())||!expectedAttemptId.equals(latest.get("id"))||!"FAILED".equals(latest.get("status"))
				||!"COMPLETE".equals(latest.get("reconciliation_status"))) throw conflict("PAYOUT_RETRY_NOT_AVAILABLE");
		var d=usableDestination(owner,p.destinationId(),p.provider(),true);
		var items=jdbc.sql("""
				select id,status,specialist_amount_minor,currency,settlement_available_at,
				("""+ ELIGIBLE_SOURCE + """
				) eligible,
				(select i.amount_minor from specialist_payout_item i where i.earning_id=specialist_earning.id and i.payout_id=:p and i.released_at is null) frozen_amount
				from specialist_earning where id in(select earning_id from specialist_payout_item where payout_id=:p and released_at is null)
				order by id for update
				""").param("p",payoutId).query().listOfRows();
		long total=0;
		for(var item:items) {
			long amount=((Number)item.get("specialist_amount_minor")).longValue();
			var availableAt=((java.sql.Timestamp)item.get("settlement_available_at")).toInstant();
			if(!"AVAILABLE".equals(item.get("status"))||!Boolean.TRUE.equals(item.get("eligible"))||!p.currency().equals(item.get("currency"))
					||availableAt.isAfter(clock.instant())||amount!=((Number)item.get("frozen_amount")).longValue()) throw conflict("PAYOUT_RETRY_NOT_AVAILABLE");
			total=Math.addExact(total,amount);
		}
		if(items.isEmpty()||total!=p.amount()) throw conflict("PAYOUT_RETRY_NOT_AVAILABLE");
		assertNoSyntheticEarnings(owner,p.provider(),payoutId);
		assertSubmissionReady(p.provider());
		reserveItems(payoutId);
		var prepared=newAttempt(p,d,((Number)latest.get("attempt_number")).intValue()+1);
		jdbc.sql("update specialist_payout set status='PROCESSING',last_failure_code=null,completed_at=null,updated_at=:now,version=version+1 where id=:id")
				.param("id",payoutId).param("now",databaseInstant(clock.instant())).update();
		jdbc.sql("""
				insert into payout_retry_command(id,actor_account_id,idempotency_key,payout_id,expected_attempt_id,result_attempt_id,created_at)
				values(:id,:actor,:key,:p,:expected,:result,:now)
				""").param("id",UUID.randomUUID()).param("actor",adminId).param("key",key).param("p",payoutId)
				.param("expected",expectedAttemptId).param("result",prepared.attemptId()).param("now",databaseInstant(clock.instant())).update();
		insertHistory(payoutId,"FAILED","PROCESSING","ADMIN_RETRIED",clock.instant());
		audit.record("audit:payout:retry:"+prepared.attemptId(),new com.mentalbridge.consultation.specialist.ConsultationAdminAuditEvent(
				UUID.randomUUID(),"consultation.payout.retried",clock.instant(),"consultation-service","1.0","CONSULTATION","SPECIALIST_REVIEW",
				adminId,"ADMIN","PAYOUT_RETRIED","SUCCEEDED","DEFINITE_FAILURE",correlationId,owner,"account:"+owner));
		return prepared;
	}

	@Transactional
	void closeFailed(UUID adminId, UUID payoutId, UUID expectedAttemptId, String key, UUID correlationId) {
		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
				.param("key", "payout-close:" + adminId + ":" + key).query((r,n) -> true).single();
		var replay = jdbc.sql("select payout_id,expected_attempt_id from payout_close_command where actor_account_id=:actor and idempotency_key=:key")
				.param("actor",adminId).param("key",key).query().listOfRows().stream().findFirst();
		if (replay.isPresent()) {
			var command = replay.orElseThrow();
			if (!payoutId.equals(command.get("payout_id")) || !expectedAttemptId.equals(command.get("expected_attempt_id")))
				throw conflict("IDEMPOTENCY_KEY_CONFLICT");
			return;
		}
		var owner = jdbc.sql("select specialist_account_id from specialist_payout where id=:id").param("id",payoutId)
				.query(UUID.class).optional().orElseThrow(() -> notFound("PAYOUT_NOT_FOUND"));
		jdbc.sql("select account_id from specialist_profile where account_id=:id for update").param("id",owner).query(UUID.class).single();
		var payout = jdbc.sql("select status,closed_at from specialist_payout where id=:id for update").param("id",payoutId).query().singleRow();
		var latest = jdbc.sql("select id,status,reconciliation_status from specialist_payout_attempt where payout_id=:id order by attempt_number desc limit 1 for update")
				.param("id",payoutId).query().singleRow();
		if (!"FAILED".equals(payout.get("status")) || payout.get("closed_at") != null
				|| !expectedAttemptId.equals(latest.get("id")) || !"FAILED".equals(latest.get("status"))
				|| !"COMPLETE".equals(latest.get("reconciliation_status"))) throw conflict("PAYOUT_CLOSE_NOT_AVAILABLE");
		var items = jdbc.sql("select e.status from specialist_earning e join specialist_payout_item i on i.earning_id=e.id where i.payout_id=:id and i.released_at is null order by e.id for update of e,i")
				.param("id",payoutId).query(String.class).list();
		if (items.isEmpty() || items.stream().anyMatch(status -> !"AVAILABLE".equals(status) && !"PENDING_SETTLEMENT".equals(status)))
			throw conflict("PAYOUT_CLOSE_NOT_AVAILABLE");
		var now = databaseInstant(clock.instant());
		jdbc.sql("update specialist_payout set closed_at=:now,closed_by=:actor,updated_at=:now,version=version+1 where id=:id")
				.param("id",payoutId).param("actor",adminId).param("now",now).update();
		jdbc.sql("update specialist_payout_item set released_at=:now where payout_id=:id and released_at is null")
				.param("id",payoutId).param("now",now).update();
		jdbc.sql("insert into payout_close_command(id,actor_account_id,idempotency_key,payout_id,expected_attempt_id,reason_code,created_at) values(:id,:actor,:key,:p,:expected,'DESTINATION_UNUSABLE',:now)")
				.param("id",UUID.randomUUID()).param("actor",adminId).param("key",key).param("p",payoutId)
				.param("expected",expectedAttemptId).param("now",now).update();
		insertHistory(payoutId,"FAILED","FAILED","ADMIN_CLOSED_DESTINATION_UNUSABLE",clock.instant());
		audit.record("audit:payout:close:" + payoutId,new com.mentalbridge.consultation.specialist.ConsultationAdminAuditEvent(
				UUID.randomUUID(),"consultation.payout.closed",clock.instant(),"consultation-service","1.0","CONSULTATION","SPECIALIST_REVIEW",
				adminId,"ADMIN","PAYOUT_CLOSED","SUCCEEDED","DESTINATION_UNUSABLE",correlationId,owner,"account:"+owner));
	}

	private void lockSpecialist(UUID id) {
		var status=jdbc.sql("select approval_status from specialist_profile where account_id=:id for update").param("id",id).query(String.class).optional();
		if(status.isEmpty()||!"APPROVED".equals(status.orElseThrow())) throw new ApiException(HttpStatus.FORBIDDEN,"SPECIALIST_EARNINGS_ACCESS_DENIED","Specialist earnings are unavailable");
	}

	private void assertSubmissionReady(String name) {
		if("DISABLED".equals(properties.getMode())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"REAL_PAYOUT_DISABLED","Payout is disabled");
		if(!properties.submitReady(name)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PAYOUT_PROVIDER_UNAVAILABLE","Payout provider is unavailable");
	}

	private void assertNoSyntheticEarnings(UUID owner,String name,UUID payoutId) {
		if("FAKE".equals(name)) return;
		var demoAllowed=properties.stagingDemoEnabled() && "PAYOS".equals(name);
		var sql="""
				select count(*) from specialist_earning e left join subscription_plan_version v on v.id::text=e.plan_version
				where e.specialist_account_id=:owner
				"""+(demoAllowed?" and not ("+StagingDemoEarningSource.ELIGIBLE_EARNING+")"
						:" and (v.pricing_policy_version='sandbox-full-price-v1' or e.earning_policy_version='staging-demo-paid-v1')")
				+(payoutId==null?" and e.status in('AVAILABLE','PENDING_SETTLEMENT') and not exists(select 1 from specialist_payout_item linked where linked.earning_id=e.id and linked.released_at is null)":" and exists(select 1 from specialist_payout_item i where i.earning_id=e.id and i.payout_id=:p and i.released_at is null)");
		var q=jdbc.sql(sql).param("owner",owner);
		if(payoutId!=null) q=q.param("p",payoutId);
		if(q.query(Long.class).single()>0) throw conflict("SANDBOX_EARNINGS_REAL_PAYOUT_DISABLED");
	}

	private Destination usableDestination(UUID owner,UUID id,String providerName,boolean retry) {
		var row=jdbc.sql("select id,destination_type,destination_ciphertext,payout_provider,status,is_current from specialist_payout_destination where id=:id and specialist_account_id=:owner for update")
				.param("id",id).param("owner",owner).query().listOfRows().stream().findFirst().orElseThrow(()->retry?conflict("PAYOUT_RETRY_NOT_AVAILABLE"):notFound("PAYOUT_DESTINATION_NOT_FOUND"));
		if(!providerName.equals(row.get("payout_provider"))) throw conflict(retry?"PAYOUT_RETRY_NOT_AVAILABLE":"PAYOUT_DESTINATION_PROVIDER_MISMATCH");
		if(!Boolean.TRUE.equals(row.get("is_current"))||!"VERIFIED".equals(row.get("status"))) throw conflict(retry?"PAYOUT_RETRY_NOT_AVAILABLE":"PAYOUT_DESTINATION_CONFIRMATION_REQUIRED");
		return new Destination(id,(String)row.get("destination_type"),(String)row.get("destination_ciphertext"));
	}

	private void reserveItems(UUID id) {
		jdbc.sql("update specialist_earning set status='PROCESSING',updated_at=:now,version=version+1 where id in(select earning_id from specialist_payout_item where payout_id=:id) and status='AVAILABLE'")
				.param("id",id).param("now",databaseInstant(clock.instant())).update();
	}

	private PreparedPayout newAttempt(ExistingPayout p,Destination d,int number) {
		var id=UUID.randomUUID();
		var key="PAYOS".equals(p.provider())?UUID.randomUUID().toString():p.id()+":"+number;
		var now=clock.instant();
		jdbc.sql("""
				insert into specialist_payout_attempt(id,payout_id,payout_provider,attempt_number,provider_idempotency_key,status,
				requested_at,created_at,updated_at,reconciliation_status,next_reconciliation_at,reconciliation_started_at,reconciliation_deadline_at,reconciliation_lease_until)
				values(:id,:p,:provider,:number,:key,'PROCESSING',:now,:now,:now,'PENDING',:next,:now,:deadline,:lease)
				""").param("id",id).param("p",p.id()).param("provider",p.provider()).param("number",number).param("key",key)
				.param("now",databaseInstant(now)).param("next",databaseInstant(now.plusSeconds(5)))
				.param("deadline",databaseInstant(now.plusSeconds(86400))).param("lease",databaseInstant(now.plusSeconds(60))).update();
		return new PreparedPayout(p.id(),id,p.amount(),p.currency(),true,new PayoutProvider.Command(p.id(),id,key,p.amount(),p.currency(),d.destinationType(),d.destinationCiphertext()),p.provider());
	}

	private PreparedPayout observe(ExistingPayout p) {
		var id=jdbc.sql("select id from specialist_payout_attempt where payout_id=:id order by attempt_number desc limit 1").param("id",p.id()).query(UUID.class).single();
		return new PreparedPayout(p.id(),id,p.amount(),p.currency(),false,null,p.provider());
	}

	private ExistingPayout existing(java.sql.ResultSet r,int n) throws java.sql.SQLException {
		return new ExistingPayout(r.getObject("id",UUID.class),r.getLong("amount_minor"),r.getString("currency"),r.getString("status"),r.getString("payout_provider"),r.getObject("destination_id",UUID.class));
	}

	private ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Payout command cannot be applied"); }
	private ApiException notFound(String code) { return new ApiException(HttpStatus.NOT_FOUND,code,"Payout resource was not found"); }

	@Transactional
	void reconcile(UUID payoutId, UUID attemptId, PayoutProvider.Result result) {
		var current = jdbc.sql("select status from specialist_payout where id=:id for update")
				.param("id", payoutId).query(String.class).single();
		if (current.equals("SUCCEEDED") || current.equals("FAILED") || !latestAttempt(payoutId).equals(attemptId)) return;
		reconcileLocked(payoutId, attemptId, current, result);
	}

	@Transactional
	void reconcileMomo(MomoPayoutIpnRequest request, String payloadHash) {
		var target = jdbc.sql("""
				select p.id payout_id, p.status, p.amount_minor, a.id attempt_id
				from specialist_payout_attempt a
				join specialist_payout p on p.id=a.payout_id
				where a.payout_provider='MOMO' and a.provider_idempotency_key=:requestId
				for update of p, a
				""").param("requestId", request.requestId()).query((row, ignored) -> new MomoTarget(
				row.getObject("payout_id", UUID.class), row.getObject("attempt_id", UUID.class),
				row.getString("status"), row.getLong("amount_minor"))).optional()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MOMO_PAYOUT_NOT_FOUND",
						"MoMo payout callback does not match a payout"));
		if (!target.payoutId().toString().equals(request.orderId()) || target.amount() != request.amount()) {
			throw new ApiException(HttpStatus.CONFLICT, "MOMO_PAYOUT_CALLBACK_MISMATCH",
					"MoMo payout callback does not match the expected payout");
		}
		var eventId = request.partnerCode() + ":" + request.requestId() + ":" + request.transId()
				+ ":" + request.resultCode();
		var inserted = jdbc.sql("""
				insert into payout_provider_event (
				 id, payout_attempt_id, payout_provider, provider_event_id, event_type,
				 payload_sha256, processing_status, received_at
				) values (:id, :attemptId, 'MOMO', :eventId, 'PAYOUT_IPN', :hash, 'RECEIVED', :now)
				on conflict (payout_provider, provider_event_id) do nothing returning id
				""").param("id", UUID.randomUUID()).param("attemptId", target.attemptId()).param("eventId", eventId)
				.param("hash", payloadHash).param("now", databaseInstant(clock.instant())).query(UUID.class).optional();
		if (inserted.isEmpty()) return;
		var status = request.resultCode() == 0 ? "SUCCEEDED"
				: request.resultCode() == 7000 || request.resultCode() == 7002 ? "PROCESSING" : "FAILED";
		if (!target.status().equals("SUCCEEDED") && !target.status().equals("FAILED")
				&& latestAttempt(target.payoutId()).equals(target.attemptId())) {
			reconcileLocked(target.payoutId(), target.attemptId(), target.status(),
					new PayoutProvider.Result(status, request.transId() == 0 ? null : Long.toString(request.transId()),
							status.equals("FAILED") ? "MOMO_" + request.resultCode() : null));
		}
		jdbc.sql("update payout_provider_event set processing_status='PROCESSED', processed_at=:now where id=:id")
				.param("now", databaseInstant(clock.instant())).param("id", inserted.orElseThrow()).update();
	}

	private UUID latestAttempt(UUID payoutId) {
		return jdbc.sql("select id from specialist_payout_attempt where payout_id=:id order by attempt_number desc limit 1")
				.param("id", payoutId).query(UUID.class).single();
	}

	@Transactional
	Poll claimPoll() {
		var rows = jdbc.sql("""
				select p.id payout_id,p.status,p.amount_minor,p.currency,a.id attempt_id,a.provider_idempotency_key,
				 a.provider_payout_reference,a.reconciliation_deadline_at,d.destination_type,d.destination_ciphertext
				from specialist_payout p join specialist_payout_attempt a on a.payout_id=p.id
				join specialist_payout_destination d on d.id=p.destination_id
				where a.payout_provider='PAYOS' and p.status in ('PENDING','PROCESSING','UNKNOWN')
				 and a.attempt_number=(select max(x.attempt_number) from specialist_payout_attempt x where x.payout_id=p.id)
				 and a.reconciliation_status in ('PENDING','POLLING')
				 and (a.next_reconciliation_at<=:now or a.reconciliation_deadline_at<=:now)
				 and (a.reconciliation_lease_until is null or a.reconciliation_lease_until<=:now)
				order by a.next_reconciliation_at,a.id limit 1 for update of p,a skip locked
				""").param("now",databaseInstant(clock.instant())).query().listOfRows();
		if (rows.isEmpty()) return null;
		var r = rows.getFirst();
		var payoutId = (UUID)r.get("payout_id");
		var attemptId = (UUID)r.get("attempt_id");
		var deadline = ((java.sql.Timestamp)r.get("reconciliation_deadline_at")).toInstant();
		if (!deadline.isAfter(clock.instant())) {
			reconcileLocked(payoutId,attemptId,(String)r.get("status"),new PayoutProvider.Result("UNKNOWN",
					(String)r.get("provider_payout_reference"),"PAYOUT_RECONCILIATION_OVERDUE"));
			return new Poll(null,null,null);
		}
		var token = UUID.randomUUID();
		jdbc.sql("""
				update specialist_payout_attempt set reconciliation_status='POLLING',reconciliation_lease_until=:lease,
				 reconciliation_lease_token=:token,reconciliation_count=reconciliation_count+1 where id=:id
				""").param("id",attemptId).param("lease",databaseInstant(clock.instant().plusSeconds(60))).param("token",token).update();
		return new Poll(payoutId,token,new PayoutProvider.Query("PAYOS",payoutId,attemptId,
				(String)r.get("provider_idempotency_key"),((Number)r.get("amount_minor")).longValue(),
				(String)r.get("currency"),(String)r.get("destination_type"),(String)r.get("destination_ciphertext"),
				(String)r.get("provider_payout_reference")));
	}

	@Transactional
	void finishPoll(Poll poll,PayoutProvider.Result result) {
		var status=jdbc.sql("select status from specialist_payout where id=:id for update")
				.param("id",poll.payoutId()).query(String.class).single();
		if ("SUCCEEDED".equals(status)||"FAILED".equals(status)||!latestAttempt(poll.payoutId()).equals(poll.query().attemptId())) return;
		var lease=jdbc.sql("select reconciliation_lease_token from specialist_payout_attempt where id=:id for update")
				.param("id",poll.query().attemptId()).query(UUID.class).optional();
		if (lease.isEmpty()||!poll.leaseToken().equals(lease.orElseThrow())) return;
		reconcileLocked(poll.payoutId(),poll.query().attemptId(),status,result);
	}

	private void reconcileLocked(UUID payoutId,UUID attemptId,String current,PayoutProvider.Result supplied) {
		var now=clock.instant();
		var attempt=jdbc.sql("select * from specialist_payout_attempt where id=:id and payout_id=:p for update")
				.param("id",attemptId).param("p",payoutId).query().singleRow();
		var oldReference=(String)attempt.get("provider_payout_reference");
		var oldChild=(String)attempt.get("provider_transfer_reference");
		var result=supplied;
		if ((oldReference!=null && result.providerReference()!=null && !oldReference.equals(result.providerReference()))
				|| (oldChild!=null && result.childReference()!=null && !oldChild.equals(result.childReference()))) {
			result=new PayoutProvider.Result("UNKNOWN",oldReference,"PAYOUT_SUBMISSION_UNKNOWN",oldChild);
		}
		var terminal="SUCCEEDED".equals(result.status())||"FAILED".equals(result.status());
		var deadlineValue=(java.sql.Timestamp)attempt.get("reconciliation_deadline_at");
		var deadline=deadlineValue==null?now.plusSeconds(86400):deadlineValue.toInstant();
		var overdue=!terminal && ("PAYOUT_RECONCILIATION_OVERDUE".equals(result.failureCode())||!deadline.isAfter(now));
		if (overdue) result=new PayoutProvider.Result("UNKNOWN",result.providerReference(),"PAYOUT_RECONCILIATION_OVERDUE",result.childReference());
		var reconciliation=terminal?"COMPLETE":overdue?"NEEDS_REVIEW":"POLLING";
		var count=((Number)attempt.get("reconciliation_count")).intValue();
		var next=now.plusSeconds(new long[] {5, 15, 30, 60, 120, 300}[Math.min(count,5)]);
		if (next.isAfter(deadline)) next=deadline;
		var terminalAt=terminal?databaseInstant(now):null;
		jdbc.sql("""
				update specialist_payout_attempt set status=:status,
				 provider_payout_reference=coalesce(provider_payout_reference,:ref),provider_transfer_reference=coalesce(provider_transfer_reference,:child),
				 provider_confirmed_at=case when :status='SUCCEEDED' then cast(:terminal as timestamptz) else null end,
				 failed_at=case when :status='FAILED' then cast(:terminal as timestamptz) else null end,failure_code=:code,
				 reconciliation_status=:reconciliation,next_reconciliation_at=:next,review_required_at=:review,
				 reconciliation_lease_until=null,reconciliation_lease_token=null,updated_at=:now,version=version+1 where id=:id
				""").param("status",result.status()).param("ref",result.providerReference()).param("child",result.childReference())
				.param("terminal",terminalAt).param("code",result.failureCode()).param("reconciliation",reconciliation)
				.param("next",terminal||overdue?null:databaseInstant(next)).param("review",overdue?databaseInstant(now):null)
				.param("now",databaseInstant(now)).param("id",attemptId).update();
		jdbc.sql("""
				update specialist_payout set status=:status,completed_at=case when :status='SUCCEEDED' then cast(:terminal as timestamptz) else null end,
				 last_failure_code=:code,updated_at=:now,version=version+1 where id=:id
				""").param("status",result.status()).param("terminal",terminalAt).param("code",result.failureCode())
				.param("now",databaseInstant(now)).param("id",payoutId).update();
		if (terminal) jdbc.sql("""
				update specialist_earning set status=:status,paid_at=:paid,updated_at=:now,version=version+1
				where id in(select earning_id from specialist_payout_item where payout_id=:id) and status='PROCESSING'
				""").param("status","SUCCEEDED".equals(result.status())?"PAID":"AVAILABLE")
				.param("paid","SUCCEEDED".equals(result.status())?databaseInstant(now):null)
				.param("now",databaseInstant(now)).param("id",payoutId).update();
		if (!current.equals(result.status()) || !java.util.Objects.equals(attempt.get("failure_code"),result.failureCode())) {
			insertHistory(payoutId,current,result.status(),result.failureCode()==null?"PROVIDER_CONFIRMED":result.failureCode(),now);
		}
		if ("PAYOS".equals(attempt.get("payout_provider"))) {
			var eventId=UUID.randomUUID();
			var hash=new PayoutDestinationCipher(properties).fingerprint(attemptId+":"+result.status()+":"+result.providerReference()+":"+result.childReference()+":"+result.failureCode());
			jdbc.sql("""
					insert into payout_provider_event(id,payout_attempt_id,payout_provider,provider_event_id,event_type,payload_sha256,
					 processing_status,failure_code,received_at,processed_at) values(:id,:attempt,'PAYOS',:event,'PAYOUT_OBSERVATION',:hash,'PROCESSED',:code,:now,:now)
					""").param("id",eventId).param("attempt",attemptId).param("event",eventId.toString()).param("hash",hash)
					.param("code",result.failureCode()).param("now",databaseInstant(now)).update();
		}
	}

	record Poll(UUID payoutId,UUID leaseToken,PayoutProvider.Query query) { }

	@Transactional
	void promoteSettledEarnings(UUID specialistId) {
		jdbc.sql("""
				select a.id from appointment a join specialist_earning e on e.appointment_id=a.id
				where e.specialist_account_id=:specialistId and e.status in ('PENDING_SETTLEMENT', 'AVAILABLE')
				order by a.id for update of a
				""").param("specialistId", specialistId).query(UUID.class).list();
		jdbc.sql("""
				update specialist_earning set status='PENDING_SETTLEMENT', updated_at=:now, version=version+1
				where specialist_account_id=:specialistId and status='AVAILABLE' and not
				""" + ELIGIBLE_SOURCE).param("specialistId", specialistId)
				.param("now", databaseInstant(clock.instant())).update();
		jdbc.sql("""
				update specialist_earning set status='AVAILABLE', updated_at=:now, version=version+1
				where specialist_account_id=:specialistId and status='PENDING_SETTLEMENT'
				  and settlement_available_at <= :now and
				""" + ELIGIBLE_SOURCE).param("specialistId", specialistId)
				.param("now", databaseInstant(clock.instant())).update();
	}

	private void insertHistory(UUID payoutId, String from, String to, String reason, java.time.Instant now) {
		jdbc.sql("""
				insert into specialist_payout_status_history (id, payout_id, from_status, to_status, reason_code, changed_at)
				values (:id, :payoutId, :fromStatus, :toStatus, :reason, :now)
				""").param("id", UUID.randomUUID()).param("payoutId", payoutId).param("fromStatus", from)
				.param("toStatus", to).param("reason", reason).param("now", databaseInstant(now)).update();
	}

	private OffsetDateTime databaseInstant(java.time.Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record PreparedPayout(UUID payoutId, UUID attemptId, long amountVnd, String currency, boolean submit,
			PayoutProvider.Command command, String provider) {
	}

	private record Destination(UUID id, String destinationType, String destinationCiphertext) {
	}

	private record EarningAmount(UUID id, long amount) {
	}

	private record ExistingPayout(UUID id, long amount, String currency, String status, String provider, UUID destinationId) {
	}

	private record MomoTarget(UUID payoutId, UUID attemptId, String status, long amount) {
	}
}
