package com.morningmission.app;

import android.content.SharedPreferences;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The grown-ups PIN.
 *
 * <p>Moved out of MainActivity unchanged during the visual rewrite, and deliberately not
 * touched otherwise. PBKDF2-HMAC-SHA256, a random 16-byte salt, 120,000 iterations, a
 * 256-bit result, and a constant-time comparison. The PIN itself is never stored.
 */
final class PinSecurity {

    private PinSecurity() {}

    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    static boolean hasPin(SharedPreferences prefs) {
        return prefs.contains("pin_hash") && prefs.contains("pin_salt");
    }

    static void savePin(SharedPreferences prefs, String pin) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(pin, salt);
            prefs.edit()
                 .putString("pin_salt", Base64.getEncoder().encodeToString(salt))
                 .putString("pin_hash", Base64.getEncoder().encodeToString(hash))
                 .apply();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static boolean verify(SharedPreferences prefs, String pin) {
        try {
            if (!hasPin(prefs)) return false;
            byte[] salt = Base64.getDecoder().decode(prefs.getString("pin_salt", ""));
            byte[] expected = Base64.getDecoder().decode(prefs.getString("pin_hash", ""));
            return MessageDigest.isEqual(expected, derive(pin, salt));
        } catch (Exception e) {
            return false;
        }
    }

    static byte[] derive(String pin, byte[] salt) throws Exception {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS))
                .getEncoded();
    }
}
