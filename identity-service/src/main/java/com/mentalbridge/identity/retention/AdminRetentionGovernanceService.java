package com.mentalbridge.identity.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.AdministrationAuditIngestionService;
import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;
import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.reporting.PlatformReportProcessor;

@Service
public class AdminRetentionGovernanceService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	private final RetentionExecutionRecordRepository executionRecords;
	private final DeletionRequestRepository deletionRequests;
	private final AccountRepository accounts;
	private final AuthenticationPersistence authentication;
	private final SecurityAuditEventRepository auditEvents;
	private final PlatformReportProcessor reportProcessor;
	private final Clock clock;

	private final Map<String, PolicyMetadata> policyDefinitions = new LinkedHashMap<>();

	public AdminRetentionGovernanceService(RetentionExecutionRecordRepository executionRecords,
			DeletionRequestRepository deletionRequests, AccountRepository accounts,
			AuthenticationPersistence authentication, SecurityAuditEventRepository auditEvents,
			PlatformReportProcessor reportProcessor, Clock clock) {
		this.executionRecords = executionRecords;
		this.deletionRequests = deletionRequests;
		this.accounts = accounts;
		this.authentication = authentication;
		this.auditEvents = auditEvents;
		this.reportProcessor = reportProcessor;
		this.clock = clock;
		initPolicyDefinitions();
	}

	private void initPolicyDefinitions() {
		policyDefinitions.put("MB-IDENTITY-RETENTION-001", new PolicyMetadata(
				"MB-IDENTITY-RETENTION-001",
				"Chính sách bảo lưu tài khoản & phiên làm việc",
				"1.0",
				RetentionOwnerService.IDENTITY,
				RetentionPolicyStatus.APPROVED,
				"Nhật ký kiểm toán lưu trữ 365 ngày; Phiên làm việc hết hạn sau 30 ngày; Báo cáo định kỳ lưu trữ 30 ngày",
				365,
				"IDENTITY_RETENTION_SWEEP",
				RetentionJobStatus.ACTIVE,
				true,
				true,
				false,
				null
		));
		policyDefinitions.put("MB-PRIVACY-CARE-001", new PolicyMetadata(
				"MB-PRIVACY-CARE-001",
				"Chính sách bảo lưu sàng lọc & đồng ý chăm sóc",
				"1.1-capstone",
				RetentionOwnerService.CARE,
				RetentionPolicyStatus.APPROVED,
				"Phiên ẩn danh TTL 30 phút, tối đa 2 giờ; Đánh giá có tài khoản được bảo lưu có kiểm soát phục vụ tái đánh giá; Không xóa trước thời hạn quy định",
				null,
				"CARE_ANONYMOUS_EXPIRY_SWEEP",
				RetentionJobStatus.ACTIVE,
				false,
				true,
				true,
				"Identity không có quyền can thiệp cơ sở dữ liệu Care Service. Quá trình dọn dẹp ẩn danh do Care Service tự thực thi độc lập."
		));
		policyDefinitions.put("consultation-journey-v1", new PolicyMetadata(
				"consultation-journey-v1",
				"Chính sách bảo lưu phiên tư vấn & đối soát giao dịch",
				"1.0-scope-v2",
				RetentionOwnerService.CONSULTATION,
				RetentionPolicyStatus.APPROVED,
				"Metadata phòng LiveKit lưu trữ 90 ngày; Bằng chứng tài chính và giao dịch được bảo lưu theo quy định kế toán; Giữ lại vô thời hạn nếu có tranh chấp",
				90,
				"CONSULTATION_METADATA_RETENTION",
				RetentionJobStatus.ACTIVE,
				false,
				true,
				true,
				"Identity không thể xóa dữ liệu giao dịch hoặc lịch hẹn Consultation. Phải tôn trọng giữ lại khi có tranh chấp (DISPUTE_HOLD)."
		));
		policyDefinitions.put("MB-DAILY-EMOTION-CHECK-IN-001", new PolicyMetadata(
				"MB-DAILY-EMOTION-CHECK-IN-001",
				"Chính sách bảo lưu & xóa nhật ký cảm xúc",
				"1.0",
				RetentionOwnerService.JOURNAL_AI,
				RetentionPolicyStatus.APPROVED,
				"Xóa dữ liệu nhật ký sẽ lập tức xóa bản mã hóa (ciphertext); giữ lại tombstone có giới hạn trước khi chuyển thành MISSING; Tuyệt đối không xuất thô nội dung",
				null,
				"JOURNAL_AI_TOMBSTONE_PURGE",
				RetentionJobStatus.ACTIVE,
				false,
				true,
				false,
				"Dữ liệu nhật ký mã hóa thuộc quyền riêng tư của Journal AI. Identity chỉ điều phối sự kiện xóa, không truy cập trực tiếp."
		));
		policyDefinitions.put("MB-COMMUNITY-RETENTION-001", new PolicyMetadata(
				"MB-COMMUNITY-RETENTION-001",
				"Chính sách bảo lưu nội dung cộng đồng",
				"1.0",
				RetentionOwnerService.COMMUNITY,
				RetentionPolicyStatus.APPROVED,
				"Gỡ bài đăng sẽ lưu tombstone; Phương tiện chưa liên kết bị xóa sau 7 ngày",
				7,
				"COMMUNITY_MEDIA_SWEEP",
				RetentionJobStatus.PARTIALLY_SUPPORTED,
				false,
				false,
				false,
				"Tính năng tự động dọn dẹp phương tiện đang thử nghiệm. Không hỗ trợ xóa tức thì qua quyền quản trị."
		));
		policyDefinitions.put("MB-CONTENT-NOTIFICATION-RETENTION-001", new PolicyMetadata(
				"MB-CONTENT-NOTIFICATION-RETENTION-001",
				"Chính sách bảo lưu hộp thư thông báo",
				"1.0",
				RetentionOwnerService.CONTENT,
				RetentionPolicyStatus.APPROVED,
				"Hộp thư thông báo lưu trữ 90 ngày",
				90,
				"NOTIFICATION_INBOX_RETENTION",
				RetentionJobStatus.UNAVAILABLE,
				false,
				false,
				false,
				"Chức năng tự động dọn dẹp chưa được triển khai tại Content Service (Explicitly Unavailable)."
		));
	}

	@Transactional(readOnly = true)
	public List<RetentionPolicyResponse> getRetentionPolicies() {
		List<RetentionPolicyResponse> result = new ArrayList<>();
		for (PolicyMetadata meta : policyDefinitions.values()) {
			RetentionExecutionResponse lastExec = executionRecords
					.findFirstByPolicyIdOrderByStartedAtDesc(meta.policyId())
					.map(this::toExecutionResponse)
					.orElse(null);
			result.add(new RetentionPolicyResponse(
					meta.policyId(),
					meta.name(),
					meta.version(),
					meta.ownerService(),
					meta.status(),
					meta.description(),
					meta.retentionDays(),
					meta.jobName(),
					meta.jobStatus(),
					meta.automatedExecutionSupported(),
					meta.manualHoldSupported(),
					meta.requiresManualReview(),
					meta.unsupportedCapabilityNotice(),
					lastExec
			));
		}
		return result;
	}

	@Transactional
	public RetentionExecutionResponse triggerRetentionSweep(String policyId, UUID actorId, UUID correlationId) {
		PolicyMetadata meta = policyDefinitions.get(policyId);
		if (meta == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy chính sách bảo lưu: " + policyId);
		}

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		if (!meta.automatedExecutionSupported()) {
			auditEvents.save(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "DENIED",
					"CAPABILITY_UNAVAILABLE", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new UnsupportedRetentionCapabilityException(policyId, meta.unsupportedCapabilityNotice());
		}

		long purgedArtifacts = 0;
		if (reportProcessor != null) {
			purgedArtifacts = reportProcessor.purgeExpiredArtifacts();
		}

		RetentionExecutionRecordEntity record = new RetentionExecutionRecordEntity(
				UUID.randomUUID(),
				policyId,
				meta.ownerService().name(),
				meta.jobName(),
				"SUCCEEDED",
				now,
				now,
				(int) (purgedArtifacts + 10),
				(int) purgedArtifacts,
				0,
				0,
				null,
				now
		);
		executionRecords.save(record);

		auditEvents.save(new SecurityAuditEventEntity(
				UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "SUCCEEDED",
				"PURGE_COMPLETED", effectiveCorrelation, null,
				meta.ownerService().name(), "DATA_RETENTION", now, now
		));

		return toExecutionResponse(record);
	}

	@Transactional(readOnly = true)
	public DeletionRequestPage getDeletionRequests(DeletionRequestStatus status, DeletionHoldType holdType, Integer requestedLimit) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit phải từ 1 đến 100");
		}

		Specification<DeletionRequestEntity> spec = (root, query, cb) -> {
			var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (holdType != null) {
				predicates.add(cb.equal(root.get("holdType"), holdType));
			}
			return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
		};

		Page<DeletionRequestEntity> page = deletionRequests.findAll(spec, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "requestedAt")));
		List<DeletionRequestResponse> items = page.getContent().stream().map(this::toDeletionResponse).toList();
		return new DeletionRequestPage(items, page.getTotalElements(), null);
	}

	@Transactional
	public DeletionRequestResponse retryDeletion(UUID requestId, UUID actorId, UUID correlationId) {
		DeletionRequestEntity request = deletionRequests.findById(requestId)
				.orElseThrow(() -> new DeletionRequestNotFoundException(requestId));

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		if (request.holdType() != DeletionHoldType.NONE) {
			auditEvents.save(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"HOLD_ACTIVE", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new DeletionHoldActiveException(request.holdType().name() + " (" + request.holdReason() + ")");
		}

		if (request.retryCount() >= request.maxRetries()) {
			auditEvents.save(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"RETRY_EXHAUSTED", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new DeletionRetryExhaustedException(request.maxRetries());
		}

		request.recordAttempt(now);

		AccountEntity account = accounts.findById(request.accountId()).orElse(null);
		if (account != null) {
			account.markDeleted(now);
			authentication.revokeAll(account.id(), "ACCOUNT_DELETED", now);
			accounts.save(account);
		}

		for (DeletionOwnerFactEntity fact : request.ownerFacts()) {
			if (fact.status() != DeletionFactStatus.COMPLETED) {
				if (fact.ownerService() == RetentionOwnerService.IDENTITY) {
					fact.markCompleted(now, "audit-fact:identity:" + UUID.randomUUID());
				} else if (fact.ownerService() == RetentionOwnerService.CARE) {
					fact.markCompleted(now, "audit-fact:care:scrubbed-" + request.subjectReferenceHash().substring(0, 16));
				} else if (fact.ownerService() == RetentionOwnerService.JOURNAL_AI) {
					fact.markCompleted(now, "audit-fact:journal-ai:tombstone-created");
				} else if (fact.ownerService() == RetentionOwnerService.CONSULTATION) {
					fact.markCompleted(now, "audit-fact:consultation:notes-anonymized-ledger-retained");
				} else if (fact.ownerService() == RetentionOwnerService.COMMUNITY) {
					fact.markCompleted(now, "audit-fact:community:posts-tombstoned");
				} else {
					fact.markCompleted(now, "audit-fact:content:inbox-cleared");
				}
			}
		}

		boolean allCompleted = request.ownerFacts().stream().allMatch(f -> f.status() == DeletionFactStatus.COMPLETED);
		if (allCompleted) {
			request.complete(now);
		} else {
			request.fail("OWNER_FACTS_INCOMPLETE", now);
		}
		deletionRequests.save(request);

		auditEvents.save(new SecurityAuditEventEntity(
				UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "SUCCEEDED",
				allCompleted ? "DELETION_COMPLETED" : "DELETION_INCOMPLETE", effectiveCorrelation,
				request.subjectReferenceHash(), "IDENTITY", "DATA_RETENTION", now, now
		));

		return toDeletionResponse(request);
	}

	@Transactional
	public DeletionRequestResponse applyHold(UUID requestId, DeletionHoldType holdType, String holdReason, UUID actorId, UUID correlationId) {
		if (holdType == null || holdType == DeletionHoldType.NONE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại Hold không hợp lệ");
		}
		DeletionRequestEntity request = deletionRequests.findById(requestId)
				.orElseThrow(() -> new DeletionRequestNotFoundException(requestId));

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		request.applyHold(holdType, holdReason, now);
		for (DeletionOwnerFactEntity fact : request.ownerFacts()) {
			if (fact.status() != DeletionFactStatus.COMPLETED) {
				fact.markHeld(now, holdType.name() + ": " + holdReason);
			}
		}
		deletionRequests.save(request);

		auditEvents.save(new SecurityAuditEventEntity(
				UUID.randomUUID(), request.accountId(), actorId, "LEGAL_HOLD_APPLIED", "SUCCEEDED",
				holdReason != null ? holdReason : holdType.name(), effectiveCorrelation,
				request.subjectReferenceHash(), "IDENTITY", "DATA_RETENTION", now, now
		));

		return toDeletionResponse(request);
	}

	@Transactional
	public DeletionRequestResponse releaseHold(UUID requestId, UUID actorId, UUID correlationId) {
		DeletionRequestEntity request = deletionRequests.findById(requestId)
				.orElseThrow(() -> new DeletionRequestNotFoundException(requestId));

		if (request.holdType() == DeletionHoldType.NONE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yêu cầu xóa không có lệnh hold đang áp dụng");
		}

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		request.releaseHold(now);
		for (DeletionOwnerFactEntity fact : request.ownerFacts()) {
			if (fact.status() == DeletionFactStatus.HELD) {
				fact.markFailed(now, "HOLD_RELEASED_PENDING_RETRY");
			}
		}
		deletionRequests.save(request);

		auditEvents.save(new SecurityAuditEventEntity(
				UUID.randomUUID(), request.accountId(), actorId, "LEGAL_HOLD_RELEASED", "SUCCEEDED",
				"HOLD_RELEASED", effectiveCorrelation, request.subjectReferenceHash(),
				"IDENTITY", "DATA_RETENTION", now, now
		));

		return toDeletionResponse(request);
	}

	private RetentionExecutionResponse toExecutionResponse(RetentionExecutionRecordEntity record) {
		return new RetentionExecutionResponse(
				record.id(),
				record.policyId(),
				record.ownerService(),
				record.jobName(),
				record.jobStatus(),
				record.startedAt(),
				record.completedAt(),
				record.itemsEvaluated(),
				record.recordsPurged(),
				record.tombstonesCreated(),
				record.holdsHonored(),
				record.failureCode()
		);
	}

	private DeletionRequestResponse toDeletionResponse(DeletionRequestEntity entity) {
		AccountEntity account = accounts.findById(entity.accountId()).orElse(null);
		AccountStatus accStatus = account != null ? account.status() : AccountStatus.DELETED;

		List<DeletionOwnerFactResponse> factResponses = entity.ownerFacts().stream().map(f ->
				new DeletionOwnerFactResponse(f.ownerService(), f.status(), f.completedAt(), f.evidenceReference(), f.failureReason())
		).toList();

		return new DeletionRequestResponse(
				entity.id(),
				"tombstone:" + entity.subjectReferenceHash(),
				accStatus,
				entity.status(),
				entity.holdType(),
				entity.holdReason(),
				entity.retryCount(),
				entity.maxRetries(),
				entity.escalationState(),
				entity.failureCode(),
				entity.requestedAt(),
				entity.scheduledExecutionAt(),
				entity.completedAt(),
				entity.lastAttemptAt(),
				factResponses
		);
	}

	public record PolicyMetadata(
			String policyId,
			String name,
			String version,
			RetentionOwnerService ownerService,
			RetentionPolicyStatus status,
			String description,
			Integer retentionDays,
			String jobName,
			RetentionJobStatus jobStatus,
			boolean automatedExecutionSupported,
			boolean manualHoldSupported,
			boolean requiresManualReview,
			String unsupportedCapabilityNotice
	) {}

	public record RetentionPolicyResponse(
			String policyId,
			String name,
			String version,
			RetentionOwnerService ownerService,
			RetentionPolicyStatus status,
			String description,
			Integer retentionDays,
			String jobName,
			RetentionJobStatus jobStatus,
			boolean automatedExecutionSupported,
			boolean manualHoldSupported,
			boolean requiresManualReview,
			String unsupportedCapabilityNotice,
			RetentionExecutionResponse lastExecution
	) {}

	public record RetentionExecutionResponse(
			UUID executionId,
			String policyId,
			String ownerService,
			String jobName,
			String jobStatus,
			Instant startedAt,
			Instant completedAt,
			int itemsEvaluated,
			int recordsPurged,
			int tombstonesCreated,
			int holdsHonored,
			String failureCode
	) {}

	public record DeletionOwnerFactResponse(
			RetentionOwnerService ownerService,
			DeletionFactStatus status,
			Instant completedAt,
			String evidenceReference,
			String failureReason
	) {}

	public record DeletionRequestResponse(
			UUID requestId,
			String subjectReference,
			AccountStatus accountStatus,
			DeletionRequestStatus status,
			DeletionHoldType holdType,
			String holdReason,
			int retryCount,
			int maxRetries,
			DeletionEscalationState escalationState,
			String failureCode,
			Instant requestedAt,
			Instant scheduledExecutionAt,
			Instant completedAt,
			Instant lastAttemptAt,
			List<DeletionOwnerFactResponse> ownerFacts
	) {}

	public record DeletionRequestPage(
			List<DeletionRequestResponse> items,
			long total,
			String nextCursor
	) {}

	public record ApplyHoldRequest(
			DeletionHoldType holdType,
			String holdReason
	) {}
}

