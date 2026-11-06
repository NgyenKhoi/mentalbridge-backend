package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mentalbridge.care.reassessment.ReassessmentSelfReportService.CreateCommand;
import com.mentalbridge.care.reassessment.ReassessmentSelfReportService.ReplaceCommand;
import com.mentalbridge.care.reassessment.ReassessmentSelfReportStore.StoredSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Selection;
import com.mentalbridge.care.shared.ApiException;

class ReassessmentSelfReportServiceTests {

	private static final UUID USER = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final String KEY = "reassessment-service-test";
	private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
	private static final Period PERIOD = new Period(NOW.minusSeconds(15 * 86400), NOW.minusSeconds(86400));
	private final ReassessmentSelfReportStore reports = mock(ReassessmentSelfReportStore.class);
	private final ReassessmentSelfReportService service = new ReassessmentSelfReportService(reports,
			Clock.fixed(NOW, ZoneOffset.UTC));
	private String persistedHash;
	private Checklist persistedChecklist;

	@BeforeEach
	void storage() {
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.empty());
		when(reports.persist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> {
					persistedHash = invocation.getArgument(3);
					persistedChecklist = invocation.getArgument(8);
					return stored(persistedHash, persistedChecklist, false);
				});
	}

	@Test
	void preservesExactLegacyHashBytesWhenChecklistIsOmitted() throws Exception {
		var response = service.create(USER, KEY, new CreateCommand(PERIOD, "UNSURE", " helpful ", " "));
		assertThat(persistedHash).isEqualTo(digest(legacyValue()));
		assertThat(response.symptomChecklist()).isNull();
		assertThat(response.symptomSelectionReasons()).isNull();
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(digest(legacyValue()), null, false)));
		assertThat(service.create(USER, KEY, new CreateCommand(PERIOD, "UNSURE", "helpful", null)).selfReportId()).isEqualTo(REPORT);
		verify(reports).persist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void checklistRequestsAreDomainSeparatedFromLegacyFreeTextInBothReplayOrders() throws Exception {
		var checklist = create(selection("SLEEP_DIFFICULTY"));
		String text = "null|symptom-checklist:v1|" + ReassessmentSymptomCatalog.CATALOG_REVISION.length() + ":"
				+ ReassessmentSymptomCatalog.CATALOG_REVISION + ":SLEEP_DIFFICULTY";
		var legacy = new CreateCommand(PERIOD, "UNSURE", " helpful ", text);
		service.create(USER, KEY, checklist);
		String checklistHash = persistedHash;
		var checklistStored = stored(checklistHash, persistedChecklist, false);
		assertThat(checklistHash).isEqualTo(digest(checklistValue("SLEEP_DIFFICULTY")));
		service.create(USER, "reassessment-service-other", legacy);
		String legacyHash = persistedHash;
		assertThat(legacyHash).isEqualTo(digest(PERIOD.startAt() + "|" + PERIOD.endAt() + "|UNSURE|helpful|" + text));
		assertThat(checklistHash).isNotEqualTo(legacyHash);
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(checklistStored));
		assertCode(() -> service.create(USER, KEY, legacy), "IDEMPOTENCY_KEY_REUSED");
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(legacyHash, null, false)));
		assertCode(() -> service.create(USER, KEY, checklist), "IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void canonicalizesReorderedSelectionsBeforeHashingAndReplaysTheRecordedSource() throws Exception {
		var first = service.create(USER, KEY, create(selection("LOW_MOOD", "LOW_ENERGY")));
		assertThat(first.symptomChecklist().selectedCodes()).containsExactly("LOW_ENERGY", "LOW_MOOD");
		assertThat(persistedHash).isEqualTo(digest(checklistValue("LOW_ENERGY", "LOW_MOOD")));
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(persistedHash, persistedChecklist, false)));
		var replay = service.create(USER, KEY, create(selection("LOW_ENERGY", "LOW_MOOD")));
		assertThat(replay).isEqualTo(first);
		verify(reports).persist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void conflictsOnChangedSelectionsOrRevisionRatherThanSilentlyReusingTheSource() {
		service.create(USER, KEY, create(selection("LOW_ENERGY")));
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(persistedHash, persistedChecklist, false)));
		assertCode(() -> service.create(USER, KEY, create(selection("LOW_MOOD"))), "IDEMPOTENCY_KEY_REUSED");
		assertCode(() -> service.create(USER, KEY, create(new Selection("other-revision", List.of("LOW_ENERGY")))),
				"IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void keepsExplicitEmptyDistinctFromLegacyOmissionWithTheSameKey() {
		var empty = service.create(USER, KEY, create(selection()));
		assertThat(empty.symptomChecklist().selectedCodes()).isEmpty();
		assertThat(empty.symptomChecklist().mappingRevision()).isEqualTo(ReassessmentSymptomCatalog.MAPPING_REVISION);
		assertThat(empty.symptomSelectionReasons()).isEmpty();
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(persistedHash, persistedChecklist, false)));
		assertCode(() -> service.create(USER, KEY, create(null)), "IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void returnsAnExactRecordedReplayBeforeCheckingTheCurrentCatalogRevision() throws Exception {
		String retiredRevision = "reassessment-symptoms-retired";
		String hash = digest(checklistValue(retiredRevision, List.of()));
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(hash, null, false)));
		assertThat(service.create(USER, KEY, create(new Selection(retiredRevision, List.of()))).selfReportId()).isEqualTo(REPORT);
		verify(reports, never()).persist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void rejectsANewUnsupportedRevisionAndInvalidShapeWithoutWriting() {
		assertCode(() -> service.create(USER, KEY, create(new Selection("other-revision", List.of()))),
				"REASSESSMENT_SYMPTOM_CATALOG_UNSUPPORTED");
		assertCode(() -> service.create(USER, KEY, create(selection("LOW_ENERGY", "LOW_ENERGY"))), "VALIDATION_FAILED");
		verify(reports, never()).persist(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void omissionOnReplacePreservesChecklistAndExplicitEmptyReplacesIt() {
		var existing = stored("hash", ReassessmentSymptomCatalog.accept(ReassessmentSymptomCatalog.normalize(selection("LOW_ENERGY"))), false);
		when(reports.find(USER, REPORT)).thenReturn(Optional.of(existing));
		when(reports.replace(any(), any(), anyLong(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
			Checklist supplied = invocation.getArgument(6);
			return Optional.of(stored("hash", supplied == null ? existing.symptomChecklist() : supplied, false));
		});
		assertThat(service.replace(USER, REPORT, 0, new ReplaceCommand("UNSURE", null, null)).symptomChecklist())
				.isEqualTo(existing.symptomChecklist());
		assertThat(service.replace(USER, REPORT, 0, new ReplaceCommand("UNSURE", null, null, selection()))
				.symptomChecklist().selectedCodes()).isEmpty();
	}

	@Test
	void doesNotResurrectADeletedSourceOnCreateReplayOrReplace() {
		service.create(USER, KEY, create(selection("LOW_ENERGY")));
		when(reports.findByRequest(USER, KEY)).thenReturn(Optional.of(stored(persistedHash, null, true)));
		assertCode(() -> service.create(USER, KEY, create(selection("LOW_ENERGY"))), "REASSESSMENT_SELF_REPORT_NOT_FOUND");
		when(reports.find(USER, REPORT)).thenReturn(Optional.of(stored(persistedHash, null, true)));
		assertCode(() -> service.replace(USER, REPORT, 0, new ReplaceCommand("UNSURE", null, null, selection())),
				"REASSESSMENT_SELF_REPORT_NOT_FOUND");
		verify(reports, never()).replace(any(), any(), anyLong(), any(), any(), any(), any(), any());
	}

	private CreateCommand create(Selection selection) {
		return new CreateCommand(PERIOD, "UNSURE", " helpful ", " ", selection);
	}

	private Selection selection(String... codes) {
		return new Selection(ReassessmentSymptomCatalog.CATALOG_REVISION, List.of(codes));
	}

	private StoredSelfReport stored(String hash, Checklist checklist, boolean deleted) {
		return new StoredSelfReport(REPORT, USER, KEY, hash, ReassessmentSelfReportService.SOURCE_VERSION,
				PERIOD.startAt(), PERIOD.endAt(), deleted ? null : "UNSURE", deleted ? null : "helpful", null,
				0, NOW, NOW, deleted ? NOW : null, checklist);
	}

	private String legacyValue() {
		return PERIOD.startAt() + "|" + PERIOD.endAt() + "|UNSURE|helpful|null";
	}

	private String checklistValue(String... codes) {
		return checklistValue(ReassessmentSymptomCatalog.CATALOG_REVISION, List.of(codes));
	}

	private String checklistValue(String revision, List<String> codes) {
		return "reassessment-self-report-create-v2|" + field(PERIOD.startAt().toString()) + field(PERIOD.endAt().toString())
				+ field("UNSURE") + field("helpful") + "~" + field(revision) + codes.size() + "#"
				+ String.join("", codes.stream().map(this::field).toList());
	}

	private String field(String value) {
		return value.length() + ":" + value;
	}

	private String digest(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private void assertCode(Runnable action, String code) {
		assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);
	}
}
