package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/admin/specialist-profiles/documents")
public class AdminSpecialistCredentialDocumentController {

	private final SpecialistCredentialDocumentService documents;

	public AdminSpecialistCredentialDocumentController(SpecialistCredentialDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping("/pending")
	ResponseEntity<List<SpecialistCredentialDocumentService.DocumentView>> pending() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.pending());
	}

	@GetMapping("/specialists/{specialistId}")
	ResponseEntity<List<SpecialistCredentialDocumentService.DocumentView>> specialist(@PathVariable UUID specialistId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(documents.forAdmin(specialistId));
	}

	@GetMapping("/{documentId}/file")
	ResponseEntity<SpecialistCredentialDocumentController.DocumentLink> file(@PathVariable UUID documentId,
			@RequestParam(defaultValue = "false") boolean download) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
				new SpecialistCredentialDocumentController.DocumentLink(
						documents.viewUrl(null, documentId, true, download)));
	}

	@PostMapping("/{documentId}/{decision}")
	ResponseEntity<SpecialistCredentialDocumentService.DocumentView> review(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID documentId, @PathVariable String decision,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		if (!decision.equals("verify") && !decision.equals("reject")) return ResponseEntity.notFound().build();
		var result = documents.review(documentId, RequestIdentity.requiredVersion(ifMatch),
				RequestIdentity.subject(jwt), decision.equals("verify"));
		return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
	}
}
