package com.mentalbridge.consultation.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConsultationLiquibaseMigrationTests extends ConsultationTestProperties {

	@Autowired JdbcClient jdbc;

	@Test
	void migrationCreatesConsultationTablesInPublic() {
		var tables = jdbc.sql("select table_name from information_schema.tables where table_schema='public'")
				.query(String.class).list();
		assertThat(tables).contains("specialist_profile", "specialist_profile_support_area",
				"specialist_profile_language", "specialist_profile_status_history",
				"current_service_entitlement", "availability_slot");
	}

	@Test
	void databaseRejectsFreeStoredRowsAmbiguousDemoProvenanceAndInvalidWindows() {
		var accountId = UUID.randomUUID();
		assertThatThrownBy(() -> insertEntitlement(accountId, "FREE", "DEMO", UUID.randomUUID(), 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertEntitlement(accountId, "PLUS", "DEMO", null, 1))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertEntitlement(accountId, "PREMIUM", "DEMO", UUID.randomUUID(), -1))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertEntitlement(UUID accountId, String packageCode, String source, UUID establishedBy,
			int durationHours) {
		var start = OffsetDateTime.now();
		jdbc.sql("""
				insert into current_service_entitlement (
				    account_id, package_code, source, source_reference, established_by,
				    effective_from, effective_until, policy_version
				) values (:accountId, :packageCode, :source, 'migration-test', :establishedBy,
				    :effectiveFrom, :effectiveUntil, 'service-entitlement-v1')
				""").param("accountId", accountId).param("packageCode", packageCode).param("source", source)
				.param("establishedBy", establishedBy).param("effectiveFrom", start)
				.param("effectiveUntil", start.plusHours(durationHours)).update();
	}

	@Test
	void databaseRejectsInvalidOrOverlappingActiveAvailability() {
		var specialistId = insertPendingProfile();
		var start = OffsetDateTime.now().plusDays(30).withNano(0);
		insertSlot(specialistId, start, "IN_APP_CHAT", "migration-valid-key-0001");

		assertThatThrownBy(() -> insertSlot(specialistId, start.plusMinutes(30), "IN_APP_CHAT",
				"migration-overlap-key-01")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				insert into availability_slot (
				    id, specialist_account_id, start_at, end_at, timezone, modality,
				    idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'PHONE',
				    'migration-invalid-key-01', :now, :now)
				""").param("id", UUID.randomUUID()).param("specialist", specialistId)
				.param("start", start.plusDays(2)).param("end", start.plusDays(2).plusMinutes(45))
				.param("now", OffsetDateTime.now()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertSlot(UUID specialistId, OffsetDateTime start, String modality, String idempotencyKey) {
		jdbc.sql("""
				insert into availability_slot (
				    id, specialist_account_id, start_at, end_at, timezone, modality,
				    idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', :modality,
				    :key, :now, :now)
				""").param("id", UUID.randomUUID()).param("specialist", specialistId)
				.param("start", start).param("end", start.plusMinutes(60)).param("modality", modality)
				.param("key", idempotencyKey).param("now", OffsetDateTime.now()).update();
	}

	@Test
	void databaseRejectsUnsupportedProfileFactsAndInvalidApprovalEvidence() {
		var specialistId = insertPendingProfile();
		assertThatThrownBy(() -> jdbc.sql("""
				insert into specialist_profile_support_area (specialist_account_id, support_area)
				values (:id, 'CLINICAL_DIAGNOSIS')
				""").param("id", specialistId).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				update specialist_profile set approval_status='APPROVED', reviewed_at=:now
				where account_id=:id
				""").param("id", specialistId).param("now", OffsetDateTime.now()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertPendingProfile() {
		return jdbc.sql("""
				insert into specialist_profile (account_id, display_name, biography, years_experience, timezone)
				values (:id, 'Test specialist', 'Test biography', 2, 'Asia/Ho_Chi_Minh')
				returning account_id
				""").param("id", UUID.randomUUID()).query(UUID.class).single();
	}
}
