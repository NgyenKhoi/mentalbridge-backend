package com.mentalbridge.care.support;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.assessment.ScreeningLevel;

@Component
class DraftCombinedScreeningMeaningCatalog {

	static final String PRESENTATION_POLICY_VERSION = "mb-combined-screening-meaning-v1";
	static final String CONTENT_VERSION = "mb-combined-screening-meaning-vi-vn-v1-draft.2";
	static final int REFERENCE_PERIOD_DAYS = 14;

	private static final List<BaseMeaning> MEANINGS = List.of(
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MINIMAL, SupportTier.SELF_GUIDED_SUPPORT,
					"Hai nhóm dấu hiệu được hỏi đến đều xuất hiện rất ít trong lần sàng lọc này.",
					"Bạn có thể duy trì nhịp nghỉ ngơi, ăn uống và vận động; đồng thời thử các tài nguyên tự hỗ trợ phù hợp."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, SupportTier.SELF_GUIDED_SUPPORT,
					"Các dấu hiệu lo âu ở mức nhẹ, còn nhóm PHQ-9 được ghi nhận rất ít.",
					"Bạn có thể bắt đầu với một hoạt động thư giãn ngắn và quan sát những lúc cảm giác lo âu xuất hiện."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Kết quả lo âu cần được chú ý, dù PHQ-9 ghi nhận rất ít dấu hiệu.",
					"Lo âu là nội dung nên ưu tiên trao đổi với chuyên gia; bạn vẫn có thể thử một hoạt động tự hỗ trợ nhẹ nhàng."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu lo âu ở mức nặng, trong khi PHQ-9 ghi nhận rất ít dấu hiệu.",
					"Kết quả lo âu gợi ý ưu tiên trao đổi với chuyên gia để nhận hỗ trợ phù hợp."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MINIMAL, SupportTier.SELF_GUIDED_SUPPORT,
					"Nhóm dấu hiệu PHQ-9 ở mức nhẹ, còn lo âu được ghi nhận rất ít.",
					"Bạn có thể bắt đầu bằng nghỉ ngơi, ăn uống đều đặn và một hoạt động nhỏ giúp nâng đỡ tâm trạng."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MILD, SupportTier.SELF_GUIDED_SUPPORT,
					"Cả hai nhóm dấu hiệu đều ở mức nhẹ, có thể bắt đầu bằng những hoạt động vừa sức.",
					"Bạn có thể chọn một hoạt động từ kho tài nguyên và theo dõi điều gì giúp mình dễ chịu hơn."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu là nội dung nên ưu tiên trao đổi; nhóm PHQ-9 ở mức nhẹ.",
					"Bạn nên trao đổi về lo âu với chuyên gia; các hoạt động nhẹ nhàng có thể hỗ trợ trong lúc chờ."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu lo âu ở mức nặng cần được chú ý; nhóm PHQ-9 ở mức nhẹ.",
					"Kết quả lo âu gợi ý ưu tiên gặp chuyên gia để được lắng nghe và định hướng phù hợp."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Các dấu hiệu PHQ-9 cần được quan tâm, dù lo âu được ghi nhận rất ít.",
					"Kết quả PHQ-9 gợi ý trao đổi với chuyên gia về những thay đổi bạn đang nhận thấy."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Nhóm dấu hiệu PHQ-9 cần được quan tâm; lo âu cũng xuất hiện ở mức nhẹ.",
					"Bạn nên trao đổi với chuyên gia về thay đổi tâm trạng và chia sẻ thêm những lúc lo âu xuất hiện."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm dấu hiệu đều đáng chú ý và nên được trao đổi cùng nhau.",
					"Hai nhóm khó khăn nên được trao đổi cùng chuyên gia để không bỏ sót phần nào."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm cần được quan tâm, đặc biệt là kết quả lo âu ở mức nặng.",
					"Bạn nên ưu tiên trao đổi với chuyên gia về lo âu và trình bày cả những thay đổi thuộc nhóm PHQ-9."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Nhóm dấu hiệu PHQ-9 cần được chú ý, dù lo âu được ghi nhận rất ít.",
					"Nhóm dấu hiệu PHQ-9 là nội dung nên được trao đổi sớm với chuyên gia."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Nhóm PHQ-9 ở mức khá nặng, đồng thời có dấu hiệu lo âu nhẹ.",
					"Bạn nên sớm trao đổi với chuyên gia và chia sẻ cả những lúc cảm giác lo âu xuất hiện."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm đáng chú ý, với PHQ-9 ở mức khá nặng và lo âu mức trung bình.",
					"Cả tâm trạng và lo âu nên được đưa vào một cuộc trao đổi đầy đủ với chuyên gia."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm dấu hiệu đều đáng chú ý và nên được chuyên gia xem xét cùng nhau.",
					"Bạn nên ưu tiên gặp chuyên gia để trao đổi riêng về từng nhóm khó khăn được ghi nhận."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Kết quả PHQ-9 ở mức nặng cần được ưu tiên trao đổi, dù lo âu ít xuất hiện.",
					"Bạn nên ưu tiên gặp chuyên gia để trao đổi về nhóm dấu hiệu PHQ-9."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Nhóm PHQ-9 ở mức nặng cần ưu tiên hỗ trợ, đồng thời lưu ý lo âu nhẹ.",
					"Bạn nên ưu tiên gặp chuyên gia và chia sẻ cả những lúc cảm giác lo âu xuất hiện."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm đáng chú ý, với PHQ-9 mức nặng và lo âu mức trung bình.",
					"Bạn nên ưu tiên gặp chuyên gia để trao đổi đầy đủ về cả tâm trạng và lo âu."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Cả hai nhóm dấu hiệu đều ở mức nặng và cần được xem xét riêng trong hỗ trợ chuyên môn.",
					"Bạn nên ưu tiên gặp chuyên gia để từng nhóm khó khăn được lắng nghe và xem xét riêng."));

	BaseMeaning resolve(ScreeningLevel phq9, ScreeningLevel gad7) {
		return MEANINGS.stream().filter(meaning -> meaning.phq9() == phq9 && meaning.gad7() == gad7)
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported screening pair"));
	}

	private static BaseMeaning meaning(ScreeningLevel phq9, ScreeningLevel gad7, SupportTier tier, String text, String rationale) {
		return new BaseMeaning(phq9, gad7, "PHQ9_" + phq9 + "_GAD7_" + gad7, text, rationale, tier);
	}

	record BaseMeaning(ScreeningLevel phq9, ScreeningLevel gad7, String code, String text, String rationale,
			SupportTier supportTier) { }
}
