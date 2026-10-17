package com.mentalbridge.community.feed;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "community_topic")
class CommunityTopicEntity {

	@Id
	@Enumerated(EnumType.STRING)
	@Column(length = 32)
	private CommunityTopic code;

	@Column(nullable = false, length = 80)
	private String label;

	@Column(nullable = false, length = 240)
	private String description;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "display_order", nullable = false)
	private short displayOrder;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected CommunityTopicEntity() {
	}

	CommunityTopic code() {
		return code;
	}

	String label() {
		return label;
	}

	String description() {
		return description;
	}
}
