package com.mentalbridge.care.supportguide;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_guide_resource")
class SupportGuideResourceEntity {

	@Id
	private UUID id;
	private UUID supportGuideId;
	private short ordinal;
	private UUID resourceId;
	private long contentVersion;
	private UUID publicationId;
	private String domain;
	private String eligibilityRole;
	private String category;
	private String title;
	@Column(columnDefinition = "text")
	private String summary;
	@Column(length = 2048)
	private String externalUrl;

	protected SupportGuideResourceEntity() { }

	SupportGuideResourceEntity(UUID id, UUID supportGuideId, short ordinal, UUID resourceId,
			long contentVersion, UUID publicationId, String domain, String eligibilityRole,
			String category, String title, String summary, String externalUrl) {
		this.id = id;
		this.supportGuideId = supportGuideId;
		this.ordinal = ordinal;
		this.resourceId = resourceId;
		this.contentVersion = contentVersion;
		this.publicationId = publicationId;
		this.domain = domain;
		this.eligibilityRole = eligibilityRole;
		this.category = category;
		this.title = title;
		this.summary = summary;
		this.externalUrl = externalUrl;
	}

	UUID resourceId() { return resourceId; }
	long contentVersion() { return contentVersion; }
	UUID publicationId() { return publicationId; }
	String domain() { return domain; }
	String eligibilityRole() { return eligibilityRole; }
	String category() { return category; }
	String title() { return title; }
	String summary() { return summary; }
	String externalUrl() { return externalUrl; }
}
