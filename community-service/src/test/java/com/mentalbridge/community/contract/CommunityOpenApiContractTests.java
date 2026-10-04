package com.mentalbridge.community.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

class CommunityOpenApiContractTests {

	private static final Set<String> IMPLEMENTED = Set.of(
			"GET /api/v1/community/feed",
			"POST /api/v1/community/posts",
			"GET /api/v1/community/posts/{postId}",
			"PATCH /api/v1/community/posts/{postId}",
			"DELETE /api/v1/community/posts/{postId}",
			"POST /api/v1/community/media/upload-intents",
			"POST /api/v1/community/media/{mediaId}/finalize",
			"DELETE /api/v1/community/media/{mediaId}",
			"GET /api/v1/community/posts/{postId}/comments",
			"POST /api/v1/community/posts/{postId}/comments",
			"PATCH /api/v1/community/comments/{commentId}",
			"DELETE /api/v1/community/comments/{commentId}",
			"PUT /api/v1/community/posts/{postId}/reaction",
			"DELETE /api/v1/community/posts/{postId}/reaction",
			"PUT /api/v1/community/posts/{postId}/bookmark",
			"DELETE /api/v1/community/posts/{postId}/bookmark",
			"POST /api/v1/community/reports",
			"PUT /api/v1/community/hidden-content/{targetType}/{targetId}",
			"DELETE /api/v1/community/hidden-content/{targetType}/{targetId}",
			"PUT /api/v1/community/blocks/{communityProfileId}",
			"DELETE /api/v1/community/blocks/{communityProfileId}",
			"GET /api/v1/community/profile",
			"PUT /api/v1/community/profile",
			"GET /api/v1/community/topics",
			"GET /api/v1/community/admin/moderation-cases",
			"GET /api/v1/community/admin/moderation-cases/{caseId}",
			"POST /api/v1/community/admin/moderation-cases/{caseId}/actions");

	private static final Set<String> ALL_OPERATIONS = Set.of(
			"GET /api/v1/community/feed",
			"POST /api/v1/community/posts",
			"GET /api/v1/community/posts/{postId}",
			"PATCH /api/v1/community/posts/{postId}",
			"DELETE /api/v1/community/posts/{postId}",
			"POST /api/v1/community/media/upload-intents",
			"POST /api/v1/community/media/{mediaId}/finalize",
			"DELETE /api/v1/community/media/{mediaId}",
			"GET /api/v1/community/posts/{postId}/comments",
			"POST /api/v1/community/posts/{postId}/comments",
			"PATCH /api/v1/community/comments/{commentId}",
			"DELETE /api/v1/community/comments/{commentId}",
			"PUT /api/v1/community/posts/{postId}/reaction",
			"DELETE /api/v1/community/posts/{postId}/reaction",
			"PUT /api/v1/community/posts/{postId}/bookmark",
			"DELETE /api/v1/community/posts/{postId}/bookmark",
			"POST /api/v1/community/reports",
			"PUT /api/v1/community/hidden-content/{targetType}/{targetId}",
			"DELETE /api/v1/community/hidden-content/{targetType}/{targetId}",
			"PUT /api/v1/community/blocks/{communityProfileId}",
			"DELETE /api/v1/community/blocks/{communityProfileId}",
			"GET /api/v1/community/profile",
			"PUT /api/v1/community/profile",
			"GET /api/v1/community/topics",
			"GET /api/v1/community/admin/moderation-cases",
			"GET /api/v1/community/admin/moderation-cases/{caseId}",
			"POST /api/v1/community/admin/moderation-cases/{caseId}/actions");

	@Test
	void frozenV1ContractIsValidAndMarksDeliveredOperationsImplemented() {
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract().toUri().toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		var operations = new HashSet<String>();
		result.getOpenAPI().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
			var key = method.name() + " " + path;
			operations.add(key);
			var operationStatus = operation.getExtensions() == null ? null
					: operation.getExtensions().get("x-mentalbridge-status");
			var pathStatus = item.getExtensions() == null ? null : item.getExtensions().get("x-mentalbridge-status");
			assertThat(operationStatus == null ? pathStatus : operationStatus)
					.isEqualTo(IMPLEMENTED.contains(key) ? "implemented" : "planned");
			assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("BearerAuth"));
		}));

		assertThat(operations).isEqualTo(ALL_OPERATIONS);
	}

	@Test
	void publicFeedContractContainsOnlyCommunityOwnedRankingInputsAndIdentity() {
		var api = new OpenAPIV3Parser().read(contract().toString());
		var author = api.getComponents().getSchemas().get("CommunityAuthor");
		var post = api.getComponents().getSchemas().get("CommunityPostSummary");
		var createPost = api.getComponents().getSchemas().get("CreatePostRequest");
		var feedParameters = api.getPaths().get("/api/v1/community/feed").getGet().getParameters();

		assertThat(author.getProperties()).containsOnlyKeys("communityProfileId", "displayName", "avatarPreset", "state");
		assertThat(post.getProperties()).containsOnlyKeys("postId", "author", "contentPreview", "topics", "media",
				"mediaAvailability", "counts", "viewerState", "publishedAt", "updatedAt");
		assertThat(createPost.getProperties()).containsOnlyKeys("content", "topics", "mediaIds", "authorMode");
		assertThat(feedParameters).extracting(parameter -> parameter.getName())
				.containsExactly("topic", "cursor", "limit");
		var topicFilter = feedParameters.getFirst();
		assertThat(topicFilter.getExplode()).isTrue();
		assertThat(topicFilter.getSchema().getMaxItems()).isEqualTo(3);
		assertThat(topicFilter.getSchema().getUniqueItems()).isTrue();
	}

	@Test
	void profileContractExposesOnlyCommunityOwnedDisplayIdentity() {
		var api = new OpenAPIV3Parser().read(contract().toString());
		var request = api.getComponents().getSchemas().get("PutCommunityProfileRequest");
		var response = api.getComponents().getSchemas().get("CommunityProfile");

		assertThat(request.getProperties()).containsOnlyKeys("displayName", "avatarPreset");
		assertThat(response.getProperties()).containsOnlyKeys("communityProfileId", "displayName", "avatarPreset",
				"status", "version", "createdAt", "updatedAt");
	}

	@Test
	void mediaIntentCarriesOnlyShortLivedSignedUploadFields() {
		var api = new OpenAPIV3Parser().read(contract().toString());
		var intent = api.getComponents().getSchemas().get("MediaUploadIntent");
		var request = api.getComponents().getSchemas().get("CreateMediaUploadIntentRequest");

		assertThat(intent.getProperties()).containsOnlyKeys("mediaId", "state", "uploadUrl", "expiresAt",
				"uploadFields", "version");
		assertThat(request.getProperties()).containsOnlyKeys("fileName", "mediaType", "mimeType", "sizeBytes");
	}

	private Path contract() {
		return Path.of("..", "contracts", "openapi", "community-service-v1.yaml").toAbsolutePath();
	}
}
