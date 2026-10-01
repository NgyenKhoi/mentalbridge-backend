package com.mentalbridge.community.feed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.community.configuration.CommunityMediaProperties;
import com.mentalbridge.community.feed.CommunityMediaRequests.CreateUploadIntentRequest;
import com.mentalbridge.community.feed.CommunityMediaResponses.MediaRecord;
import com.mentalbridge.community.feed.CommunityMediaResponses.UploadIntent;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
public class CommunityMediaService {

	private static final Logger LOG = LoggerFactory.getLogger(CommunityMediaService.class);
	private static final String DEFAULT_DISPLAY_NAME = "ThĂ nh viĂªn MentalBridge";
	private static final int MAX_UNATTACHED_MEDIA = 10;
	private static final Set<String> IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final Set<String> VIDEO_MIME_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
	private static final List<CommunityMediaEntity.State> ACTIVE_UNATTACHED_STATES = List.of(
			CommunityMediaEntity.State.PENDING, CommunityMediaEntity.State.PROCESSING,
			CommunityMediaEntity.State.READY);

	private final CommunityProfileRepository profiles;
	private final CommunityMediaRepository media;
	private final CommunityMediaStorage storage;
	private final CommunityMediaProperties properties;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public CommunityMediaService(CommunityProfileRepository profiles, CommunityMediaRepository media,
			CommunityMediaStorage storage, CommunityMediaProperties properties, TransactionTemplate transactions,
			Clock clock) {
		this.profiles = profiles;
		this.media = media;
		this.storage = storage;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Transactional
	public UploadIntent createIntent(UUID subject, String idempotencyKey, CreateUploadIntentRequest request) {
		var key = validateIdempotencyKey(idempotencyKey);
		var input = validate(request);
		var fingerprint = fingerprint(input);
		var now = clock.instant();
		profiles.createIfAbsent(UUID.randomUUID(), subject, DEFAULT_DISPLAY_NAME, now);
		var owner = profiles.findByAccountSubjectForUpdate(subject)
				.filter(profile -> profile.status() == CommunityProfileEntity.Status.ACTIVE)
				.orElseThrow(CommunityApiException::communityAccessUnavailable);
		var replay = media.findByOwnerAndIdempotencyKeyForUpdate(owner.id(), key);
		if (replay.isPresent()) {
			if (!fingerprint.equals(replay.get().requestFingerprint())) {
				throw CommunityApiException.idempotencyKeyReused();
			}
			return uploadIntent(replay.get());
		}
		if (media.countUnattachedByOwnerAndStateIn(owner.id(), ACTIVE_UNATTACHED_STATES) >= MAX_UNATTACHED_MEDIA) {
			throw CommunityApiException.mediaLimitReached();
		}
		var id = UUID.randomUUID();
		var expiresAt = now.plus(properties.uploadIntentTtl());
		var entity = new CommunityMediaEntity(id, owner, input.mediaType(),
				"mentalbridge/community/" + owner.id() + "/" + id, input.mimeType(), input.sizeBytes(), expiresAt,
				key, fingerprint, now);
		media.saveAndFlush(entity);
		return uploadIntent(entity);
	}

	public MediaRecord finalizeUpload(UUID subject, UUID mediaId) {
		var snapshot = media.findOwnedById(mediaId, subject).orElseThrow(CommunityApiException::mediaNotFound);
		if (snapshot.state() != CommunityMediaEntity.State.PENDING
				&& snapshot.state() != CommunityMediaEntity.State.PROCESSING) {
			return record(snapshot);
		}
		if (snapshot.uploadExpiresAt() == null || !clock.instant().isBefore(snapshot.uploadExpiresAt())) {
			return transactions.execute(status -> {
				var current = media.findOwnedByIdForUpdate(mediaId, subject)
						.orElseThrow(CommunityApiException::mediaNotFound);
				if (current.state() == CommunityMediaEntity.State.PENDING
						|| current.state() == CommunityMediaEntity.State.PROCESSING) {
					current.expire(clock.instant());
				}
				return record(current);
			});
		}

		CommunityMediaStorage.StoredAsset asset;
		try {
			asset = storage.inspect(snapshot.storageKey(), snapshot.mediaType());
		}
		catch (CommunityMediaStorage.AssetMissingException exception) {
			throw CommunityApiException.mediaUploadMissing();
		}
		catch (CommunityMediaStorage.StorageUnavailableException exception) {
			throw CommunityApiException.mediaStorageUnavailable();
		}
		var validation = validateAsset(snapshot, asset);
		var deliveryUrl = validation.valid() ? storage.deliveryUrl(asset, snapshot.mediaType()) : null;
		return transactions.execute(status -> {
			var current = media.findOwnedByIdForUpdate(mediaId, subject)
					.orElseThrow(CommunityApiException::mediaNotFound);
			if (current.state() != CommunityMediaEntity.State.PENDING
					&& current.state() != CommunityMediaEntity.State.PROCESSING) {
				return record(current);
			}
			var now = clock.instant();
			if (validation.valid()) {
				var duration = asset.durationSeconds() == null ? null : (int) Math.ceil(asset.durationSeconds());
				current.markReady(deliveryUrl, asset.width(), asset.height(), duration, now);
			}
			else {
				current.reject(validation.reason(), now);
			}
			media.flush();
			return record(current);
		});
	}

	public void delete(UUID subject, UUID mediaId, long expectedVersion) {
		var stored = transactions.execute(status -> {
			var current = media.findOwnedByIdForUpdate(mediaId, subject)
					.orElseThrow(CommunityApiException::mediaNotFound);
			if (current.version() != expectedVersion) {
				throw CommunityApiException.mediaVersionMismatch();
			}
			if (current.post() != null) {
				throw CommunityApiException.mediaNotAttachable();
			}
			var reference = new StorageReference(current.id(), current.storageKey(), current.mediaType());
			current.delete(clock.instant());
			media.flush();
			return reference;
		});
		purge(stored);
	}

	@Scheduled(fixedDelayString = "${mentalbridge.community.media.cleanup-interval}")
	public void cleanupExpiredMedia() {
		var references = transactions.execute(status -> {
			var now = clock.instant();
			var items = media.findCleanupBatch(now, now.minus(properties.orphanRetention()), 50);
			return items.stream().map(item -> {
				if (item.state() != CommunityMediaEntity.State.DELETED
						&& item.state() != CommunityMediaEntity.State.EXPIRED) {
					item.expire(now);
				}
				return new StorageReference(item.id(), item.storageKey(), item.mediaType());
			}).toList();
		});
		if (references != null) {
			references.forEach(this::purge);
		}
	}

	private UploadIntent uploadIntent(CommunityMediaEntity entity) {
		if (entity.storageKey() == null || entity.uploadExpiresAt() == null) {
			throw CommunityApiException.mediaNotFound();
		}
		var authorization = storage.authorize(entity.storageKey(), entity.mediaType(), entity.createdAt().getEpochSecond());
		return new UploadIntent(entity.id(), entity.state(), authorization.uploadUrl(), entity.uploadExpiresAt(),
				authorization.fields(), entity.version());
	}

	private ValidatedInput validate(CreateUploadIntentRequest request) {
		var fileName = Normalizer.normalize(request.fileName(), Normalizer.Form.NFC).trim();
		var mimeType = request.mimeType().trim().toLowerCase(Locale.ROOT);
		var allowed = request.mediaType() == CommunityMediaEntity.Type.IMAGE ? IMAGE_MIME_TYPES : VIDEO_MIME_TYPES;
		var maximum = request.mediaType() == CommunityMediaEntity.Type.IMAGE
				? properties.maxImageBytes() : properties.maxVideoBytes();
		if (fileName.isEmpty() || fileName.codePointCount(0, fileName.length()) > 255
				|| fileName.codePoints().anyMatch(Character::isISOControl)
				|| !allowed.contains(mimeType) || request.sizeBytes() < 1 || request.sizeBytes() > maximum) {
			throw CommunityApiException.invalidMediaInput();
		}
		return new ValidatedInput(fileName, request.mediaType(), mimeType, request.sizeBytes());
	}

	private AssetValidation validateAsset(CommunityMediaEntity expected, CommunityMediaStorage.StoredAsset asset) {
		if (!expected.storageKey().equals(asset.storageKey())
				|| !"authenticated".equals(asset.deliveryType())
				|| !resourceType(expected.mediaType()).equals(asset.resourceType())) {
			return new AssetValidation(false, "PROVIDER_IDENTITY_MISMATCH");
		}
		if (expected.expectedSizeBytes() == null || expected.expectedSizeBytes() != asset.bytes()
				|| asset.width() < 1 || asset.height() < 1) {
			return new AssetValidation(false, "FILE_BOUNDS_MISMATCH");
		}
		if (!formatMatches(expected.expectedMimeType(), asset.format())) {
			return new AssetValidation(false, "UNSUPPORTED_FILE_SIGNATURE");
		}
		if (expected.mediaType() == CommunityMediaEntity.Type.VIDEO
				&& (asset.durationSeconds() == null || asset.durationSeconds() <= 0
						|| asset.durationSeconds() > properties.maxVideoDurationSeconds())) {
			return new AssetValidation(false, "VIDEO_DURATION_EXCEEDED");
		}
		return new AssetValidation(true, null);
	}

	private boolean formatMatches(String mimeType, String format) {
		return switch (mimeType) {
			case "image/jpeg" -> "jpg".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format);
			case "image/png" -> "png".equalsIgnoreCase(format);
			case "image/webp" -> "webp".equalsIgnoreCase(format);
			case "video/mp4" -> "mp4".equalsIgnoreCase(format);
			case "video/webm" -> "webm".equalsIgnoreCase(format);
			case "video/quicktime" -> "mov".equalsIgnoreCase(format);
			default -> false;
		};
	}

