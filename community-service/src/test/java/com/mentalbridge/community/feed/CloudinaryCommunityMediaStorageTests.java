package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.Test;

import com.mentalbridge.community.configuration.CloudinaryProperties;

class CloudinaryCommunityMediaStorageTests {

	private final CloudinaryCommunityMediaStorage storage = new CloudinaryCommunityMediaStorage(
			new Cloudinary(Map.of("cloud_name", "test-cloud", "api_key", "public-key", "api_secret", "secret",
					"secure", true)),
			new CloudinaryProperties("test-cloud", "public-key", "secret", 5));

	@Test
	void uploadAuthorizationIsSignedScopedAndNeverExposesTheSecret() {
		var authorization = storage.authorize("mentalbridge/community/owner/media",
				CommunityMediaEntity.Type.IMAGE, 1_790_756_400L);

		assertThat(authorization.uploadUrl().toString())
				.isEqualTo("https://api.cloudinary.com/v1_1/test-cloud/image/upload");
		assertThat(authorization.fields())
				.containsEntry("api_key", "public-key")
				.containsEntry("public_id", "mentalbridge/community/owner/media")
				.containsEntry("type", "authenticated")
				.containsEntry("overwrite", "false")
				.containsKey("signature")
				.doesNotContainKey("api_secret");
	}

	@Test
	void deliveryUsesSignedAuthenticatedTransformationsThatStripMetadata() {
		var asset = new CommunityMediaStorage.StoredAsset("mentalbridge/community/owner/media", "image",
				"authenticated", "jpg", 100, 800, 600, null, 7);

		assertThat(storage.deliveryUrl(asset, CommunityMediaEntity.Type.IMAGE))
				.startsWith("https://res.cloudinary.com/test-cloud/image/authenticated/")
				.contains("s--")
				.contains("f_auto,q_auto")
				.contains("/v7/mentalbridge/community/owner/media");
	}
}
