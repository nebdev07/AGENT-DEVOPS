package com.nebdev.devopsagent.saas.shared;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Coffre scellé pour le runner séparé (ADR-055). Chiffrement hybride : une clé AES-256 aléatoire par valeur,
 * enveloppée par la clé publique RSA du runner (OAEP SHA-256), puis AES-GCM avec données associées (AAD) qui lient
 * la valeur à son organisation et à son usage.
 *
 * <p>Le control plane ne détient que la clé <b>publique</b> : il peut sceller (générer, importer une clé SSH),
 * jamais rouvrir. Seul le runner, qui détient la clé privée, déchiffre — au moment d'exécuter.
 *
 * <p>Format : {@code [version=2][longueur u16][clé AES enveloppée][iv 12][texte chiffré + tag 16]}.
 */
public final class SealedBox {

    public static final byte VERSION = 2;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int MIN_RSA_BITS = 3072;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT);

    private SealedBox() {}

    /** AAD d'une clé SSH : identique côté control plane (scellement) et runner (ouverture). */
    public static String credentialAad(java.util.UUID orgId, java.util.UUID credentialId) {
        return orgId + "|credential|" + credentialId;
    }

    public static String secretAad(java.util.UUID orgId, String name) {
        return orgId + "|secret|" + name;
    }

    public static String gitAad(java.util.UUID orgId, java.util.UUID gitCredentialId) {
        return orgId + "|git|" + gitCredentialId;
    }

    public static String stagingAad(java.util.UUID orgId, java.util.UUID stagingId) {
        return orgId + "|staging|" + stagingId;
    }

    public static boolean isSealed(byte[] blob) {
        return blob != null && blob.length > 0 && blob[0] == VERSION;
    }

    public static byte[] seal(PublicKey runnerKey, String aad, byte[] plaintext) {
        byte[] raw = null;
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256, RANDOM);
            SecretKey key = gen.generateKey();
            raw = key.getEncoded();
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, runnerKey, OAEP);
            byte[] wrapped = rsa.doFinal(raw);
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            gcm.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = gcm.doFinal(plaintext);
            return ByteBuffer.allocate(1 + 2 + wrapped.length + IV_LEN + ct.length)
                    .put(VERSION).putShort((short) wrapped.length).put(wrapped).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Scellement impossible", e);
        } finally {
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    public static byte[] open(PrivateKey runnerKey, String aad, byte[] blob) {
        if (!isSealed(blob) || blob.length < 3) {
            throw new IllegalStateException("Valeur scellée invalide ou version inconnue.");
        }
        ByteBuffer in = ByteBuffer.wrap(blob);
        in.get();
        int len = Short.toUnsignedInt(in.getShort());
        if (len <= 0 || blob.length < 3 + len + IV_LEN + 16) {
            throw new IllegalStateException("Valeur scellée tronquée.");
        }
        byte[] wrapped = new byte[len];
        in.get(wrapped);
        byte[] raw = null;
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, runnerKey, OAEP);
            raw = rsa.doFinal(wrapped);
            int offset = 3 + len;
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(raw, "AES"), new GCMParameterSpec(TAG_BITS, blob, offset, IV_LEN));
            gcm.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return gcm.doFinal(blob, offset + IV_LEN, blob.length - offset - IV_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ouverture refusée (clé du runner ou contexte incorrect).");
        } finally {
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    // ───────────── Clés (PEM standard : openssl genpkey / openssl pkey -pubout) ─────────────

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(MIN_RSA_BITS, RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PublicKey parsePublicKey(String pem) {
        try {
            byte[] der = pemBody(pem, "PUBLIC KEY");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            requireStrength(key.getModulus().bitLength());
            return key;
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("Clé publique du runner invalide (PEM « PUBLIC KEY » RSA attendu).");
        }
    }

    public static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] der = pemBody(pem, "PRIVATE KEY");
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (key instanceof RSAPrivateCrtKey rsa) {
                requireStrength(rsa.getModulus().bitLength());
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Clé privée du runner invalide (PEM PKCS#8 « PRIVATE KEY » RSA attendu).");
        }
    }

    /** Clé publique dérivée de la clé privée (contrôle de cohérence au démarrage du runner). */
    public static PublicKey publicOf(PrivateKey key) {
        if (!(key instanceof RSAPrivateCrtKey rsa)) {
            throw new IllegalStateException("Clé privée RSA CRT attendue.");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Identifiant court de la clé publique (journal, audit, contrôle de correspondance). */
    public static String keyId(PublicKey key) {
        return "rk_" + Hashing.sha256Hex(new String(Base64.getEncoder().encode(key.getEncoded()), StandardCharsets.US_ASCII))
                .substring(0, 16);
    }

    public static String toPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static String toPem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static byte[] pemBody(String pem, String type) {
        if (pem == null || !pem.contains("-----BEGIN " + type + "-----")) {
            throw new IllegalStateException("PEM « " + type + " » attendu.");
        }
        String body = pem.substring(pem.indexOf("-----BEGIN " + type + "-----") + ("-----BEGIN " + type + "-----").length());
        int end = body.indexOf("-----END " + type + "-----");
        if (end < 0) {
            throw new IllegalStateException("PEM « " + type + " » incomplet.");
        }
        return Base64.getMimeDecoder().decode(body.substring(0, end).replaceAll("\\s", ""));
    }

    private static void requireStrength(int bits) {
        if (bits < MIN_RSA_BITS) {
            throw new IllegalStateException("Clé RSA du runner trop courte (" + bits + " bits, minimum " + MIN_RSA_BITS + ").");
        }
    }
}
