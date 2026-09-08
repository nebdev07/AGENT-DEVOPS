package com.nebdev.devopsagent.ssh;

/**
 * Script déterministe : une ligne dans {@code ~/.ssh/authorized_keys}. Idempotent.
 */
public final class SshKeyInstallScript {

    private SshKeyInstallScript() {}

    public static String installAuthorizedKey(String publicKeyLine) {
        String key = publicKeyLine == null ? "" : publicKeyLine.strip();
        if (!isSafePublicKey(key)) {
            throw new IllegalArgumentException("Clé publique invalide (attendu : une ligne ssh- / ecdsa-).");
        }
        return """
                set -eu
                umask 077
                mkdir -p "$HOME/.ssh"
                chmod 700 "$HOME/.ssh"
                touch "$HOME/.ssh/authorized_keys"
                chmod 600 "$HOME/.ssh/authorized_keys"
                KEY='%s'
                if grep -Fqx "$KEY" "$HOME/.ssh/authorized_keys"; then
                  echo KEY_ALREADY_PRESENT
                  exit 0
                fi
                printf '%%s\\n' "$KEY" >> "$HOME/.ssh/authorized_keys"
                echo KEY_INSTALLED
                """.formatted(key);
    }

    static boolean isSafePublicKey(String key) {
        if (key.isBlank() || key.contains("'") || key.contains("\n") || key.contains("\r")) {
            return false;
        }
        return key.startsWith("ssh-") || key.startsWith("ecdsa-");
    }
}
