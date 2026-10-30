package com.mentalbridge.identity.retention;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RetentionExecutionRecordRepository extends JpaRepository<RetentionExecutionRecordEntity, UUID> {
	Optional<RetentionExecutionRecordEntity> findFirstByPolicyIdOrderByStartedAtDesc(String policyId);

	Optional<RetentionExecutionRecordEntity> findFirstByPolicyIdAndJobStatusOrderByStartedAtDesc(
			String policyId, String jobStatus);

	List<RetentionExecutionRecordEntity> findTop3ByPolicyIdOrderByStartedAtDesc(String policyId);

	@Query(value = "select pg_try_advisory_xact_lock(hashtext(:policyId))", nativeQuery = true)
	boolean tryAcquirePolicyExecutionLock(@Param("policyId") String policyId);
}

