package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.config.DevopsProperties;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clés SSH lecture seule pour cloner des dépôts privés (V7-05). Hors git, sous {@code state/}.
 * GitHub refuse une même Deploy key sur deux dépôts : une clé par dépôt ({@code state/git-deploy-keys/}),
 * la clé unique historique {@code state/git-deploy} reste un repli.
 */
@Component
public class GitDeployKeyStore {

    private static final Pattern SSH_REPO = Pattern.compile(
            "^(?:git@|ssh://git@)([a-z0-9.-]+)[:/](.+?)(?:\\.git)?/?$", Pattern.CASE_INSENSITIVE);

    private final Path privateKey;
    private final Path publicKey;
    private final Path repoDir;

    public GitDeployKeyStore(DevopsProperties properties) {
        Path dir = Path.of(properties.getStateDir());
        this.privateKey = dir.resolve("git-deploy");
        this.publicKey = dir.resolve("git-deploy.pub");
        this.repoDir = dir.resolve("git-deploy-keys");
    }

    /** Identifiant de fichier stable pour un dépôt SSH, ex. {@code github.com-org-repo}. Vide si HTTPS. */
    public static String keyId(String gitUrl) {
        if (gitUrl == null) {
            return "";
        }
        Matcher m = SSH_REPO.matcher(gitUrl.trim());
        if (!m.matches()) {
            return "";
        }
        String raw = (m.group(1) + "-" + m.group(2)).toLowerCase(Locale.ROOT);
        return raw.replaceAll("[^a-z0-9.-]+", "-").replaceAll("-{2,}", "-");
    }

    /** Page où coller la Deploy key (GitHub / GitLab), vide si inconnue. */
    public static String deployKeyPage(String gitUrl) {
        if (gitUrl == null) {
            return "";
        }
        Matcher m = SSH_REPO.matcher(gitUrl.trim());
        if (!m.matches()) {
            return "";
        }
        String host = m.group(1).toLowerCase(Locale.ROOT);
        String path = m.group(2);
        if ("github.com".equals(host)) {
            return "https://github.com/" + path + "/settings/keys/new";
        }
        if ("gitlab.com".equals(host)) {
            return "https://gitlab.com/" + path + "/-/settings/repository";
        }
        return "";
    }

    public boolean present() {
        return Files.isRegularFile(privateKey) && Files.isRegularFile(publicKey);
    }

    public boolean hasRepoKey(String gitUrl) {
        String id = keyId(gitUrl);
        return !id.isEmpty() && Files.isRegularFile(repoPrivate(id)) && Files.isRegularFile(repoPublic(id));
    }

    /** Une clé utilisable pour ce dépôt (dédiée ou repli historique). */
    public boolean present(String gitUrl) {
        return hasRepoKey(gitUrl) || present();
    }

    public String publicKeyText() throws IOException {
        if (!present()) {
            return "";
        }
        return Files.readString(publicKey, StandardCharsets.UTF_8).trim();
    }

    public String publicKeyText(String gitUrl) throws IOException {
        if (!hasRepoKey(gitUrl)) {
            return "";
        }
        return Files.readString(repoPublic(keyId(gitUrl)), StandardCharsets.UTF_8).trim();
    }

    public String fingerprint() throws IOException {
        if (!present()) {
            return "";
        }
        return fingerprintOf(publicKey);
    }

    public String fingerprint(String gitUrl) throws IOException {
        if (!hasRepoKey(gitUrl)) {
            return "";
        }
        return fingerprintOf(repoPublic(keyId(gitUrl)));
    }

    public String privateKeyBase64() throws IOException {
        if (!present()) {
            throw missingKey();
        }
        return Base64.getEncoder().encodeToString(Files.readAllBytes(privateKey));
    }

