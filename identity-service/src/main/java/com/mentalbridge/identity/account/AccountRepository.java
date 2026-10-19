package com.mentalbridge.identity.account;

import java.util.Optional;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<AccountEntity, UUID>, JpaSpecificationExecutor<AccountEntity> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AccountEntity account where account.email = :email")
	Optional<AccountEntity> findByEmailForUpdate(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from AccountEntity account where account.id = :id")
	Optional<AccountEntity> findByIdForUpdate(@Param("id") UUID id);

	boolean existsByIdAndStatusNot(UUID id, AccountStatus status);

	@Query("select account.id from AccountEntity account where account.id in :ids and account.status = :status")
	List<UUID> findIdsByIdInAndStatus(@Param("ids") Collection<UUID> ids, @Param("status") AccountStatus status);

	long countByStatus(AccountStatus status);

	long countByRole(RoleCode role);

	long countByRoleAndStatusNot(RoleCode role, AccountStatus status);

	@Query("""
			select account.role, account.status, count(account)
			from AccountEntity account
			where account.status <> :deletedStatus
			group by account.role, account.status
			""")
	List<Object[]> aggregateCurrentAccounts(@Param("deletedStatus") AccountStatus deletedStatus);

	@Query("""
			select account.role, account.status, count(account)
			from AccountEntity account
			where account.createdAt >= :periodStart and account.createdAt < :periodEndExclusive
			group by account.role, account.status
			order by account.role, account.status
			""")
	List<Object[]> aggregateCreatedAccounts(@Param("periodStart") Instant periodStart,
			@Param("periodEndExclusive") Instant periodEndExclusive);

	@Query("""
			select count(account) from AccountEntity account
			where account.role = com.mentalbridge.identity.account.RoleCode.USER
			  and account.createdAt >= :from and account.createdAt < :to
			""")
	long countUserRegistrationsBetween(@Param("from") Instant from, @Param("to") Instant to);

	@Query("""
			select count(account) from AccountEntity account
			where account.role = com.mentalbridge.identity.account.RoleCode.USER
			  and account.createdAt >= :from and account.createdAt < :to
			  and account.emailVerifiedAt is not null and account.emailVerifiedAt < :to
			""")
	long countUserRegistrationCohortActivatedBy(@Param("from") Instant from, @Param("to") Instant to);

}
