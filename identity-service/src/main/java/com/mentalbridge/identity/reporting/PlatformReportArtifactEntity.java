package com.mentalbridge.identity.reporting;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "platform_report_artifact")
public class PlatformReportArtifactEntity {

	@Id
	@Column(name = "report_job_id")
	private UUID reportJobId;

	@Column(name = "media_type", nullable = false, updatable = false, length = 96)
	private String mediaType;

	@Column(name = "file_name", nullable = false, updatable = false, length = 160)
	private String fileName;

	@Column(nullable = false, updatable = false)
	private byte[] content;

	@Column(name = "content_sha256", nullable = false, updatable = false, length = 64)
	private String contentSha256;

	@Column(name = "content_length", nullable = false, updatable = false)
	private long contentLength;

	@Column(name = "generated_at", nullable = false, updatable = false)
	private Instant generatedAt;

	@Column(name = "retained_until", nullable = false, updatable = false)
	private Instant retainedUntil;

	protected PlatformReportArtifactEntity() {
	}

	PlatformReportArtifactEntity(UUID reportJobId, String mediaType, String fileName, byte[] content,
			String contentSha256, Instant generatedAt, Instant retainedUntil) {
		this.reportJobId = reportJobId;
		this.mediaType = mediaType;
		this.fileName = fileName;
		this.content = content.clone();
		this.contentSha256 = contentSha256;
		this.contentLength = content.length;
		this.generatedAt = generatedAt;
		this.retainedUntil = retainedUntil;
	}

	public UUID reportJobId() { return reportJobId; }
	public String mediaType() { return mediaType; }
	public String fileName() { return fileName; }
	public byte[] content() { return content.clone(); }
	public String contentSha256() { return contentSha256; }
	public long contentLength() { return contentLength; }
	public Instant generatedAt() { return generatedAt; }
	public Instant retainedUntil() { return retainedUntil; }

}