    public String privateKeyBase64(String gitUrl) throws IOException {
        if (hasRepoKey(gitUrl)) {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(repoPrivate(keyId(gitUrl))));
        }
        return privateKeyBase64();
    }

    public record Verification(boolean ok, String message) {}

    /**
     * READ : le dépôt accepte-t-il la clé ? Demande la liste des références (comme un clone), sans rien écrire.
     * La clé privée ne quitte pas le poste (authentification par signature).
     */
    public Verification verify(String gitUrl) {
        Matcher m = gitUrl == null ? null : SSH_REPO.matcher(gitUrl.trim());
        if (m == null || !m.matches()) {
            return new Verification(true, "Dépôt HTTPS : pas de clé nécessaire.");
        }
        Path key = hasRepoKey(gitUrl) ? repoPrivate(keyId(gitUrl)) : (present() ? privateKey : null);
        if (key == null) {
            return new Verification(false, "Pas encore de clé pour ce dépôt : clique Générer.");
        }
        String host = m.group(1);
        String repoPath = m.group(2) + ".git";
        try (SSHClient client = new SSHClient()) {
            client.addHostKeyVerifier(new PromiscuousVerifier());
            client.setConnectTimeout(10_000);
            client.setTimeout(15_000);
            client.connect(host, 22);
            try {
                client.authPublickey("git", client.loadKeys(key.toAbsolutePath().toString()));
            } catch (UserAuthException e) {
                return new Verification(false,
                        "Clé refusée par " + host + " : colle la clé publique en Deploy key sur ce dépôt.");
            }
            try (Session session = client.startSession()) {
                Session.Command cmd = session.exec("git-upload-pack '" + repoPath.replace("'", "") + "'");
                byte[] buf = new byte[16];
                int n = cmd.getInputStream().read(buf);
                if (n > 0) {
                    return new Verification(true, "Accès en lecture confirmé.");
                }
                String err = new String(cmd.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                return new Verification(false, err.isBlank()
                        ? "Dépôt introuvable avec cette clé : vérifie qu’elle est collée sur CE dépôt."
                        : "Refus du dépôt : " + err);
            }
        } catch (IOException e) {
            return new Verification(false, "Contrôle impossible (" + e.getMessage() + "). Réessaie.");
        }
    }

    public void generate(boolean rotate) throws IOException {
        if (present() && !rotate) {
            throw new IllegalStateException(OperatorMessage.of(
                    "une clé Git existe déjà.",
                    "Bande Applications — clé Git",
                    "copie la publique sur le dépôt. Pour en créer une nouvelle : régénérer (l’ancienne Deploy key ne marchera plus)."));
        }
        createKey(privateKey, publicKey, rotate, "vps-autopilot-git-readonly");
    }

    public void generate(String gitUrl, boolean rotate) throws IOException {
        String id = keyId(gitUrl);
        if (id.isEmpty()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "ce dépôt n’est pas en SSH (git@…).",
                    "Clés Git — dépôt",
                    "un dépôt HTTPS public n’a pas besoin de clé ; privé : utilise git@github.com:org/repo.git."));
        }
        if (hasRepoKey(gitUrl) && !rotate) {
            throw new IllegalStateException(OperatorMessage.of(
                    "une clé existe déjà pour " + id + ".",
                    "Clés Git — dépôt",
                    "copie la publique en Deploy key de ce dépôt. Régénérer invalide l’ancienne."));
        }
        Files.createDirectories(repoDir);
        createKey(repoPrivate(id), repoPublic(id), rotate, "vps-autopilot-" + id);
    }

    private void createKey(Path priv, Path pub, boolean rotate, String comment) throws IOException {
        Files.createDirectories(priv.getParent());
        if (rotate) {
            Files.deleteIfExists(priv);
            Files.deleteIfExists(pub);
        }
        runSshKeygen(List.of(
                "-t", "ed25519",
                "-f", priv.toAbsolutePath().toString(),
                "-N", "",
                "-C", comment
        ), 30);
        if (!Files.isRegularFile(priv) || !Files.isRegularFile(pub)) {
            throw new IllegalStateException(OperatorMessage.of(
                    "ssh-keygen n’a pas écrit la clé.",
                    "Clés Git",
                    "installe le client OpenSSH (ssh-keygen), puis réessaie."));
        }
        try {
            Files.setPosixFilePermissions(priv, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows : pas POSIX ; le fichier reste hors git.
        }
    }

    private Path repoPrivate(String id) {
        return repoDir.resolve(id);
    }

    private Path repoPublic(String id) {
        return repoDir.resolve(id + ".pub");
    }

    private static IllegalStateException missingKey() {
        return new IllegalStateException(OperatorMessage.of(
                "aucune clé Git lecture seule sur ce poste.",
                "Clés Git",
                "Génère la clé du dépôt, copie la publique en Deploy key (lecture seule) sur GitHub/GitLab, puis Déployer."));
    }

    private static String fingerprintOf(Path pub) throws IOException {
        List<String> lines = runSshKeygen(List.of("-lf", pub.toAbsolutePath().toString()), 15);
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    private static List<String> runSshKeygen(List<String> args, int timeoutSec) throws IOException {
        List<String> cmd = new ArrayList<>();
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
                    "Clés Git",
                    "installe OpenSSH Client (Windows : Paramètres → Fonctionnalités facultatives), puis réessaie."), e);
        }
        try {
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                throw new IllegalStateException(OperatorMessage.of(
                        "ssh-keygen n’a pas répondu.",
                        "Clés Git",
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
                    "Clés Git",
                    detail.isBlank() ? "code " + p.exitValue() : detail));
        }
        if (out.isBlank()) {
            return List.of();
        }
        return List.of(out.strip().split("\\R"));
    }
}
