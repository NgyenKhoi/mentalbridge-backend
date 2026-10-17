package com.mentalbridge.identity.reporting;

import java.util.UUID;

final class InvalidPlatformReportRequestException extends RuntimeException {
	InvalidPlatformReportRequestException(String message) { super(message); }
}

final class PlatformReportNotFoundException extends RuntimeException {
	PlatformReportNotFoundException(UUID id) { super("Platform report " + id + " was not found"); }
}

final class PlatformReportNotDownloadableException extends RuntimeException {
	PlatformReportNotDownloadableException(String message) { super(message); }
}

final class PlatformReportArtifactExpiredException extends RuntimeException {
	PlatformReportArtifactExpiredException(UUID id) { super("Platform report artifact " + id + " has expired"); }
}
