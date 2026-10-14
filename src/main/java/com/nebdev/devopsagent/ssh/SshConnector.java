package com.nebdev.devopsagent.ssh;

import com.nebdev.devopsagent.config.DevopsProperties;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.xfer.FileSystemFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class SshConnector {

    private final DevopsProperties properties;
    private final TrustedHostStore trustedHostStore;

    public SshConnector(DevopsProperties properties, TrustedHostStore trustedHostStore) {
        this.properties = properties;
        this.trustedHostStore = trustedHostStore;
    }

    public String runStdinScript(String script) throws IOException {
        return runStdinScript(script, fromProperties());
    }

    public String runStdinScript(String script, SshTarget target) throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        if (ssh.getPrivateKeyPath() == null || ssh.getPrivateKeyPath().isBlank()) {
            throw new IllegalStateException("DEVOPS_SSH_KEY est vide. Renseigner .env (chemin de la clé privée).");
        }
        return runStdinScript(script, target, ssh.getTimeoutSeconds());
    }

    public String runStdinScript(String script, SshTarget target, int timeoutSeconds) throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        if (ssh.getPrivateKeyPath() == null || ssh.getPrivateKeyPath().isBlank()) {
            throw new IllegalStateException("DEVOPS_SSH_KEY est vide. Renseigner .env (chemin de la clé privée).");
        }
        return execute(script, target, false, timeoutSeconds, client -> {
            KeyProvider keys = ssh.getPassphrase() == null || ssh.getPassphrase().isBlank()
                    ? client.loadKeys(ssh.getPrivateKeyPath())
                    : client.loadKeys(ssh.getPrivateKeyPath(), ssh.getPassphrase());
            client.authPublickey(target.user(), keys);
        });
    }

    /**
     * Envoie des fichiers locaux dans {@code remoteDir} (créé en 700) par SFTP, clé de l’agent.
     * @param files nom distant → fichier local
     */
    public void uploadFiles(SshTarget target, String remoteDir, Map<String, Path> files) throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        if (ssh.getPrivateKeyPath() == null || ssh.getPrivateKeyPath().isBlank()) {
            throw new IllegalStateException("DEVOPS_SSH_KEY est vide. Renseigner .env (chemin de la clé privée).");
        }
        try (SSHClient client = new SSHClient()) {
            if (ssh.isTrustUnknown()) {
                client.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                client.addHostKeyVerifier(new StoredFingerprintVerifier(trustedHostStore));
            }
            client.setConnectTimeout(ssh.getTimeoutSeconds() * 1000);
            client.setTimeout(Math.max(120, ssh.getTimeoutSeconds()) * 1000);
            client.connect(target.host(), target.port());
            KeyProvider keys = ssh.getPassphrase() == null || ssh.getPassphrase().isBlank()
                    ? client.loadKeys(ssh.getPrivateKeyPath())
                    : client.loadKeys(ssh.getPrivateKeyPath(), ssh.getPassphrase());
            client.authPublickey(target.user(), keys);
            try (Session session = client.startSession()) {
                Session.Command cmd = session.exec("mkdir -p -m 700 '" + remoteDir + "'");
                cmd.join(30, TimeUnit.SECONDS);
                Integer status = cmd.getExitStatus();
                if (status != null && status != 0) {
                    throw new IOException("Impossible de créer " + remoteDir + " sur le VPS (exit=" + status + ").");
                }
            }
            try (SFTPClient sftp = client.newSFTPClient()) {
                for (Map.Entry<String, Path> e : files.entrySet()) {
                    sftp.put(new FileSystemFile(e.getValue().toFile()), remoteDir + "/" + e.getKey());
                }
            }
        } catch (IOException e) {
            rethrow(e, target, false);
            throw e;
        }
    }

    /** Télécharge un fichier distant lisible par l’utilisateur SSH (SFTP, clé de l’agent). */
    public void downloadFile(SshTarget target, String remotePath, Path localFile) throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        if (ssh.getPrivateKeyPath() == null || ssh.getPrivateKeyPath().isBlank()) {
            throw new IllegalStateException("DEVOPS_SSH_KEY est vide. Renseigner .env (chemin de la clé privée).");
        }
        try (SSHClient client = new SSHClient()) {
            if (ssh.isTrustUnknown()) {
                client.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                client.addHostKeyVerifier(new StoredFingerprintVerifier(trustedHostStore));
            }
            client.setConnectTimeout(ssh.getTimeoutSeconds() * 1000);
            client.setTimeout(Math.max(120, ssh.getTimeoutSeconds()) * 1000);
            client.connect(target.host(), target.port());
            KeyProvider keys = ssh.getPassphrase() == null || ssh.getPassphrase().isBlank()
                    ? client.loadKeys(ssh.getPrivateKeyPath())
                    : client.loadKeys(ssh.getPrivateKeyPath(), ssh.getPassphrase());
            client.authPublickey(target.user(), keys);
            try (SFTPClient sftp = client.newSFTPClient()) {
                sftp.get(remotePath, new FileSystemFile(localFile.toFile()));
            }
        } catch (IOException e) {
            rethrow(e, target, false);
            throw e;
        }
    }

    public String runStdinScriptWithPassword(String script, SshTarget target, String password) throws IOException {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Mot de passe SSH vide.");
        }
        return execute(script, target, true, properties.getSsh().getTimeoutSeconds(),
                client -> client.authPassword(target.user(), password));
    }

    @FunctionalInterface
    private interface Authenticator {
        void authenticate(SSHClient client) throws IOException;
    }

    private String execute(
            String script,
            SshTarget target,
            boolean passwordAuth,
            int timeoutSeconds,
            Authenticator authenticator
    ) throws IOException {
        DevopsProperties.Ssh ssh = properties.getSsh();
        int timeout = timeoutSeconds <= 0 ? ssh.getTimeoutSeconds() : timeoutSeconds;
        try (SSHClient client = new SSHClient()) {
            if (ssh.isTrustUnknown()) {
                client.addHostKeyVerifier(new PromiscuousVerifier());
            } else {
                client.addHostKeyVerifier(new StoredFingerprintVerifier(trustedHostStore));
            }
            client.setConnectTimeout(timeout * 1000);
            // Une connexion figée (sans RST) bloquait la lecture indéfiniment : silence > timeout = échec.
            client.setTimeout(timeout * 1000);
            client.connect(target.host(), target.port());
            authenticator.authenticate(client);

            try (Session session = client.startSession()) {
                Session.Command cmd = session.exec("bash -s");
                cmd.getOutputStream().write(script.getBytes(StandardCharsets.UTF_8));
                cmd.getOutputStream().flush();
                cmd.getOutputStream().close();
                String stdout = new String(cmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(cmd.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                cmd.join(timeout, TimeUnit.SECONDS);
                Integer status = cmd.getExitStatus();
                if (status != null && status != 0) {
                    // stdout porte les marqueurs (NEED_DOCKERFILE, …) ; stderr = bruit git.
                    throw new IOException(
                            "Commande distante exit=" + status
                                    + "\n###STDOUT###\n" + stdout.strip()
                                    + "\n###STDERR###\n" + stderr.strip());
                }
                if (!stderr.isBlank()) {
                    return stdout + "\n###WARN###\n" + stderr;
                }
                return stdout;
            }
        } catch (IOException e) {
            rethrow(e, target, passwordAuth);
            throw e;
        }
    }

    private static void rethrow(IOException e, SshTarget target, boolean passwordAuth) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("HOST_KEY_NOT_VERIFIABLE") || msg.contains("Could not verify")) {
            throw new IllegalStateException(
                    "Empreinte SSH inconnue pour " + target.host()
                            + ". Clique « Enregistrer l'empreinte SSH » puis réessaie. "
                            + msg);
        }
        if (msg.contains("Auth fail") || msg.contains("authentication")) {
            if (passwordAuth) {
                throw new IllegalStateException(
                        "Mot de passe refusé (" + target.user() + "@" + target.host()
                                + "). Vérifie le mot de passe fourni par ton hébergeur, ou essaie un autre utilisateur.");
            }
            throw new IllegalStateException(
                    "Authentification refusée (" + target.user() + "@" + target.host()
                            + "). Pose la clé de l'agent sur ce compte (Aide › 3. Clé SSH), ou change l'utilisateur.");
        }
    }

    private SshTarget fromProperties() {
        DevopsProperties.Ssh ssh = properties.getSsh();
        return new SshTarget(ssh.getHost(), ssh.getPort(), ssh.getUser());
    }

    static final class StoredFingerprintVerifier implements HostKeyVerifier {
        private final TrustedHostStore store;

        StoredFingerprintVerifier(TrustedHostStore store) {
            this.store = store;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            return store.isTrusted(hostname, port, SecurityUtils.getFingerprint(key));
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            return List.of();
        }
    }
}
