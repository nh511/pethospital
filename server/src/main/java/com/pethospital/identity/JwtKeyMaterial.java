package com.pethospital.identity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

public record JwtKeyMaterial(
    RSAPrivateKey privateKey,
    RSAPublicKey publicKey,
    String keyId,
    boolean ephemeral
) {
    private static final String PRIVATE_FILE = "jwt-private.pem";
    private static final String PUBLIC_FILE = "jwt-public.pem";

    public static JwtKeyMaterial load(
        ResourceLoader resourceLoader,
        String privateLocation,
        String publicLocation,
        boolean allowEphemeral
    ) {
        String privateValue = trimToNull(privateLocation);
        String publicValue = trimToNull(publicLocation);
        if ((privateValue == null) != (publicValue == null)) {
            throw new IllegalStateException("Both JWT private and public key locations must be configured");
        }
        if (privateValue != null) {
            return read(resourceLoader.getResource(privateValue), resourceLoader.getResource(publicValue), false);
        }

        Path localDirectory = findLocalKeyDirectory();
        if (localDirectory != null) {
            return read(
                resourceLoader.getResource(localDirectory.resolve(PRIVATE_FILE).toUri().toString()),
                resourceLoader.getResource(localDirectory.resolve(PUBLIC_FILE).toUri().toString()),
                false
            );
        }
        if (allowEphemeral) {
            return generateEphemeral();
        }
        throw new IllegalStateException(
            "JWT key material is missing. Run tools/generate-jwt-key.ps1 or configure "
                + "JWT_PRIVATE_KEY_LOCATION and JWT_PUBLIC_KEY_LOCATION"
        );
    }

    private static JwtKeyMaterial read(Resource privateResource, Resource publicResource, boolean ephemeral) {
        Converter<InputStream, RSAPrivateKey> privateConverter = RsaKeyConverters.pkcs8();
        Converter<InputStream, RSAPublicKey> publicConverter = RsaKeyConverters.x509();
        try (InputStream privateInput = privateResource.getInputStream();
             InputStream publicInput = publicResource.getInputStream()) {
            RSAPrivateKey privateKey = privateConverter.convert(privateInput);
            RSAPublicKey publicKey = publicConverter.convert(publicInput);
            if (privateKey == null || publicKey == null) {
                throw new IllegalStateException("JWT key conversion returned no key");
            }
            if (!privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException("JWT private and public keys do not form a pair");
            }
            return new JwtKeyMaterial(privateKey, publicKey, keyId(publicKey), ephemeral);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to load JWT key material", exception);
        }
    }

    private static JwtKeyMaterial generateEphemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            return new JwtKeyMaterial(privateKey, publicKey, keyId(publicKey), true);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate test JWT key material", exception);
        }
    }

    private static Path findLocalKeyDirectory() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path[] candidates = current.getFileName() != null && "server".equalsIgnoreCase(current.getFileName().toString())
            ? new Path[] {current.resolve(".local"), current.getParent().resolve(".local")}
            : new Path[] {current.resolve(".local")};
        for (Path candidate : candidates) {
            boolean privateExists = Files.isRegularFile(candidate.resolve(PRIVATE_FILE));
            boolean publicExists = Files.isRegularFile(candidate.resolve(PUBLIC_FILE));
            if (privateExists != publicExists) {
                throw new IllegalStateException("Local JWT key pair is incomplete in " + candidate);
            }
            if (privateExists) {
                return candidate;
            }
        }
        return null;
    }

    private static String keyId(RSAPublicKey publicKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 16);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to derive JWT key id", exception);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
