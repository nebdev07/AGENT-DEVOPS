package com.nebdev.devopsagent.ssh;

import com.nebdev.devopsagent.config.DevopsProperties;
import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class SshBootstrapService {

    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final DevopsProperties properties;

    public SshBootstrapService(
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector,
            DevopsProperties properties
    ) {
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
        this.properties = properties;
    }

    public Result installAgentPublicKey(SshTarget target, String password, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("install-agent-pubkey", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        if (target.user() == null || target.user().isBlank()) {
            throw new IllegalArgumentException("Indiquez l'utilisateur SSH (ex. autopilot).");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "Mot de passe SSH requis (fourni par ton hébergeur). Il n'est pas enregistré.");
        }
        String pub = readPublicKey();
        String raw = sshConnector.runStdinScriptWithPassword(
                SshKeyInstallScript.installAuthorizedKey(pub), target, password);
        if (raw.contains("KEY_ALREADY_PRESENT")) {
            return new Result("KEY_ALREADY_PRESENT", target, pub);
        }
        if (raw.contains("KEY_INSTALLED")) {
            return new Result("KEY_INSTALLED", target, pub);
        }
        throw new IOException("Réponse inattendue du VPS: " + raw.strip());
    }

    public String readPublicKey() throws IOException {
        String key = properties.getSsh().getPrivateKeyPath();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("DEVOPS_SSH_KEY est vide.");
        }
        Path pub = Path.of(key + ".pub");
        if (!Files.isRegularFile(pub)) {
            throw new IllegalStateException("Clé publique introuvable: " + pub);
        }
        return Files.readString(pub).strip();
    }

    public record Result(String outcome, SshTarget target, String publicKey) {}
}
