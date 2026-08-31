import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;

public final class GenerateLocalIdentitySecrets {

    private GenerateLocalIdentitySecrets() {
    }

    public static void main(String[] arguments) throws Exception {
        var outputDirectory = Path.of(arguments.length > 0 ? arguments[0] : ".local/identity-secrets")
                .toAbsolutePath().normalize();
        var keySize = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 2048;
        if (keySize < 2048 || keySize > 8192) {
            throw new IllegalArgumentException("RSA key size must be between 2048 and 8192 bits");
        }

        Files.createDirectories(outputDirectory);

        var keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize, new SecureRandom());
        var keyPair = keyPairGenerator.generateKeyPair();

        var privateKey = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        var publicKey = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        var encryptionKeyBytes = new byte[32];
        new SecureRandom().nextBytes(encryptionKeyBytes);

        var privateKeyPath = outputDirectory.resolve("jwt-private-key.pem");
        var publicKeyPath = outputDirectory.resolve("jwt-public-key.pem");
        var environmentPath = outputDirectory.resolve("identity-secrets.env");

        Files.writeString(privateKeyPath, privateKey, StandardCharsets.US_ASCII);
        Files.writeString(publicKeyPath, publicKey, StandardCharsets.US_ASCII);
        Files.writeString(environmentPath, String.join(System.lineSeparator(),
                "IDENTITY_JWT_PRIVATE_KEY=" + dotenvPem(privateKey),
                "IDENTITY_JWT_PUBLIC_KEY=" + dotenvPem(publicKey),
                "IDENTITY_ENCRYPTION_KEY=" + Base64.getEncoder().encodeToString(encryptionKeyBytes))
                + System.lineSeparator(), StandardCharsets.UTF_8);

        restrictPermissions(privateKeyPath);
        restrictPermissions(environmentPath);

        System.out.println("Generated local Identity secrets in " + outputDirectory);
        System.out.println("Copy the three entries from " + environmentPath + " into identity-service/.env.");
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----" + System.lineSeparator()
                + Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(encoded)
                + System.lineSeparator() + "-----END " + type + "-----" + System.lineSeparator();
    }

    private static String dotenvPem(String pem) {
        return pem.stripTrailing().replace("\r\n", "\n").replace("\n", "\\n");
    }

    private static void restrictPermissions(Path path) throws Exception {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
        catch (UnsupportedOperationException ignored) {
        }
    }
}
