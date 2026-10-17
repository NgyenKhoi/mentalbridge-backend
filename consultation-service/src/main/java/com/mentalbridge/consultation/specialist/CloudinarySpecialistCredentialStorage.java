package com.mentalbridge.consultation.specialist;

import java.time.Instant;
import java.util.Map;

import com.cloudinary.Cloudinary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "mentalbridge.consultation.credentials.cloudinary", name = "enabled", havingValue = "true")
class CloudinarySpecialistCredentialStorage implements SpecialistCredentialStorage {

	private final Cloudinary cloudinary;

	CloudinarySpecialistCredentialStorage(SpecialistCredentialStorageProperties properties) {
		if (properties.cloudName() == null || properties.cloudName().isBlank()
				|| properties.apiKey() == null || properties.apiKey().isBlank()
				|| properties.apiSecret() == null || properties.apiSecret().isBlank()) {
			throw new IllegalStateException("Credential storage requires Cloudinary configuration");
		}
		this.cloudinary = new Cloudinary(Map.of("cloud_name", properties.cloudName(),
				"api_key", properties.apiKey(), "api_secret", properties.apiSecret(),
				"timeout", properties.timeoutSeconds(), "secure", true));
	}

	@Override
	public StoredFile upload(byte[] contents, String storageKey, String mediaType) {
		try {
			var result = cloudinary.uploader().upload(contents, Map.of("public_id", storageKey,
					"resource_type", resourceType(mediaType), "type", "authenticated", "overwrite", false));
			return new StoredFile((String) result.get("public_id"),
					"application/pdf".equals(mediaType) ? "pdf" : (String) result.get("format"),
					((Number) result.get("bytes")).longValue());
		}
		catch (Exception exception) {
			throw new StorageFailure(exception);
		}
	}

	@Override
	public String viewUrl(String storageKey, String format, String mediaType, Instant expiresAt,
			boolean attachment) {
		try {
			return cloudinary.privateDownload(storageKey, format, Map.of("resource_type", resourceType(mediaType),
					"type", "authenticated", "expires_at", expiresAt.getEpochSecond(), "attachment", attachment));
		}
		catch (Exception exception) {
			throw new StorageFailure(exception);
		}
	}

	@Override
	public void delete(String storageKey, String mediaType) {
		try {
			cloudinary.uploader().destroy(storageKey, Map.of("resource_type", resourceType(mediaType),
					"type", "authenticated", "invalidate", true));
		}
		catch (Exception exception) {
			throw new StorageFailure(exception);
		}
	}

	private String resourceType(String mediaType) {
		return "application/pdf".equals(mediaType) ? "raw" : "image";
	}

	static class StorageFailure extends RuntimeException {
		StorageFailure(Throwable cause) { super(cause); }
	}
}
