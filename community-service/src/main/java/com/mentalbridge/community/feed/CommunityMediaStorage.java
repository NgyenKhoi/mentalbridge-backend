package com.mentalbridge.community.feed;

import java.net.URI;
import java.util.Map;

public interface CommunityMediaStorage {

	UploadAuthorization authorize(String storageKey, CommunityMediaEntity.Type mediaType, long timestampSeconds);

	StoredAsset inspect(String storageKey, CommunityMediaEntity.Type mediaType);

	String deliveryUrl(StoredAsset asset, CommunityMediaEntity.Type mediaType);

	void delete(String storageKey, CommunityMediaEntity.Type mediaType);

	record UploadAuthorization(URI uploadUrl, Map<String, String> fields) {
	}

	record StoredAsset(String storageKey, String resourceType, String deliveryType, String format, long bytes,
			int width, int height, Double durationSeconds, long providerVersion) {
	}

	class AssetMissingException extends RuntimeException {
		public AssetMissingException(Throwable cause) {
			super(cause);
		}
	}

	class StorageUnavailableException extends RuntimeException {
		public StorageUnavailableException(Throwable cause) {
			super(cause);
		}
	}
}