	private String resourceType(CommunityMediaEntity.Type type) {
		return type == CommunityMediaEntity.Type.IMAGE ? "image" : "video";
	}

	private void purge(StorageReference reference) {
		if (reference == null || reference.storageKey() == null) {
			return;
		}
		try {
			storage.delete(reference.storageKey(), reference.mediaType());
			transactions.executeWithoutResult(status -> media.findByIdForUpdate(reference.mediaId())
					.filter(item -> reference.storageKey().equals(item.storageKey()))
					.ifPresent(item -> item.clearStorageReference(clock.instant())));
		}
		catch (CommunityMediaStorage.StorageUnavailableException exception) {
			LOG.warn("Community media provider cleanup will be retried for media {}", reference.mediaId());
		}
	}

	private MediaRecord record(CommunityMediaEntity entity) {
		return new MediaRecord(entity.id(), entity.mediaType(), entity.state(), entity.version(), entity.createdAt(),
				entity.updatedAt());
	}

	private String validateIdempotencyKey(String value) {
		if (value == null || value.length() < 16 || value.length() > 128
				|| value.chars().anyMatch(character -> character < 33 || character > 126)) {
			throw CommunityApiException.invalidIdempotencyKey();
		}
		return value;
	}

	private String fingerprint(ValidatedInput input) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			add(digest, input.fileName());
			add(digest, input.mediaType().name());
			add(digest, input.mimeType());
			add(digest, Long.toString(input.sizeBytes()));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private void add(MessageDigest digest, String value) {
		var bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private record ValidatedInput(String fileName, CommunityMediaEntity.Type mediaType, String mimeType,
			long sizeBytes) {
	}

	private record AssetValidation(boolean valid, String reason) {
	}

	private record StorageReference(UUID mediaId, String storageKey, CommunityMediaEntity.Type mediaType) {
	}
}
