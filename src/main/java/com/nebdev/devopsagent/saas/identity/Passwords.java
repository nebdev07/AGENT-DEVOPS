package com.nebdev.devopsagent.saas.identity;

import com.nebdev.devopsagent.saas.shared.ApiException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Politique de mot de passe et hachage BCrypt (coût 12). */
@Component
public class Passwords {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    private static final Set<String> TOO_COMMON = Set.of(
            "password1234", "motdepasse12", "azertyuiop12", "qwertyuiop12", "123456789012", "vpsautopilot!changeme",
            "changeme1234", "administrator");

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    /** Hash factice : un e-mail inconnu coûte le même temps qu'un mauvais mot de passe (pas d'énumération). */
    private final String dummyHash = encoder.encode("dummy-password-for-timing");

    public String hash(String raw, String email) {
        requirePolicy(raw, email);
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null || hash.isBlank()) {
            encoder.matches("x", dummyHash);
            return false;
        }
        return encoder.matches(raw, hash);
    }

    public void burn(String raw) {
        encoder.matches(raw == null ? "" : raw, dummyHash);
    }

    public static void requirePolicy(String raw, String email) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw ApiException.badRequest("password_length", MIN_LENGTH, MAX_LENGTH);
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (TOO_COMMON.contains(lower) || raw.chars().distinct().count() < 5) {
            throw ApiException.badRequest("password_too_weak");
        }
        if (email != null && !email.isBlank()) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) {
                throw ApiException.badRequest("password_contains_email");
            }
        }
    }
}
