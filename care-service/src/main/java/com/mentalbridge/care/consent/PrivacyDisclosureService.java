package com.mentalbridge.care.consent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class PrivacyDisclosureService {

	public static final String VERSION = "privacy-capstone-v1";
	public static final String CONSENT_TYPE = "PRIVACY_POLICY";
	private static final String LOCALE = "vi-VN";
	private static final String TITLE = "Thông báo xử lý dữ liệu Care cho bản Capstone";
	private static final String CONTENT = "Trong bản Capstone thử nghiệm, MentalBridge lưu hồ sơ Care, câu trả lời PHQ-9 và kết quả sàng lọc do máy chủ tính để hiển thị lịch sử và hỗ trợ bạn chủ động đánh giá lại. Kết quả này không phải chẩn đoán. Xác nhận này không cho phép xử lý AI, sử dụng cho nghiên cứu, gửi marketing hoặc chia sẻ với chuyên gia. Dữ liệu ẩn danh hết hạn độc lập và không được gắn vào tài khoản đăng ký sau đó. Lịch sử của tài khoản chỉ được dùng với dữ liệu tổng hợp/test trong phạm vi demo Sprint 2; việc thu thập dữ liệu người dùng thật cần quy trình privacy, security và legal riêng.";

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
