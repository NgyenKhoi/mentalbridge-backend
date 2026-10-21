package com.mentalbridge.identity.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import com.mentalbridge.identity.account.SecurityAuditEventEntity;
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
	private final RetentionAuditLogger auditLogger;
	private final PlatformReportProcessor reportProcessor;
	private final Clock clock;
	private final RetentionExecutionRecorder executionRecorder;

	private final Map<String, PolicyMetadata> policyDefinitions = new LinkedHashMap<>();

	public AdminRetentionGovernanceService(RetentionExecutionRecordRepository executionRecords,
			DeletionRequestRepository deletionRequests, AccountRepository accounts,
			AuthenticationPersistence authentication, RetentionAuditLogger auditLogger,
			PlatformReportProcessor reportProcessor, Clock clock,
			RetentionExecutionRecorder executionRecorder) {
		this.executionRecords = executionRecords;
		this.deletionRequests = deletionRequests;
		this.accounts = accounts;
		this.authentication = authentication;
		this.auditLogger = auditLogger;
		this.reportProcessor = reportProcessor;
		this.clock = clock;
		this.executionRecorder = executionRecorder;
		initPolicyDefinitions();
	}

	private void initPolicyDefinitions() {
		policyDefinitions.put("MB-IDENTITY-REPORTING-001", new PolicyMetadata(
				"MB-IDENTITY-REPORTING-001",
				"Chính sách bảo lưu tệp báo cáo tổng hợp",
				"1.0",
				RetentionOwnerService.IDENTITY,
				RetentionPolicyStatus.APPROVED,
				"Tệp báo cáo tổng hợp hoạt động tài khoản được lưu trữ an toàn trong 30 ngày trước khi dọn dẹp tự động.",
				30,
				"REPORT_ARTIFACT_RETENTION_SWEEP",
				RetentionJobStatus.ACTIVE,
				true,
				false,
				false,
				null
		));
		policyDefinitions.put("MB-IDENTITY-AUDIT-001", new PolicyMetadata(
				"MB-IDENTITY-AUDIT-001",
				"Chính sách bảo lưu nhật ký kiểm toán Identity",
				"1.0",
				RetentionOwnerService.IDENTITY,
				RetentionPolicyStatus.APPROVED,
				"Nhật ký kiểm toán tối thiểu hóa danh tính được bảo lưu bất biến trong 365 ngày (MB-587). Không hỗ trợ quét xóa thủ công từ quản trị.",
				365,
				"IDENTITY_AUDIT_RETENTION",
				RetentionJobStatus.PARTIALLY_SUPPORTED,
				false,
				false,
				false,
				"Nhật ký kiểm toán được bảo lưu bất biến trong 365 ngày theo chính sách MB-587; không hỗ trợ quét xóa sớm hoặc xóa thủ công từ giao diện quản trị."
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
				RetentionJobStatus.PARTIALLY_SUPPORTED,
				false,
				true,
				true,
				"Identity không có quyền can thiệp cơ sở dữ liệu Care Service. Quá trình dọn dẹp do Care Service tự thực thi độc lập."
		));
		policyDefinitions.put("consultation-journey-v1", new PolicyMetadata(
				"consultation-journey-v1",
				"Chính sách bảo lưu phiên tư vấn & đối soát giao dịch",
				"1.0-scope-v2",
				RetentionOwnerService.CONSULTATION,
				RetentionPolicyStatus.APPROVED,
				"Metadata phòng LiveKit lưu trữ 90 ngày (ADR 0039); Bằng chứng tài chính và giao dịch được bảo lưu theo quy định kế toán; Giữ lại vô thời hạn nếu có tranh chấp",
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

			List<RetentionExecutionRecordEntity> recent = meta.automatedExecutionSupported()
					? executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc(meta.policyId())
					: List.of();

			boolean hasRunning = (lastExec != null && "RUNNING".equals(lastExec.jobStatus()))
					|| recent.stream().anyMatch(r -> "RUNNING".equals(r.jobStatus()));

			boolean lockLookupSuccessful = false;
			boolean lockAcquired = false;
			if (hasRunning) {
				try {
					lockAcquired = executionRecords.tryAcquirePolicyExecutionLock(meta.policyId());
					lockLookupSuccessful = true;
				} catch (Exception ignored) {
				}
			}

			boolean orphanedRunningDetected = lockLookupSuccessful && lockAcquired;

			if (orphanedRunningDetected && lastExec != null && "RUNNING".equals(lastExec.jobStatus())) {
				lastExec = new RetentionExecutionResponse(
						lastExec.executionId(),
						lastExec.policyId(),
						lastExec.ownerService(),
						lastExec.jobName(),
						"STALE",
						lastExec.startedAt(),
						lastExec.completedAt(),
						lastExec.itemsEvaluated(),
						lastExec.recordsPurged(),
						lastExec.tombstonesCreated(),
						lastExec.holdsHonored(),
						"PRIOR_EXECUTION_STALE"
				);
			}

			RetentionJobStatus effectiveJobStatus = meta.jobStatus();
			if (meta.automatedExecutionSupported() && lastExec != null) {
				boolean hasUnresolvedRunning = !orphanedRunningDetected
						&& ((lastExec != null && "RUNNING".equals(lastExec.jobStatus()))
								|| recent.stream().anyMatch(r -> "RUNNING".equals(r.jobStatus())));

				if (retryExhausted(recent, orphanedRunningDetected)) {
					effectiveJobStatus = RetentionJobStatus.FAILED;
				} else if (hasUnresolvedRunning) {
					effectiveJobStatus = RetentionJobStatus.RUNNING;
				} else if ("STALE".equals(lastExec.jobStatus())) {
					effectiveJobStatus = RetentionJobStatus.STALE;
				}
			}

			result.add(new RetentionPolicyResponse(
					meta.policyId(),
					meta.name(),
					meta.version(),
					meta.ownerService(),
					meta.status(),
					meta.description(),
					meta.retentionDays(),
					meta.jobName(),
					effectiveJobStatus,
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
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "DENIED",
					"CAPABILITY_UNAVAILABLE", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new UnsupportedRetentionCapabilityException(policyId, meta.unsupportedCapabilityNotice());
		}

		boolean lockAcquired;
		try {
			lockAcquired = executionRecords.tryAcquirePolicyExecutionLock(policyId);
		} catch (Exception ex) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "DENIED",
					"LOCK_ACQUISITION_FAILED", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Không thể xác thực khóa thực thi độc quyền cho chính sách " + policyId + ". Vui lòng thử lại sau.", ex);
		}

		if (!lockAcquired) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "DENIED",
					"CONCURRENT_SWEEP_RUNNING", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Tiến trình dọn dẹp cho chính sách " + policyId + " đang được thực thi (RUNNING).");
		}

		var priorRunningResult = executionRecords.findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc(policyId, "RUNNING");
		if (priorRunningResult.isPresent()) {
			var priorRunning = priorRunningResult.get();
			RetentionExecutionRecordEntity staleRecord = new RetentionExecutionRecordEntity(
					priorRunning.id(),
					priorRunning.policyId(),
					priorRunning.ownerService(),
					priorRunning.jobName(),
					"STALE",
					priorRunning.startedAt(),
					now,
					priorRunning.itemsEvaluated(),
					priorRunning.recordsPurged(),
					priorRunning.tombstonesCreated(),
					priorRunning.holdsHonored(),
					"PRIOR_EXECUTION_STALE",
					priorRunning.createdAt()
			);
			executionRecorder.recordStale(staleRecord);
		}

		List<RetentionExecutionRecordEntity> recentExecutions = executionRecords.findTop3ByPolicyIdOrderByStartedAtDesc(policyId);
		if (retryExhausted(recentExecutions, true)) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "DENIED",
					"RETRY_EXHAUSTED", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"Tiến trình dọn dẹp đã thất bại liên tiếp 3 lần. Cần can thiệp thủ công (MANUAL_ESCALATION_REQUIRED) trước khi thực thi lại.");
		}

		UUID executionId = UUID.randomUUID();
		RetentionExecutionRecordEntity runningRecord = new RetentionExecutionRecordEntity(
				executionId,
				policyId,
				meta.ownerService().name(),
				meta.jobName(),
				"RUNNING",
				now,
				null,
				0,
				0,
				0,
				0,
				null,
				now
		);
		executionRecorder.recordStart(runningRecord);

		int evaluated = 0;
		int purged = 0;
		RetentionExecutionRecordEntity record;
		try {
			if (reportProcessor != null && "MB-IDENTITY-REPORTING-001".equals(policyId)) {
				var purgeMetrics = reportProcessor.purgeExpiredArtifactsWithMetrics();
				evaluated = (int) purgeMetrics.itemsEvaluated();
				purged = (int) purgeMetrics.recordsPurged();
			}

			record = new RetentionExecutionRecordEntity(
					executionId,
					policyId,
					meta.ownerService().name(),
					meta.jobName(),
					"SUCCEEDED",
					now,
					clock.instant(),
					evaluated,
					purged,
					0,
					0,
					null,
					now
			);
			executionRecorder.recordSuccess(record);

			auditLogger.recordSuccess(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "SUCCEEDED",
					"PURGE_COMPLETED", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
		} catch (Exception ex) {
			record = new RetentionExecutionRecordEntity(
					executionId,
					policyId,
					meta.ownerService().name(),
					meta.jobName(),
					"FAILED",
					now,
					clock.instant(),
					evaluated,
					purged,
					0,
					0,
					"SWEEP_EXECUTION_FAILED",
					now
			);
			executionRecorder.recordFailure(record);

			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), null, actorId, "RETENTION_SWEEP_TRIGGERED", "FAILED",
					"SWEEP_EXECUTION_FAILED", effectiveCorrelation, null,
					meta.ownerService().name(), "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Thực thi dọn dẹp thất bại do lỗi hệ thống nội bộ.", ex);
		}

		return toExecutionResponse(record);
	}

	@Transactional(readOnly = true)
	public DeletionRequestPage getDeletionRequests(DeletionRequestStatus status, DeletionHoldType holdType, Integer requestedLimit) {
		return getDeletionRequests(status, holdType, requestedLimit, 0);
	}

	@Transactional(readOnly = true)
	public DeletionRequestPage getDeletionRequests(DeletionRequestStatus status, DeletionHoldType holdType, Integer requestedLimit, Integer requestedPage) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit phải từ 1 đến 100");
		}
		int pageNumber = requestedPage == null ? 0 : requestedPage;
		if (pageNumber < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page không được âm");
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

		Page<DeletionRequestEntity> page = deletionRequests.findAll(spec, PageRequest.of(pageNumber, limit, Sort.by(Sort.Direction.DESC, "requestedAt")));
		List<DeletionRequestResponse> items = page.getContent().stream().map(this::toDeletionResponse).toList();
		String nextCursor = page.hasNext() ? String.valueOf(pageNumber + 1) : null;
		return new DeletionRequestPage(items, page.getTotalElements(), nextCursor);
	}

	@Transactional
	public DeletionRequestResponse retryDeletion(UUID requestId, UUID actorId, UUID correlationId) {
		DeletionRequestEntity request = deletionRequests.findById(requestId)
				.orElseThrow(() -> new DeletionRequestNotFoundException(requestId));

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		if (request.status() == DeletionRequestStatus.COMPLETED) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"ALREADY_COMPLETED", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Yêu cầu xóa đã hoàn tất (COMPLETED), không thể thực hiện lại");
		}

		if (request.holdType() != DeletionHoldType.NONE) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"HOLD_ACTIVE", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new DeletionHoldActiveException(request.holdType().name() + " (" + request.holdReason() + ")");
		}

		if (request.retryCount() >= request.maxRetries()) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"RETRY_EXHAUSTED", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new DeletionRetryExhaustedException(request.maxRetries());
		}

		if (request.scheduledExecutionAt() != null && now.isBefore(request.scheduledExecutionAt())) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"EXECUTION_SCHEDULE_NOT_REACHED", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Chưa đến thời điểm thực thi được phê duyệt: " + request.scheduledExecutionAt());
		}

		if (request.ownerFacts().isEmpty()) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"MISSING_OWNER_FACTS", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Yêu cầu xóa không có danh sách owner facts hợp lệ");
		}

		DeletionOwnerFactEntity identityFact = request.ownerFacts().stream()
				.filter(f -> f.ownerService() == RetentionOwnerService.IDENTITY)
				.findFirst()
				.orElse(null);

		if (identityFact == null) {
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"MISSING_IDENTITY_FACT", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Yêu cầu xóa thiếu owner fact của dịch vụ IDENTITY");
		}

		if (identityFact.status() == DeletionFactStatus.COMPLETED) {
			List<RetentionOwnerService> pendingOthers = request.ownerFacts().stream()
					.filter(f -> f.ownerService() != RetentionOwnerService.IDENTITY && f.status() != DeletionFactStatus.COMPLETED)
					.map(DeletionOwnerFactEntity::ownerService)
					.toList();
			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "DENIED",
					"EXTERNAL_OWNER_RETRY_UNSUPPORTED", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"Dịch vụ Identity đã hoàn tất xóa dữ liệu. Các dịch vụ khác (" + pendingOthers + ") chưa hỗ trợ retry tự động từ Identity coordinator.");
		}

		// Only consume retry attempt if actual work is dispatched/processed
		request.recordAttempt(now);

		AccountEntity account = accounts.findById(request.accountId()).orElse(null);
		if (account != null) {
			if (account.status() != AccountStatus.DELETED) {
				account.markDeleted(now);
				authentication.revokeAll(account.id(), "ACCOUNT_DELETED", now);
				accounts.save(account);
			}
			identityFact.markCompleted(now, "audit:identity:account-deleted:" + request.id());

			boolean hasFacts = !request.ownerFacts().isEmpty();
			boolean identityFactCompleted = request.ownerFacts().stream()
					.anyMatch(f -> f.ownerService() == RetentionOwnerService.IDENTITY && f.status() == DeletionFactStatus.COMPLETED);
			boolean allCompleted = hasFacts && identityFactCompleted && request.ownerFacts().stream()
					.allMatch(f -> f.status() == DeletionFactStatus.COMPLETED
							&& f.evidenceReference() != null
							&& !f.evidenceReference().isBlank());

			if (allCompleted) {
				request.complete(now);
			} else {
				if (request.retryCount() >= request.maxRetries()) {
					request.fail("RETRY_LIMIT_EXHAUSTED", now);
				} else {
					request.markInProgress(now);
				}
			}
			deletionRequests.save(request);

			auditLogger.recordSuccess(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "SUCCEEDED",
					allCompleted ? "DELETION_COMPLETED" : "DELETION_ATTEMPTED_IN_PROGRESS", effectiveCorrelation,
					request.subjectReferenceHash(), "IDENTITY", "DATA_RETENTION", now, now
			));
		} else {
			identityFact.markFailed(now, "ACCOUNT_NOT_FOUND");
			request.fail("ACCOUNT_NOT_FOUND", now);
			deletionRequests.save(request);

			auditLogger.recordDenied(new SecurityAuditEventEntity(
					UUID.randomUUID(), request.accountId(), actorId, "DATA_DELETION_RETRY_REQUESTED", "FAILED",
					"ACCOUNT_NOT_FOUND", effectiveCorrelation, request.subjectReferenceHash(),
					"IDENTITY", "DATA_RETENTION", now, now
			));
		}

		return toDeletionResponse(request);
	}

	@Transactional
	public DeletionRequestResponse applyHold(UUID requestId, DeletionHoldType holdType, DeletionHoldReason holdReason, UUID actorId, UUID correlationId) {
		if (holdType == null || holdType == DeletionHoldType.NONE) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Loại Hold không hợp lệ");
		}
		if (holdReason == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mã lý do Hold không hợp lệ hoặc chứa nội dung không an toàn.");
		}

		DeletionRequestEntity request = deletionRequests.findById(requestId)
				.orElseThrow(() -> new DeletionRequestNotFoundException(requestId));

		if (request.status() == DeletionRequestStatus.COMPLETED) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể áp dụng Hold lên yêu cầu xóa đã hoàn tất (COMPLETED).");
		}

		Instant now = clock.instant();
		UUID effectiveCorrelation = correlationId != null ? correlationId : UUID.randomUUID();

		request.applyHold(holdType, holdReason.name(), now);
		deletionRequests.save(request);

		auditLogger.recordSuccess(new SecurityAuditEventEntity(
				UUID.randomUUID(), request.accountId(), actorId, "DELETION_HOLD_APPLIED", "SUCCEEDED",
				holdType.name() + "_" + holdReason.name(), effectiveCorrelation,
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

		String auditReason = holdAuditReason(request.holdType(), request.holdReason());
		request.releaseHold(now);
		deletionRequests.save(request);

		auditLogger.recordSuccess(new SecurityAuditEventEntity(
				UUID.randomUUID(), request.accountId(), actorId, "DELETION_HOLD_RELEASED", "SUCCEEDED",
				auditReason, effectiveCorrelation, request.subjectReferenceHash(),
				"IDENTITY", "DATA_RETENTION", now, now
		));

		return toDeletionResponse(request);
	}

	private String holdAuditReason(DeletionHoldType holdType, String holdReason) {
		try {
			return holdType.name() + "_" + DeletionHoldReason.valueOf(holdReason).name();
		} catch (IllegalArgumentException | NullPointerException ignored) {
			return holdType.name() + "_UNRECOGNIZED_REASON";
		}
	}

	private String safeHoldReason(String holdReason) {
		if (holdReason == null) {
			return null;
		}
		try {
			return DeletionHoldReason.valueOf(holdReason).name();
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private boolean retryExhausted(List<RetentionExecutionRecordEntity> recentExecutions, boolean orphanedRunningDetected) {
		return recentExecutions.size() >= 3 && recentExecutions.stream().allMatch(record ->
				"FAILED".equals(record.jobStatus()) || "STALE".equals(record.jobStatus())
						|| (orphanedRunningDetected && "RUNNING".equals(record.jobStatus())));
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
				safeFailureCode(record.failureCode())
		);
	}

	public static final Set<String> APPROVED_FAILURE_CODES = Set.of(
			"ACCOUNT_NOT_FOUND",
			"RETRY_LIMIT_EXHAUSTED",
			"RETRY_EXHAUSTED",
			"PRIOR_EXECUTION_STALE",
			"SWEEP_EXECUTION_FAILED",
			"LOCK_ACQUISITION_FAILED",
			"CONCURRENT_SWEEP_RUNNING",
			"CAPABILITY_UNAVAILABLE",
			"ALREADY_COMPLETED",
			"HOLD_ACTIVE",
			"EXECUTION_SCHEDULE_NOT_REACHED",
			"MISSING_OWNER_FACTS",
			"MISSING_IDENTITY_FACT",
			"EXTERNAL_OWNER_RETRY_UNSUPPORTED"
	);

	public static final Set<String> APPROVED_FAILURE_REASONS = Set.of(
			"ACCOUNT_NOT_FOUND",
			"COMMUNITY_TIMEOUT",
			"LEGAL_HOLD_ACTIVE",
			"AUTH_FAILED",
			"KEY_EXPIRED",
			"INVALID_REFERENCE",
			"STATUTORY_INQUIRY",
			"PENDING_DISPUTE",
			"ACTIVE_SAFETY_REVIEW",
			"SCHEDULED_APPOINTMENT_ACTIVE",
			"USER_CONTESTED",
			"AUDIT_COMPLIANCE_HOLD"
	);

	private static final java.util.regex.Pattern APPROVED_IDENTITY_EVIDENCE_PATTERN =
			java.util.regex.Pattern.compile("^audit:identity:account-deleted:[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", java.util.regex.Pattern.CASE_INSENSITIVE);

	private String safeFailureCode(String code) {
		if (code != null) {
			String trimmed = code.trim();
			if (APPROVED_FAILURE_CODES.contains(trimmed)) {
				return trimmed;
			}
		}
		return null;
	}

	private String safeFailureReason(String reason) {
		if (reason != null) {
			String trimmed = reason.trim();
			if (APPROVED_FAILURE_REASONS.contains(trimmed)) {
				return trimmed;
			}
		}
		return null;
	}

	private String safeEvidenceReference(RetentionOwnerService ownerService, String ref) {
		if (ownerService == RetentionOwnerService.IDENTITY && ref != null) {
			String trimmed = ref.trim();
			if (APPROVED_IDENTITY_EVIDENCE_PATTERN.matcher(trimmed).matches()) {
				return trimmed;
			}
		}
		return null;
	}

	private DeletionRequestResponse toDeletionResponse(DeletionRequestEntity entity) {
		AccountEntity account = accounts.findById(entity.accountId()).orElse(null);
		AccountStatus accStatus = account != null ? account.status() : null;

		List<DeletionOwnerFactResponse> factResponses = entity.ownerFacts().stream().map(f ->
				new DeletionOwnerFactResponse(
						f.ownerService(),
						f.status(),
						f.completedAt(),
						safeEvidenceReference(f.ownerService(), f.evidenceReference()),
						safeFailureReason(f.failureReason())
				)
		).toList();

		return new DeletionRequestResponse(
				entity.id(),
				"tombstone:" + entity.subjectReferenceHash(),
				accStatus,
				entity.status(),
				entity.holdType(),
				safeHoldReason(entity.holdReason()),
				entity.retryCount(),
				entity.maxRetries(),
				entity.escalationState(),
				safeFailureCode(entity.failureCode()),
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
			DeletionHoldReason holdReason
	) {}
}
