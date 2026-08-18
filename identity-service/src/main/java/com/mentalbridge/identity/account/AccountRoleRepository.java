package com.mentalbridge.identity.account;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRoleRepository extends JpaRepository<AccountRoleEntity, AccountRoleId> {

	@Query("select role.id.roleCode from AccountRoleEntity role where role.id.accountId = :accountId order by role.id.roleCode")
	List<String> findRoleCodes(@Param("accountId") UUID accountId);

}
