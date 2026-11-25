package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class AiContextProcessingDisclosureService {
	public static final String CONSENT_TYPE = "AI_CONTEXT_PROCESSING";
	public static final String VERSION = "ai-context-processing-capstone-v1";
	private static final String CONTENT = "MentalBridge dùng AI để xử lý các nguồn dữ liệu cá nhân đã được cho phép và bạn chọn, gồm nhật ký, thông tin sàng lọc tối giản, kế hoạch hỗ trợ và tiến độ tài nguyên, để phân tích nhật ký, trò chuyện có bối cảnh, hỗ trợ kế hoạch, gợi ý bản tóm tắt trước buổi tư vấn và diễn đạt Hướng dẫn hỗ trợ. Mức đồng ý này chỉ có hiệu lực khi bạn đang đồng ý AI trò chuyện. Bạn có thể rút lại từng mức đồng ý để chặn việc xử lý mới; rút lại AI trò chuyện sẽ tạm dừng AI dùng bối cảnh, và mức bối cảnh hiện tại có hiệu lực trở lại khi bạn đồng ý AI trò chuyện lại.";

	public AiProcessingDisclosureService.DisclosureView current(String locale) {
		if (locale != null && !"vi-VN".equalsIgnoreCase(locale)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "AI_CONTEXT_DISCLOSURE_NOT_FOUND",
					"AI context processing disclosure was not found");
		}
		return new AiProcessingDisclosureService.DisclosureView(CONSENT_TYPE, VERSION, "vi-VN", "AI dùng bối cảnh",
				CONTENT, true);
	}

	public void requireCurrent(String version) {
		if (!VERSION.equals(version)) {
			throw new ApiException(HttpStatus.CONFLICT, "AI_CONTEXT_DISCLOSURE_REQUIRED",
					"The current AI context processing disclosure must be used");
		}
	}
}
