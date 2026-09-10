package com.mentalbridge.care.consent;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.shared.ApiException;

@Service
public class PrivacyDisclosureService {

	public static final String VERSION = "privacy-capstone-v3";
	public static final String CONSENT_TYPE = "PRIVACY_POLICY";
	private static final String LOCALE = "vi-VN";
	private static final Map<String, DisclosureText> DISCLOSURES = Map.of(
			"privacy-capstone-v2",
			new DisclosureText("Thông báo về việc xử lý dữ liệu sức khỏe",
					"MentalBridge lưu thông tin hồ sơ, câu trả lời PHQ-9 và kết quả sàng lọc được tính từ câu trả lời của bạn để hiển thị lịch sử và hỗ trợ bạn thực hiện lại bài sàng lọc. Kết quả sàng lọc chỉ mang tính tham khảo, không phải chẩn đoán y khoa và không thay thế tư vấn của chuyên gia. Việc xác nhận thông báo này chỉ áp dụng cho quá trình xử lý bài sàng lọc; không bao gồm xử lý bằng AI, nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Bạn có thể rút lại xác nhận để ngăn các lần xử lý mới. Việc rút lại xác nhận không tự động xóa lịch sử đã lưu; yêu cầu xóa dữ liệu là một quy trình riêng."),
			VERSION,
			new DisclosureText("Thông báo và đồng ý xử lý dữ liệu sàng lọc",
					"MentalBridge xử lý các câu trả lời PHQ-9 hoặc GAD-7 và kết quả sàng lọc được tính từ các câu trả lời đó nhằm cung cấp chức năng sàng lọc sức khỏe tâm lý. Đối với người dùng đã đăng nhập, MentalBridge có thể lưu kết quả sàng lọc cùng thông tin cần thiết của tài khoản để hiển thị lịch sử và hỗ trợ bạn thực hiện lại bài sàng lọc. Đối với phiên ẩn danh, dữ liệu chỉ được xử lý trong phạm vi của phiên ẩn danh theo chính sách hiện hành và không tự động được gắn vào tài khoản được tạo sau đó. Kết quả PHQ-9 và GAD-7 chỉ mang tính sàng lọc, không phải chẩn đoán y khoa và không thay thế đánh giá hoặc tư vấn của chuyên gia. Sự đồng ý này chỉ áp dụng cho việc xử lý dữ liệu cần thiết để thực hiện và lưu kết quả bài sàng lọc. Sự đồng ý này không bao gồm xử lý dữ liệu bằng AI, sử dụng dữ liệu cho nghiên cứu, tiếp thị hoặc chia sẻ dữ liệu với chuyên gia. Các mục đích đó, nếu được triển khai, phải có quyết định đồng ý riêng. Bạn có thể rút lại sự đồng ý đối với các hoạt động xử lý mới trong tương lai. Việc rút lại sự đồng ý không tự động xóa dữ liệu đã được lưu trước đó; yêu cầu xóa dữ liệu là một quy trình riêng theo chính sách hiện hành."));

	public DisclosureView current(String locale) {
		return byVersion(VERSION, locale);
	}

	DisclosureView byVersion(String version, String locale) {
		var disclosure = DISCLOSURES.get(version);
		if (disclosure == null || (locale != null && !LOCALE.equalsIgnoreCase(locale))) {
			throw new ApiException(HttpStatus.NOT_FOUND, "PRIVACY_DISCLOSURE_NOT_FOUND",
					"Privacy disclosure was not found");
		}
		return new DisclosureView(CONSENT_TYPE, version, LOCALE, disclosure.title(), disclosure.content(), true);
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

	private record DisclosureText(String title, String content) {
	}
}
