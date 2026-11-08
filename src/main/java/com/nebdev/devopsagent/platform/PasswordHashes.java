package com.nebdev.devopsagent.platform;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Hachage des mots de passe plateforme (BCrypt). Jamais de clair en stockage. */
public final class PasswordHashes {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private PasswordHashes() {}

    public static String hash(String raw) {
        requirePolicy(raw);
        return ENCODER.encode(raw);
    }

    public static boolean matches(String raw, String hash) {
        if (raw == null || hash == null || hash.isBlank()) {
            return false;
        }
        return ENCODER.matches(raw, hash);
    }

    public static void requirePolicy(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH || raw.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Mot de passe : entre " + MIN_LENGTH + " et " + MAX_LENGTH + " caractères.");
        }
        if (!raw.equals(raw.strip())) {
            throw new IllegalArgumentException("Mot de passe : pas d’espaces en début/fin.");
        }
    }

    public static void requireDifferent(String current, String next) {
        if (current != null && current.equals(next)) {
            throw new IllegalArgumentException("Choisis un mot de passe différent de l’actuel.");
        }
    }

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!#%+=";

    /** Mot de passe initial aléatoire (20 caractères), conforme à {@link #requirePolicy}. */
    public static String randomPassword() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
