package com.nebdev.devopsagent.ssh;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Contexte d'exécution d'un appel au moteur pour <b>un</b> serveur (ADR-051) : clé privée SSH propre à ce
 * serveur, empreinte d'hôte attendue (SHA256, format OpenSSH) et phrase de passe des sauvegardes de
 * l'organisation. Posé par le SaaS pour la durée d'une exécution, puis effacé.
 *
 * <p>Sans contexte (console locale, profil platform), le moteur garde son comportement d'origine :
 * clé {@code DEVOPS_SSH_KEY}, {@code state/trusted-hosts.json}, {@code state/backup.key}.
 * Avec contexte, une empreinte absente ou différente fait <b>échouer</b> la connexion (pas de confiance
 * implicite, même si {@code DEVOPS_SSH_TRUST_UNKNOWN=true}).
 */
public final class ExecutionScope {

    public record Credentials(String privateKeyPem, String hostFingerprintSha256, String backupPassphrase) {
        @Override
        public String toString() {
            return "Credentials[privateKey=***, hostFingerprint=" + hostFingerprintSha256 + ", backupPassphrase=***]";
        }
    }

    private static final ThreadLocal<Credentials> CURRENT = new ThreadLocal<>();

    private ExecutionScope() {}

    public static Optional<Credentials> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static <T> T run(Credentials credentials, Callable<T> work) throws Exception {
        Credentials previous = CURRENT.get();
        CURRENT.set(credentials);
        try {
            return work.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
