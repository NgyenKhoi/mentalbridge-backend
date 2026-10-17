package com.mentalbridge.community.feed;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.api.ApiResponse;
import com.cloudinary.api.exceptions.NotFound;
import org.springframework.stereotype.Component;

import com.mentalbridge.community.configuration.CloudinaryProperties;

@Component
class CloudinaryCommunityMediaStorage implements CommunityMediaStorage {

	private static final String DELIVERY_TYPE = "authenticated";

	private final Cloudinary cloudinary;
	private final CloudinaryProperties properties;

	CloudinaryCommunityMediaStorage(Cloudinary cloudinary, CloudinaryProperties properties) {
		this.cloudinary = cloudinary;
		this.properties = properties;
	}

	@Override
	public UploadAuthorization authorize(String storageKey, CommunityMediaEntity.Type mediaType,
			long timestampSeconds) {
		var signed = new LinkedHashMap<String, Object>();
		signed.put("overwrite", false);
		signed.put("public_id", storageKey);
		signed.put("timestamp", timestampSeconds);
		signed.put("type", DELIVERY_TYPE);
		var fields = new LinkedHashMap<String, String>();
		fields.put("api_key", properties.apiKey());
		fields.put("overwrite", "false");
		fields.put("public_id", storageKey);
		fields.put("timestamp", Long.toString(timestampSeconds));
		fields.put("type", DELIVERY_TYPE);
		fields.put("signature", cloudinary.apiSignRequest(signed, properties.apiSecret(), 1));
		var resourceType = resourceType(mediaType);
		return new UploadAuthorization(URI.create("https://api.cloudinary.com/v1_1/" + properties.cloudName()
				+ "/" + resourceType + "/upload"), Map.copyOf(fields));
	}

	@Override
	public StoredAsset inspect(String storageKey, CommunityMediaEntity.Type mediaType) {
		try {
			ApiResponse response = cloudinary.api().resource(storageKey,
					Map.of("resource_type", resourceType(mediaType), "type", DELIVERY_TYPE));
			return new StoredAsset(text(response, "public_id"), text(response, "resource_type"),
					text(response, "type"), text(response, "format"), number(response, "bytes").longValue(),
					number(response, "width").intValue(), number(response, "height").intValue(),
					optionalNumber(response, "duration"), number(response, "version").longValue());
		}
		catch (NotFound exception) {
			throw new AssetMissingException(exception);
		}
		catch (Exception exception) {
			throw new StorageUnavailableException(exception);
		}
	}

	@Override
	public void delete(String storageKey, CommunityMediaEntity.Type mediaType) {
		try {
			cloudinary.api().deleteResources(java.util.List.of(storageKey),
					Map.of("resource_type", resourceType(mediaType), "type", DELIVERY_TYPE, "invalidate", true));
		}
		catch (NotFound exception) {
			return;
		}
		catch (Exception exception) {
			throw new StorageUnavailableException(exception);
		}
	}

	@Override
	public String deliveryUrl(StoredAsset asset, CommunityMediaEntity.Type mediaType) {
		var transformation = new Transformation<>().quality("auto");
		if (mediaType == CommunityMediaEntity.Type.IMAGE) {
			transformation.fetchFormat("auto");
		}
		else {
			transformation.fetchFormat("mp4");
		}
		return cloudinary.url().resourceType(resourceType(mediaType)).type(DELIVERY_TYPE).secure(true).signed(true)
				.version(asset.providerVersion()).transformation(transformation).generate(asset.storageKey());
	}

	private String resourceType(CommunityMediaEntity.Type mediaType) {
		return mediaType == CommunityMediaEntity.Type.IMAGE ? "image" : "video";
	}

	private String text(Map<?, ?> response, String key) {
		var value = response.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new IllegalStateException("Cloudinary response omitted " + key);
		}
		return text;
	}

	private Number number(Map<?, ?> response, String key) {
		var value = response.get(key);
		if (!(value instanceof Number number)) {
			throw new IllegalStateException("Cloudinary response omitted " + key);
		}
		return number;
	}

	private Double optionalNumber(Map<?, ?> response, String key) {
		var value = response.get(key);
		return value instanceof Number number ? number.doubleValue() : null;
	}
}
