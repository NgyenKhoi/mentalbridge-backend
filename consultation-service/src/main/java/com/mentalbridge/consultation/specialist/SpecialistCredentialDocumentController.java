package com.mentalbridge.consultation.specialist;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/specialist-profile/documents")
public class SpecialistCredentialDocumentController {

	private final SpecialistCredentialDocumentService documents;

	public SpecialistCredentialDocumentController(SpecialistCredentialDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping
	ResponseEntity<List<SpecialistCredentialDocumentService.DocumentView>> own(@AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(documents.own(RequestIdentity.subject(jwt)));
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<SpecialistCredentialDocumentService.DocumentView> upload(@AuthenticationPrincipal Jwt jwt,
			@RequestParam String title, @RequestParam String issuer, @RequestParam int issueYear,
			@RequestParam SpecialistCredentialDocumentEntity.Type credentialType,
			@RequestParam MultipartFile file) {
		var result = documents.upload(RequestIdentity.subject(jwt), title, issuer, issueYear, credentialType, file);
		return ResponseEntity.created(URI.create("/api/v1/specialist-profile/documents/" + result.id()))
				.eTag(Long.toString(result.version())).body(result);
	}

	@GetMapping("/{documentId}/file")
	ResponseEntity<DocumentLink> file(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID documentId,
			@RequestParam(defaultValue = "false") boolean download) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
				new DocumentLink(documents.viewUrl(RequestIdentity.subject(jwt), documentId, false, download)));
	}

	@PutMapping("/{documentId}/badge")
	ResponseEntity<SpecialistCredentialDocumentService.DocumentView> badge(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID documentId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@Valid @RequestBody BadgeRequest request) {
		var result = documents.setBadge(RequestIdentity.subject(jwt), documentId,
				RequestIdentity.requiredVersion(ifMatch), request.visible());
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
	}

	record BadgeRequest(@NotNull Boolean visible) { }
	record DocumentLink(String url) { }
}
