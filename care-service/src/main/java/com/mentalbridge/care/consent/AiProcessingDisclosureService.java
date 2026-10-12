package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class AiProcessingDisclosureService {

	public static final String CONSENT_TYPE = "AI_PROCESSING";
	public static final String VERSION = "ai-processing-capstone-v2";
	private static final String LOCALE = "vi-VN";
	private static final String TITLE = "Đồng ý xử lý dữ liệu bằng AI";
	private static final String CONTENT = "MentalBridge chỉ dùng AI khi bạn chủ động yêu cầu. Phạm vi bao gồm nội dung nhật ký cụ thể bạn chọn, các phiên bản nhật ký trong khoảng thời gian giới hạn, hoặc bản tóm tắt trước buổi tư vấn đã lưu cùng mức sàng lọc tối giản. AI chỉ tạo gợi ý để bạn xem lại và chỉnh sửa; không chẩn đoán, không chấm PHQ-9/GAD-7, không quyết định an toàn, quyền lợi hay kế hoạch hỗ trợ, và không tự phê duyệt hoặc chia sẻ với chuyên gia. Nội dung nhật ký, câu trả lời sàng lọc, chat và ghi chú riêng không được dùng cho gợi ý tóm tắt này. Bạn có thể rút lại đồng ý để chặn các yêu cầu hoặc lần thử lại mới.";

	public DisclosureView current(String locale) {
		if (locale != null && !LOCALE.equalsIgnoreCase(locale)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "AI_PROCESSING_DISCLOSURE_NOT_FOUND",
					"AI processing disclosure was not found");
		}
		return new DisclosureView(CONSENT_TYPE, VERSION, LOCALE, TITLE, CONTENT, true);
	}

	public void requireCurrent(String version) {
		if (!VERSION.equals(version)) {
			throw new ApiException(HttpStatus.CONFLICT, "AI_PROCESSING_DISCLOSURE_REQUIRED",
					"The current AI processing disclosure must be used");
		}
	}

	public record DisclosureView(String consentType, String version, String locale, String title, String content,
			boolean capstoneOnly) { }
}
