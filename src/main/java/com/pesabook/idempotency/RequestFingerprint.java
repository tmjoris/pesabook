package com.pesabook.idempotency;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes a request body so a later request carrying the same key can be checked
 * against it without keeping the original body around.
 */
@Component
public class RequestFingerprint {

    public String of(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body == null
                    ? new byte[0]
                    : body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required and was not available", e);
        }
    }
}
