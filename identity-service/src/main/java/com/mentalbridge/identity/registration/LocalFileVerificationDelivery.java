package com.mentalbridge.identity.registration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.mentalbridge.identity.configuration.VerificationDeliveryProperties;

@Component
@Profile({ "local", "dev" })
@ConditionalOnProperty(prefix = "mentalbridge.identity.verification-delivery", name = "mode", havingValue = "local-file")
public class LocalFileVerificationDelivery implements VerificationDelivery {

	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE);

	private final VerificationDeliveryProperties properties;

	public LocalFileVerificationDelivery(VerificationDeliveryProperties properties) {
		if (properties.verificationUrl() == null || properties.passwordRecoveryUrl() == null
				|| properties.localDirectory() == null) {
			throw new IllegalStateException("Local verification delivery configuration is incomplete");
		}
		this.properties = properties;
	}

	@Override
	public void requestDelivery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {
		write(accountId, challenge, properties.verificationUrl(), ".verification-url");
	}

	@Override
	public void requestPasswordRecovery(UUID accountId, String normalizedEmail, String challenge, UUID correlationId) {
		write(accountId, challenge, properties.passwordRecoveryUrl(), ".password-recovery-url");
	}

	private void write(UUID accountId, String challenge, java.net.URI baseUrl, String suffix) {
		var directory = properties.localDirectory().toAbsolutePath().normalize();
		var destination = directory.resolve(accountId + suffix).normalize();
		if (!destination.getParent().equals(directory)) {
			throw new IllegalStateException("Local verification delivery path is invalid");
		}
		var link = UriComponentsBuilder.fromUri(baseUrl).queryParam("challenge", challenge).build()
				.encode().toUriString();
		try {
			Files.createDirectories(directory);
			restrict(directory, DIRECTORY_PERMISSIONS);
			Files.writeString(destination, link + System.lineSeparator(), StandardCharsets.UTF_8);
			restrict(destination, FILE_PERMISSIONS);
		}
		catch (IOException exception) {
			throw new IllegalStateException("Unable to write local verification delivery", exception);
		}
	}

	private void restrict(Path path, Set<PosixFilePermission> permissions) throws IOException {
		var attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class);
		if (attributes != null) {
			attributes.setPermissions(permissions);
		}
	}

}
