package com.mentalbridge.care.reassessment;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.shared.ApiException.FieldViolation;

public final class ReassessmentSymptomCatalog {

	public static final String CATALOG_REVISION = "reassessment-symptoms-v1";
	public static final String MAPPING_REVISION = "reassessment-symptom-resource-map-v1";
	public static final int MAX_SELECTIONS = 8;
	private static final List<Item> ITEMS = List.of(
			new Item("SLEEP_DIFFICULTY", "Khó ngủ hoặc giấc ngủ bị gián đoạn"),
			new Item("LOW_ENERGY", "Thiếu năng lượng cho việc thường ngày"),
			new Item("PERSISTENT_WORRY", "Lo lắng lặp đi lặp lại"),
			new Item("LOW_MOOD", "Buồn hoặc ít hứng thú với việc thường thích"),
			new Item("CONCENTRATION_DIFFICULTY", "Khó tập trung vào việc đang làm"),
			new Item("APPETITE_CHANGE", "Ăn uống thay đổi so với thường ngày"),
			new Item("SOCIAL_WITHDRAWAL", "Ít muốn gặp gỡ hoặc trò chuyện"),
			new Item("STRESS_OVERLOAD", "Cảm thấy căng thẳng hoặc quá tải"));
	private static final List<Candidate> CANDIDATES = List.of(
			candidate("SLEEP_DIFFICULTY", "207", "depression-adjunct", "anxiety-adjunct"),
			candidate("LOW_ENERGY", "208", "depression-maintenance", "depression-support-activity", "anxiety-adjunct"),
			candidate("PERSISTENT_WORRY", "211", "anxiety-maintenance", "anxiety-support-activity"),
			candidate("LOW_MOOD", "208", "depression-maintenance", "depression-support-activity", "anxiety-adjunct"),
			candidate("CONCENTRATION_DIFFICULTY", "203", "anxiety-maintenance", "anxiety-support-activity", "depression-adjunct"),
			candidate("STRESS_OVERLOAD", "209", "anxiety-maintenance", "anxiety-support-activity", "depression-adjunct"));

	private ReassessmentSymptomCatalog() { }

	public static CatalogView current() {
		return new CatalogView(CATALOG_REVISION, MAPPING_REVISION, MAX_SELECTIONS, ITEMS);
	}

	static Selection normalize(Selection selection) {
		if (selection == null) return null;
		if (selection.catalogRevision() == null || selection.catalogRevision().isBlank()
				|| selection.catalogRevision().length() > 128) throw invalid("symptomChecklist.catalogRevision");
		var codes = selection.selectedCodes();
		if (codes == null || codes.size() > MAX_SELECTIONS || codes.stream().anyMatch(code -> code == null
				|| ITEMS.stream().noneMatch(item -> item.code().equals(code)))
				|| new HashSet<>(codes).size() != codes.size()) throw invalid("symptomChecklist.selectedCodes");
		return new Selection(selection.catalogRevision(), codes.stream().sorted().toList());
	}

	static Checklist accept(Selection selection) {
		if (selection == null) return null;
		if (!CATALOG_REVISION.equals(selection.catalogRevision())) {
			throw new ApiException(HttpStatus.CONFLICT, "REASSESSMENT_SYMPTOM_CATALOG_UNSUPPORTED",
					"Reassessment symptom catalog revision is not supported");
		}
		return new Checklist(selection.catalogRevision(), MAPPING_REVISION, selection.selectedCodes());
	}

	static Checklist stored(String revision, List<String> codes) {
		if (revision == null) return null;
		return accept(normalize(new Selection(revision, codes)));
	}

	public static boolean released(Checklist checklist) {
		if (checklist == null || !CATALOG_REVISION.equals(checklist.catalogRevision())
				|| !MAPPING_REVISION.equals(checklist.mappingRevision()) || checklist.selectedCodes() == null) return false;
		try {
			return checklist.selectedCodes().equals(normalize(new Selection(checklist.catalogRevision(),
					checklist.selectedCodes())).selectedCodes());
		}
		catch (ApiException exception) {
			return false;
		}
	}

	public static List<Candidate> candidates(Checklist checklist) {
		if (!released(checklist)) return List.of();
		return CANDIDATES.stream().filter(candidate -> checklist.selectedCodes().contains(candidate.code())).toList();
	}

	public static List<SelectionReason> reasons(Checklist checklist) {
		if (checklist == null) return null;
		if (!released(checklist)) throw new IllegalArgumentException("Released symptom catalog bundle is required");
		return ITEMS.stream().filter(item -> checklist.selectedCodes().contains(item.code())).map(item -> {
			var resources = CANDIDATES.stream().filter(candidate -> candidate.code().equals(item.code()))
					.map(Candidate::resourceId).toList();
			return new SelectionReason(item.code(), resources, resources.isEmpty()
					? "NO_APPROVED_RESOURCE_MAPPING" : "SELF_REPORTED_SYMPTOM_MATCH", checklist.mappingRevision());
		}).toList();
	}

	private static Candidate candidate(String code, String resourceSuffix, String... slotIds) {
		return new Candidate(code, UUID.fromString("00000000-0000-4000-8000-000000000" + resourceSuffix), List.of(slotIds));
	}

	private static ApiException invalid(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed",
				List.of(new FieldViolation(field, "INVALID_VALUE", "Symptom checklist value is invalid")));
	}

	public record Selection(String catalogRevision, List<String> selectedCodes) { }

	public record Checklist(String catalogRevision, String mappingRevision, List<String> selectedCodes) {
		public Checklist {
			if (selectedCodes != null) selectedCodes = List.copyOf(selectedCodes);
		}
	}

	public record SelectionReason(String code, List<UUID> resourceIds, String reasonCode, String mappingRevision) {
		public SelectionReason {
			resourceIds = List.copyOf(resourceIds);
		}
	}

	public record Candidate(String code, UUID resourceId, List<String> slotIds) {
		public Candidate {
			slotIds = List.copyOf(slotIds);
		}
	}

	public record Item(String code, String label) { }

	public record CatalogView(String catalogRevision, String mappingRevision, int maxSelections, List<Item> items) {
		public CatalogView {
			items = List.copyOf(items);
		}
	}
}
