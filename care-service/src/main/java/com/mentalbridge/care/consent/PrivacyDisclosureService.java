package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class PrivacyDisclosureService {

	public static final String VERSION = "privacy-capstone-v2";
	public static final String CONSENT_TYPE = "PRIVACY_POLICY";
	private static final String LOCALE = "vi-VN";
	private static final String TITLE = "Thông báo về việc xử lý dữ liệu sức khỏe";
	private static final String CONTENT = "MentalBridge lưu thông tin hồ sơ, câu trả lời PHQ-9 và kết quả sàng lọc được tính từ câu trả lời của bạn để hiển thị lịch sử và hỗ trợ bạn thực hiện lại bài sàng lọc. Kết quả sàng lọc chỉ mang tính tham khảo, không phải chẩn đoán y khoa và không thay thế tư vấn của chuyên gia. Việc xác nhận thông báo này chỉ áp dụng cho quá trình xử lý bài sàng lọc; không bao gồm xử lý bằng AI, nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Bạn có thể rút lại xác nhận để ngăn các lần xử lý mới. Việc rút lại xác nhận không tự động xóa lịch sử đã lưu; yêu cầu xóa dữ liệu là một quy trình riêng.";

	public DisclosureView current(String locale) {
		if (locale != null && !LOCALE.equalsIgnoreCase(locale)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PRIVACY_DISCLOSURE_NOT_FOUND",
					"Privacy disclosure was not found");
		}
		return new DisclosureView(CONSENT_TYPE, VERSION, LOCALE, TITLE, CONTENT, true);
	}

	public void requireCurrent(String version, boolean acknowledged) {
		if (!acknowledged || !VERSION.equals(version)) {
			throw new ApiException(HttpStatus.CONFLICT, "PRIVACY_DISCLOSURE_REQUIRED",
					"The current privacy disclosure must be acknowledged before assessment processing");
		}
	}

	public record DisclosureView(String consentType, String version, String locale, String title, String content,
			boolean capstoneOnly) {
	}
}
