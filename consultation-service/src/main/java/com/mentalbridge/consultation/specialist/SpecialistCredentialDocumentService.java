package com.mentalbridge.consultation.specialist;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SpecialistCredentialDocumentService {

	private static final long MAX_BYTES = 10 * 1024 * 1024;
	private static final Set<String> SUPPORTED_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

	private final SpecialistCredentialDocumentRepository documents;
	private final SpecialistProfileService profiles;
	private final SpecialistProfileRepository profileRepository;
	private final ObjectProvider<SpecialistCredentialStorage> storage;
	private final Clock clock;

	public SpecialistCredentialDocumentService(SpecialistCredentialDocumentRepository documents,
			SpecialistProfileService profiles, SpecialistProfileRepository profileRepository,
			ObjectProvider<SpecialistCredentialStorage> storage, Clock clock) {
		this.documents = documents;
		this.profiles = profiles;
		this.profileRepository = profileRepository;
		this.storage = storage;
		this.clock = clock;
	}

	public DocumentView upload(UUID specialistId, String title, String issuer, int issueYear,
			SpecialistCredentialDocumentEntity.Type type, MultipartFile file) {
		profiles.getOwn(specialistId);
		if (title == null || title.isBlank() || title.length() > 160 || issuer == null || issuer.isBlank()
				|| issuer.length() > 160 || issueYear < 1900 || issueYear > clock.instant().atZone(java.time.ZoneOffset.UTC).getYear()
				|| type == null || file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
			throw invalidDocument();
		}
		byte[] contents;
		try {
			contents = file.getBytes();
		}
		catch (IOException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_FILE_UNREADABLE", "Could not read credential file");
		}
		String mediaType = detectType(contents);
		if (!SUPPORTED_TYPES.contains(mediaType) || !mediaType.equals(file.getContentType())) throw invalidDocument();
		String fileName = safeFileName(file.getOriginalFilename());
		if (fileName.isBlank() || fileName.length() > 180) throw invalidDocument();
		var fileStorage = requireStorage();
		var id = UUID.randomUUID();
		var key = "specialist-credentials/" + specialistId + "/" + id
				+ ("application/pdf".equals(mediaType) ? ".pdf" : "");
		SpecialistCredentialStorage.StoredFile stored;
		try {
			stored = fileStorage.upload(contents, key, mediaType);
		}
		catch (CloudinarySpecialistCredentialStorage.StorageFailure exception) {
			throw storageUnavailable();
		}
		try {
			var document = new SpecialistCredentialDocumentEntity(id, specialistId, title, issuer,
					issueYear, type, fileName, mediaType, stored.bytes(), stored.storageKey(), stored.format(),
					clock.instant());
			return DocumentView.from(documents.saveAndFlush(document));
		}
		catch (RuntimeException exception) {
			try {
				fileStorage.delete(stored.storageKey(), mediaType);
			}
			catch (RuntimeException cleanupFailure) {
				exception.addSuppressed(cleanupFailure);
			}
			throw exception;
		}
	}

	@Transactional(readOnly = true)
	public List<DocumentView> own(UUID specialistId) {
		profiles.getOwn(specialistId);
		return documents.findTop100BySpecialistAccountIdOrderByCreatedAtDescIdDesc(specialistId)
				.stream().map(DocumentView::from).toList();
	}

	@Transactional(readOnly = true)
	public List<DocumentView> forAdmin(UUID specialistId) {
		return documents.findTop100BySpecialistAccountIdOrderByCreatedAtDescIdDesc(specialistId)
				.stream().map(DocumentView::from).toList();
	}

	@Transactional(readOnly = true)
	public List<DocumentView> pending() {
		return documents.findTop100ByVerificationStatusOrderByCreatedAtAscIdAsc(
				SpecialistCredentialDocumentEntity.Status.PENDING).stream().map(DocumentView::from).toList();
	}

	@Transactional(readOnly = true)
	public List<VerifiedBadge> publicBadges(UUID specialistId) {
		var profile = profileRepository.findById(specialistId).orElseThrow(this::notFound);
		if (profile.approvalStatus() != SpecialistApprovalStatus.APPROVED) throw notFound();
		return documents.findTop100BySpecialistAccountIdAndVerificationStatusAndBadgeVisibleTrueOrderByCreatedAtAscIdAsc(
				specialistId, SpecialistCredentialDocumentEntity.Status.VERIFIED)
				.stream().map(value -> new VerifiedBadge(value.title(), value.credentialType())).toList();
	}

	@Transactional(readOnly = true)
	public String viewUrl(UUID specialistId, UUID documentId, boolean admin, boolean attachment) {
		var document = find(documentId);
		if (!admin && !document.specialistAccountId().equals(specialistId)) throw notFound();
		try {
			return requireStorage().viewUrl(document.storageKey(), document.storageFormat(), document.mediaType(),
					clock.instant().plusSeconds(300), attachment);
		}
		catch (CloudinarySpecialistCredentialStorage.StorageFailure exception) {
			throw storageUnavailable();
		}
	}

	@Transactional
	public DocumentView setBadge(UUID specialistId, UUID documentId, long expectedVersion, boolean visible) {
		var document = find(documentId);
		if (!document.specialistAccountId().equals(specialistId)) throw notFound();
		checkVersion(document, expectedVersion);
		if (visible && document.verificationStatus() != SpecialistCredentialDocumentEntity.Status.VERIFIED) {
			throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_NOT_VERIFIED",
					"Only verified credentials may appear on a public profile");
		}
		if (document.badgeVisible() != visible) document.showBadge(visible, clock.instant());
		return DocumentView.from(documents.saveAndFlush(document));
	}

	@Transactional
	public DocumentView review(UUID documentId, long expectedVersion, UUID adminId, boolean verified) {
		var document = find(documentId);
		checkVersion(document, expectedVersion);
		if (document.verificationStatus() != SpecialistCredentialDocumentEntity.Status.PENDING) {
			throw new ApiException(HttpStatus.CONFLICT, "CREDENTIAL_ALREADY_REVIEWED",
					"Credential has already been reviewed");
		}
		document.review(verified ? SpecialistCredentialDocumentEntity.Status.VERIFIED
				: SpecialistCredentialDocumentEntity.Status.REJECTED, adminId, clock.instant());
		return DocumentView.from(documents.saveAndFlush(document));
	}

	private SpecialistCredentialDocumentEntity find(UUID documentId) {
		return documents.findById(documentId).orElseThrow(this::notFound);
	}

	private SpecialistCredentialStorage requireStorage() {
		var provider = storage.getIfAvailable();
		if (provider == null) throw storageUnavailable();
		return provider;
	}

	private void checkVersion(SpecialistCredentialDocumentEntity document, long expectedVersion) {
		if (document.version() != expectedVersion) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "CREDENTIAL_VERSION_MISMATCH",
					"Credential was changed; load its latest version");
		}
	}

	private String safeFileName(String supplied) {
		return supplied == null ? "" : supplied.replace('\\', '/').substring(supplied.replace('\\', '/').lastIndexOf('/') + 1).strip();
	}

	private String detectType(byte[] bytes) {
		if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
				&& bytes[3] == 'F' && bytes[4] == '-') return "application/pdf";
		if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
				&& (bytes[2] & 0xff) == 0xff) return "image/jpeg";
		if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
				&& bytes[3] == 'G' && bytes[4] == 0x0d && bytes[5] == 0x0a
				&& bytes[6] == 0x1a && bytes[7] == 0x0a) return "image/png";
		return "";
	}

	private ApiException invalidDocument() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_DOCUMENT",
				"Credential details or file are invalid");
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "Credential was not found");
	}

	private ApiException storageUnavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CREDENTIAL_STORAGE_UNAVAILABLE",
				"Credential storage is unavailable");
	}

	public record DocumentView(UUID id, UUID specialistAccountId, String title, String issuer, int issueYear,
			SpecialistCredentialDocumentEntity.Type credentialType, String fileName, String mediaType, long fileBytes,
			SpecialistCredentialDocumentEntity.Status verificationStatus, boolean badgeVisible, Instant reviewedAt,
			Instant createdAt, long version) {
		static DocumentView from(SpecialistCredentialDocumentEntity value) {
			return new DocumentView(value.id(), value.specialistAccountId(), value.title(), value.issuer(),
					value.issueYear(), value.credentialType(), value.fileName(), value.mediaType(), value.fileBytes(),
					value.verificationStatus(), value.badgeVisible(), value.reviewedAt(), value.createdAt(),
					value.version());
		}
	}

	public record VerifiedBadge(String title, SpecialistCredentialDocumentEntity.Type credentialType) { }
}
