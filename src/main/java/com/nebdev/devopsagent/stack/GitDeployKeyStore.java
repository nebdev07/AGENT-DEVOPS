package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.config.DevopsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Clé SSH lecture seule pour cloner des dépôts privés (V7-05). Hors git : {@code state/git-deploy}.
 */
@Component
public class GitDeployKeyStore {

    private final Path privateKey;
    private final Path publicKey;

    public GitDeployKeyStore(DevopsProperties properties) {
        Path dir = Path.of(properties.getStateDir());
        this.privateKey = dir.resolve("git-deploy");
        this.publicKey = dir.resolve("git-deploy.pub");
    }

    public boolean present() {
        return Files.isRegularFile(privateKey) && Files.isRegularFile(publicKey);
    }

    public String publicKeyText() throws IOException {
        if (!present()) {
            return "";
        }
        return Files.readString(publicKey, StandardCharsets.UTF_8).trim();
    }

    public String fingerprint() throws IOException {
        if (!present()) {
            return "";
        }
        List<String> lines = runSshKeygen(List.of("-lf", publicKey.toAbsolutePath().toString()), 15);
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    public String privateKeyBase64() throws IOException {
        if (!present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "aucune clé Git lecture seule sur ce poste.",
                    "Bande Applications — clé Git",
                    "Génère la clé, copie la publique en Deploy key (lecture seule) sur GitHub/GitLab, puis Déployer."));
        }
        byte[] raw = Files.readAllBytes(privateKey);
        return Base64.getEncoder().encodeToString(raw);
    }

    public void generate(boolean rotate) throws IOException {
        if (present() && !rotate) {
            throw new IllegalStateException(OperatorMessage.of(
                    "une clé Git existe déjà.",
                    "Bande Applications — clé Git",
                    "copie la publique sur le dépôt. Pour en créer une nouvelle : régénérer (l’ancienne Deploy key ne marchera plus)."));
        }
        Files.createDirectories(privateKey.getParent());
        if (rotate) {
            Files.deleteIfExists(privateKey);
            Files.deleteIfExists(publicKey);
        }
        runSshKeygen(List.of(
                "-t", "ed25519",
                "-f", privateKey.toAbsolutePath().toString(),
                "-N", "",
                "-C", "vps-autopilot-git-readonly"
        ), 30);
        if (!present()) {
            throw new IllegalStateException(OperatorMessage.of(
                    "ssh-keygen n’a pas écrit state/git-deploy.",
                    "Bande Applications — clé Git",
                    "installe le client OpenSSH (ssh-keygen), puis réessaie."));
        }
        restrictPrivate();
    }

    private void restrictPrivate() {
        try {
            Files.setPosixFilePermissions(privateKey, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows : pas POSIX ; le fichier reste hors git.
        }
    }

    private static List<String> runSshKeygen(List<String> args, int timeoutSec) throws IOException {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add("ssh-keygen");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(OperatorMessage.of(
                    "ssh-keygen introuvable.",
                    "Bande Applications — clé Git",
                    "installe OpenSSH Client (Windows : Paramètres → Fonctionnalités facultatives), puis réessaie."), e);
        }
        try {
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IllegalStateException(OperatorMessage.of(
                        "ssh-keygen n’a pas répondu.",
                        "Bande Applications — clé Git",
                        "relance Générer. Si ça bloque : ferme un prompt ssh-keygen ouvert."));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("ssh-keygen interrompu", e);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            String detail = out.strip();
            if (detail.length() > 240) {
                detail = detail.substring(0, 237) + "…";
            }
            throw new IllegalStateException(OperatorMessage.of(
                    "ssh-keygen a échoué.",
                    "Bande Applications — clé Git",
                    detail.isBlank() ? "code " + p.exitValue() : detail));
        }
        if (out.isBlank()) {
            return List.of();
        }
        return List.of(out.strip().split("\\R"));
    }
}
