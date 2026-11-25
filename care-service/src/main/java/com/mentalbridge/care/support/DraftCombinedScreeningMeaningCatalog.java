package com.mentalbridge.care.support;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mentalbridge.care.assessment.ScreeningLevel;

@Component
class DraftCombinedScreeningMeaningCatalog {

	static final String PRESENTATION_POLICY_VERSION = "mb-combined-screening-meaning-v1";
	static final String CONTENT_VERSION = "mb-combined-screening-meaning-vi-vn-v1-draft.3";
	static final int REFERENCE_PERIOD_DAYS = 14;

	private static final List<BaseMeaning> MEANINGS = List.of(
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MINIMAL, SupportTier.SELF_GUIDED_SUPPORT,
					"Dấu hiệu trầm cảm và lo âu đều rất ít.",
					"Cả hai kết quả đều ở mức tối thiểu, nên duy trì những thói quen đang giúp bạn ổn định là bước phù hợp. Bạn có thể làm lại bài sàng lọc khi cảm nhận của mình thay đổi."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, SupportTier.SELF_GUIDED_SUPPORT,
					"Lo âu ở mức nhẹ, dấu hiệu trầm cảm rất ít.",
					"Cả hai kết quả đều chưa vượt mức nhẹ, nên bạn có thể bắt đầu bằng các hoạt động tự chăm sóc, như một bài thư giãn ngắn, và để ý những lúc lo âu xuất hiện."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức trung bình, dấu hiệu trầm cảm rất ít. Bạn nên trao đổi thêm với chuyên gia.",
					"Lo âu từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia, để bạn hiểu rõ hơn điều đang diễn ra. Trong lúc chờ, các hoạt động tự chăm sóc nhẹ nhàng vẫn có thể giúp bạn."),
			meaning(ScreeningLevel.MINIMAL, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức nặng, dấu hiệu trầm cảm rất ít. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Kết quả lo âu nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Chuyên gia có thể cùng bạn tìm cách hỗ trợ phù hợp."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MINIMAL, SupportTier.SELF_GUIDED_SUPPORT,
					"Dấu hiệu trầm cảm ở mức nhẹ, lo âu rất ít.",
					"Cả hai kết quả đều chưa vượt mức nhẹ, nên bạn có thể bắt đầu bằng nghỉ ngơi, ăn uống đều đặn và một hoạt động nhỏ giúp nâng đỡ tâm trạng."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MILD, SupportTier.SELF_GUIDED_SUPPORT,
					"Dấu hiệu trầm cảm và lo âu đều ở mức nhẹ.",
					"Cả hai kết quả đều ở mức nhẹ, nên các hoạt động tự chăm sóc vừa sức là bước phù hợp để bắt đầu. Bạn có thể để ý điều gì giúp mình dễ chịu hơn và làm lại bài sàng lọc nếu tình trạng nặng thêm."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức trung bình, dấu hiệu trầm cảm ở mức nhẹ. Bạn nên trao đổi thêm với chuyên gia.",
					"Lo âu từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia. Khi trao đổi, bạn có thể kể cả những thay đổi về tâm trạng để chuyên gia có bức tranh đầy đủ."),
			meaning(ScreeningLevel.MILD, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức nhẹ. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Kết quả lo âu nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Bạn có thể kể cả những thay đổi về tâm trạng khi trao đổi."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức trung bình, lo âu rất ít. Bạn nên trao đổi thêm với chuyên gia.",
					"Dấu hiệu trầm cảm từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia, để bạn hiểu rõ hơn những thay đổi về tâm trạng, giấc ngủ hay năng lượng."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức trung bình, lo âu ở mức nhẹ. Bạn nên trao đổi thêm với chuyên gia.",
					"Dấu hiệu trầm cảm từ mức trung bình trở lên là lý do MentalBridge gợi ý trao đổi với chuyên gia. Khi trao đổi, bạn có thể kể cả những lúc lo âu xuất hiện."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm và lo âu đều ở mức trung bình. Bạn nên trao đổi thêm với chuyên gia.",
					"Cả hai kết quả đều từ mức trung bình trở lên, nên MentalBridge gợi ý trao đổi với chuyên gia về cả tâm trạng lẫn lo âu trong cùng một lần để không bỏ sót phần nào."),
			meaning(ScreeningLevel.MODERATE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức trung bình. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Kết quả lo âu nằm ở mức cao nhất của thang đo và dấu hiệu trầm cảm ở mức trung bình, nên việc trao đổi với chuyên gia nên được đặt lên trước. Hãy kể cả hai điều này khi trao đổi."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức khá nặng, lo âu rất ít. Bạn nên sớm trao đổi với chuyên gia.",
					"Dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên diễn ra sớm thay vì chờ xem có tự cải thiện hay không."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức khá nặng, lo âu ở mức nhẹ. Bạn nên sớm trao đổi với chuyên gia.",
					"Dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên diễn ra sớm. Khi trao đổi, bạn có thể kể cả những lúc lo âu xuất hiện."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức khá nặng, lo âu ở mức trung bình. Bạn nên sớm trao đổi với chuyên gia.",
					"Dấu hiệu trầm cảm ở mức khá nặng và lo âu ở mức trung bình, nên việc trao đổi với chuyên gia nên diễn ra sớm và bao gồm cả tâm trạng lẫn lo âu."),
			meaning(ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Lo âu ở mức nặng, dấu hiệu trầm cảm ở mức khá nặng. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Lo âu nằm ở mức cao nhất của thang đo và dấu hiệu trầm cảm ở mức khá nặng, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Mỗi kết quả cần được chuyên gia xem xét riêng."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MINIMAL, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức nặng, lo âu rất ít. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MILD, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức nặng, lo âu ở mức nhẹ. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước. Bạn có thể kể cả những lúc lo âu xuất hiện."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.MODERATE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm ở mức nặng, lo âu ở mức trung bình. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Dấu hiệu trầm cảm nằm ở mức cao nhất của thang đo và lo âu ở mức trung bình, nên việc trao đổi với chuyên gia nên được đặt lên trước và bao gồm cả hai điều này."),
			meaning(ScreeningLevel.SEVERE, ScreeningLevel.SEVERE, SupportTier.PROFESSIONAL_SUPPORT_RECOMMENDED,
					"Dấu hiệu trầm cảm và lo âu đều ở mức nặng. Bạn nên ưu tiên trao đổi sớm với chuyên gia.",
					"Cả hai kết quả đều nằm ở mức cao nhất của thang đo, nên việc trao đổi với chuyên gia nên được đặt lên trước, không cần chờ thêm. Mỗi kết quả cần được chuyên gia xem xét riêng."));

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
