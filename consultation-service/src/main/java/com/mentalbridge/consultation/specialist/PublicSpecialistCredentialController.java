package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/specialists/{specialistId}/credential-badges")
public class PublicSpecialistCredentialController {

	private final SpecialistCredentialDocumentService documents;

	public PublicSpecialistCredentialController(SpecialistCredentialDocumentService documents) {
		this.documents = documents;
	}

	@GetMapping
	List<SpecialistCredentialDocumentService.VerifiedBadge> list(@PathVariable UUID specialistId) {
		return documents.publicBadges(specialistId);
	}
}
