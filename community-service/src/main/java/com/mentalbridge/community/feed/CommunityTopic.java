package com.mentalbridge.community.feed;

public enum CommunityTopic {
	MY_STORY("Câu chuyện của tôi", "Chia sẻ trải nghiệm cá nhân theo cách bạn thấy thoải mái."),
	SMALL_MILESTONE("Bước tiến nhỏ", "Ghi nhận một thay đổi nhỏ có ý nghĩa với bạn."),
	HELPFUL_REFLECTION("Điều mình nhận ra", "Chia sẻ một suy ngẫm có thể hữu ích cho người khác."),
	PEER_QUESTION("Hỏi cộng đồng", "Đặt câu hỏi để lắng nghe trải nghiệm từ những người đồng hành."),
	EXPERIENCE_SHARING("Chia sẻ trải nghiệm", "Kể lại điều bạn đã thử và cảm nhận của riêng bạn."),
	HELPFUL_RESOURCE("Tài nguyên hữu ích", "Chia sẻ tài nguyên đã được xem xét và giúp ích cho bạn.");

	private final String label;
	private final String description;

	CommunityTopic(String label, String description) {
		this.label = label;
		this.description = description;
	}

	String label() {
		return label;
	}

	String description() {
		return description;
	}
}
