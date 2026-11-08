package com.nebdev.devopsagent.saas.shared;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Chiffrement par enveloppe (ADR-051) : une clé de données (DEK, AES-256) par organisation, elle-même
 * chiffrée par la clé maîtresse (KEK) fournie hors base ({@code AUTOPILOT_MASTER_KEY}). AES-GCM avec
 * données associées (AAD) qui lient chaque valeur à son organisation et à son usage : un blob copié
 * d'une ligne à une autre ne se déchiffre pas.
 *
 * <p>Format : {@code [version=1][iv 12 octets][texte chiffré + tag 16 octets]}.
 */
@Service
public class CryptoService {

    private static final byte VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKey kek;
    private final String kekId;

    @org.springframework.beans.factory.annotation.Autowired
    public CryptoService(SaasProperties properties) {
        this(properties.getCrypto().getMasterKey(), properties.getCrypto().getMasterKeyId());
    }

    public CryptoService(String masterKeyBase64, String kekId) {
        byte[] raw = decodeMasterKey(masterKeyBase64);
        this.kek = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        this.kekId = kekId == null || kekId.isBlank() ? "k1" : kekId;
    }

    static byte[] decodeMasterKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(
                    "AUTOPILOT_MASTER_KEY manquante : 32 octets en base64 (openssl rand -base64 32).");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("AUTOPILOT_MASTER_KEY n'est pas du base64 valide.");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("AUTOPILOT_MASTER_KEY doit faire 32 octets (reçu " + raw.length + ").");
        }
        return raw;
    }

    public String kekId() {
        return kekId;
    }

    public byte[] newWrappedDek(String orgAad) {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, random);
            byte[] dek = gen.generateKey().getEncoded();
            try {
                return seal(kek, dek, "dek|" + orgAad);
            } finally {
                Arrays.fill(dek, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Génération de clé impossible", e);
        }
    }

    public byte[] encrypt(byte[] wrappedDek, String orgAad, String purposeAad, byte[] plaintext) {
        byte[] dek = open(kek, wrappedDek, "dek|" + orgAad);
        try {
            return seal(new SecretKeySpec(dek, "AES"), plaintext, orgAad + "|" + purposeAad);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decrypt(byte[] wrappedDek, String orgAad, String purposeAad, byte[] ciphertext) {
        byte[] dek = open(kek, wrappedDek, "dek|" + orgAad);
        try {
            return open(new SecretKeySpec(dek, "AES"), ciphertext, orgAad + "|" + purposeAad);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Chiffrement plateforme (secrets MFA des utilisateurs) : directement par la KEK. */
    public byte[] encryptPlatform(String purposeAad, byte[] plaintext) {
        return seal(kek, plaintext, "platform|" + purposeAad);
    }

    public byte[] decryptPlatform(String purposeAad, byte[] ciphertext) {
        return open(kek, ciphertext, "platform|" + purposeAad);
    }

    private byte[] seal(SecretKey key, byte[] plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plaintext);
            return ByteBuffer.allocate(1 + IV_LEN + ct.length).put(VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    private byte[] open(SecretKey key, byte[] blob, String aad) {
        if (blob == null || blob.length < 1 + IV_LEN + 16 || blob[0] != VERSION) {
            throw new IllegalStateException("Valeur chiffrée invalide ou version inconnue.");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(blob, 1 + IV_LEN, blob.length - 1 - IV_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Déchiffrement refusé (clé ou contexte incorrect).");
        }
    }
}
