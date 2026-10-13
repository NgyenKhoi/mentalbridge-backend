package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.community.configuration.CommunityMediaProperties;

class CommunityAvatarServiceTests {

	private final Instant now = Instant.parse("2026-10-13T10:00:00Z");
	private final UUID subject = UUID.randomUUID();
	private final CommunityProfileRepository profiles = mock(CommunityProfileRepository.class);
	private final CommunityMediaRepository media = mock(CommunityMediaRepository.class);
	private final CommunityProfileService service = new CommunityProfileService(profiles, media,
			Clock.fixed(now, ZoneOffset.UTC));
	private final CommunityProfileEntity profile = new CommunityProfileEntity(UUID.randomUUID(), subject,
			"Mầm xanh", AvatarPreset.LEAF, now);

	@Test
	void validatesAnOwnedReadyPhotoAndKeepsThePresetFallback() {
		var image = image(CommunityMediaEntity.Type.IMAGE, true);
		when(profiles.findByAccountSubjectForUpdate(subject)).thenReturn(Optional.of(profile));
		when(media.findOwnedByIdForUpdate(image.id(), subject)).thenReturn(Optional.of(image));
		when(profiles.saveAndFlush(profile)).thenReturn(profile);

		var saved = service.replaceAvatar(subject, 0L, image.id());

		assertThat(saved.avatarImage().mediaId()).isEqualTo(image.id());
		assertThat(saved.avatarPreset()).isEqualTo(AvatarPreset.LEAF);
		assertThat(profile.avatarMedia()).isSameAs(image);
		assertThat(service.replaceAvatar(subject, 0L, image.id()).avatarImage()).isEqualTo(saved.avatarImage());
		assertThat(service.replaceAvatar(subject, 0L, null).avatarImage()).isNull();
		assertThat(profile.avatarPreset()).isEqualTo(AvatarPreset.LEAF);
	}

	@Test
	void rejectsMissingVersionStaleDifferentSelectionAndUnverifiedOrVideoMedia() {
		when(profiles.findByAccountSubjectForUpdate(subject)).thenReturn(Optional.of(profile));
		assertThatThrownBy(() -> service.replaceAvatar(subject, null, null)).hasMessageContaining("If-Match");
		assertThatThrownBy(() -> service.replaceAvatar(subject, 1L, UUID.randomUUID()))
				.hasMessageContaining("changed since it was read");
		for (var image : java.util.List.of(image(CommunityMediaEntity.Type.IMAGE, false),
				image(CommunityMediaEntity.Type.VIDEO, true))) {
			when(media.findOwnedByIdForUpdate(image.id(), subject)).thenReturn(Optional.of(image));
			assertThatThrownBy(() -> service.replaceAvatar(subject, 0L, image.id()))
					.isInstanceOf(com.mentalbridge.community.shared.CommunityApiException.class);
		}
		assertThat(profile.avatarImage()).isNull();
		verify(profiles, never()).saveAndFlush(any());
	}

	@Test
	void unavailableMediaFallsBackWithoutDisclosingAnImage() {
		var image = image(CommunityMediaEntity.Type.IMAGE, true);
		profile.replaceAvatar(image, now);
		assertThat(profile.avatarImage()).isNotNull();
		image.delete(now);
		assertThat(profile.avatarImage()).isNull();
		assertThat(profile.avatarPreset()).isEqualTo(AvatarPreset.LEAF);
	}

	@Test
	void rejectsReadyImagesWithoutUploadProvenanceOrPositiveKnownDimensions() {
		when(profiles.findByAccountSubjectForUpdate(subject)).thenReturn(Optional.of(profile));
		var legacyImage = image(CommunityMediaEntity.Type.IMAGE, true);
		legacyImage.clearStorageReference(now);
		var missingDimensions = image(CommunityMediaEntity.Type.IMAGE, true);
		org.springframework.test.util.ReflectionTestUtils.setField(missingDimensions, "width", null);
		var invalidDimensions = image(CommunityMediaEntity.Type.IMAGE, true);
		invalidDimensions.markReady("https://res.cloudinary.test/avatar.webp", 0, 800, null, now);
		for (var image : java.util.List.of(legacyImage, missingDimensions, invalidDimensions)) {
			when(media.findOwnedByIdForUpdate(image.id(), subject)).thenReturn(Optional.of(image));
			assertThatThrownBy(() -> service.replaceAvatar(subject, 0L, image.id()))
					.isInstanceOf(com.mentalbridge.community.shared.CommunityApiException.class);
		}
		verify(profiles, never()).saveAndFlush(any());
	}

	@Test
	void cleanupRechecksSelectionAfterTheCandidateWasLocked() {
		var image = image(CommunityMediaEntity.Type.IMAGE, true);
		var storage = mock(CommunityMediaStorage.class);
		var transactions = mock(TransactionTemplate.class);
		when(transactions.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(new SimpleTransactionStatus());
		});
		when(media.findCleanupBatch(now, now.minus(Duration.ofHours(24)), 50)).thenReturn(java.util.List.of(image));
		when(profiles.existsByAvatarMediaId(image.id())).thenReturn(true);
		var mediaService = new CommunityMediaService(profiles, media, storage,
				new CommunityMediaProperties(10_000, 50_000, 60, Duration.ofMinutes(10), Duration.ofHours(24),
						Duration.ofHours(1)), transactions, Clock.fixed(now, ZoneOffset.UTC));

		mediaService.cleanupExpiredMedia();

		assertThat(image.state()).isEqualTo(CommunityMediaEntity.State.READY);
		org.mockito.Mockito.verifyNoInteractions(storage);
	}

	private CommunityMediaEntity image(CommunityMediaEntity.Type type, boolean ready) {
		var image = new CommunityMediaEntity(UUID.randomUUID(), profile, type, "test-key", "image/jpeg",
				1000, now.plusSeconds(600), UUID.randomUUID().toString(), "f".repeat(64), now);
		if (ready) image.markReady("https://res.cloudinary.test/avatar.webp", 800, 800, null, now);
		return image;
	}
}
