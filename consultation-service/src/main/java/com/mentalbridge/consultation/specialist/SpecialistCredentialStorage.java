package com.mentalbridge.consultation.specialist;

import java.time.Instant;

interface SpecialistCredentialStorage {
	StoredFile upload(byte[] contents, String storageKey, String mediaType);
	String viewUrl(String storageKey, String format, String mediaType, Instant expiresAt, boolean attachment);
	void delete(String storageKey, String mediaType);

	record StoredFile(String storageKey, String format, long bytes) { }
}
