package com.nebdev.devopsagent.saas.credential;

import com.nebdev.devopsagent.ssh.HostKeyService;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;

/**
 * Génération et lecture de paires de clés SSH. RSA 3072 (PKCS#8), format lu par SSHJ et accepté par tous
 * les OpenSSH actuels. Une clé importée doit être non chiffrée : elle est chiffrée au repos par le coffre.
 */
public final class SshKeys {

    public record Pair(String privatePem, String publicOpenSsh, String fingerprint) {
        @Override
        public String toString() {
            return "Pair[private=***, public=" + publicOpenSsh + "]";
        }
    }

    private SshKeys() {}

    public static Pair generate(String comment) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(3072);
            KeyPair kp = gen.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            return new Pair(pem, openSsh(kp.getPublic(), comment), HostKeyService.sha256Fingerprint(kp.getPublic()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lit une clé privée (PKCS#8, PEM RSA/EC ou format OpenSSH) et en déduit la clé publique. */
    public static Pair parse(String privatePem, String comment) {
        if (privatePem == null || privatePem.isBlank() || privatePem.length() > 20_000) {
            throw new IllegalArgumentException("Clé privée vide ou trop longue.");
        }
        if (privatePem.contains("ENCRYPTED") || privatePem.contains("Proc-Type: 4,ENCRYPTED")) {
            throw new IllegalArgumentException("Clé protégée par phrase de passe : fournir la clé déchiffrée.");
        }
        try (SSHClient client = new SSHClient()) {
            KeyProvider kp = client.loadKeys(privatePem.strip() + "\n", null, null);
            PublicKey pub = kp.getPublic();
            kp.getPrivate();
            return new Pair(privatePem.strip() + "\n", openSsh(pub, comment), HostKeyService.sha256Fingerprint(pub));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Clé privée illisible : " + e.getClass().getSimpleName());
        }
    }

    static String openSsh(PublicKey key, String comment) {
        byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        String c = comment == null ? "" : " " + comment.replaceAll("[^A-Za-z0-9@._-]", "");
        return KeyType.fromKey(key) + " " + Base64.getEncoder().encodeToString(blob) + c;
    }
}
