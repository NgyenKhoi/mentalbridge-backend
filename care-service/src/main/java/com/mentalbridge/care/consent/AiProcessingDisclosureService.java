package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class AiProcessingDisclosureService {

	public static final String CONSENT_TYPE = "AI_PROCESSING";
	public static final String VERSION = "ai-processing-capstone-v1";
	private static final String LOCALE = "vi-VN";
	private static final String TITLE = "Đồng ý xử lý nhật ký bằng AI";
	private static final String CONTENT = "MentalBridge chỉ xử lý bằng AI một phiên bản nhật ký cụ thể khi bạn chủ động yêu cầu, hoặc các phiên bản nhật ký cụ thể trong hai khoảng thời gian giới hạn khi bạn chủ động yêu cầu phân tích theo thời gian. Kết quả chỉ hỗ trợ phản ánh và điều hướng, không phải chẩn đoán, không chấm PHQ-9/GAD-7, không quyết định safety, eligibility hay thay đổi SupportPlan. Sự đồng ý này không bao gồm nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Bạn có thể rút lại sự đồng ý để chặn các lần xử lý hoặc retry mới. Việc rút lại không tự động xóa kết quả đã lưu; xóa dữ liệu là một quy trình riêng.";

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
			boolean capstoneOnly) {
	}
}
