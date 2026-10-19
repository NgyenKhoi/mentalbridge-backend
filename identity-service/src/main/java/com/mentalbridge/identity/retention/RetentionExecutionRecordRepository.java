package com.mentalbridge.identity.retention;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RetentionExecutionRecordRepository extends JpaRepository<RetentionExecutionRecordEntity, UUID> {
	Optional<RetentionExecutionRecordEntity> findFirstByPolicyIdOrderByStartedAtDesc(String policyId);
}

