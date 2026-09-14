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
	void migrationCreatesTheSpecialistProfileApprovalTablesInPublic() {
		var tables = jdbc.sql("select table_name from information_schema.tables where table_schema='public'")
				.query(String.class).list();
		assertThat(tables).contains("specialist_profile", "specialist_profile_support_area",
				"specialist_profile_language", "specialist_profile_status_history");
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
