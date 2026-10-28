package com.mentalbridge.identity.retention;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface DeletionRequestRepository extends JpaRepository<DeletionRequestEntity, UUID>, JpaSpecificationExecutor<DeletionRequestEntity> {
	Optional<DeletionRequestEntity> findByAccountId(UUID accountId);
	List<DeletionRequestEntity> findByStatus(DeletionRequestStatus status);
}

