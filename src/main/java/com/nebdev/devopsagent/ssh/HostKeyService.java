package com.nebdev.devopsagent.ssh;

import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.risk.RiskLevel;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.PublicKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enregistre l'empreinte via SSHJ (ssh-keyscan Windows échoue sur OpenSSH 10).
 */
@Component
public class HostKeyService {

    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final ActionGuard actionGuard;
    private final TrustedHostStore trustedHostStore;

    public HostKeyService(
            ForbiddenTargetGuard forbiddenTargetGuard,
            ActionGuard actionGuard,
            TrustedHostStore trustedHostStore
    ) {
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.actionGuard = actionGuard;
        this.trustedHostStore = trustedHostStore;
    }

    public AcceptResult accept(String host, int port) throws IOException {
        actionGuard.assertAllowed(RiskLevel.READ, "accept-host-key");
        forbiddenTargetGuard.assertAllowed(host);
        int p = port <= 0 ? 22 : port;
        AtomicReference<PublicKey> seen = new AtomicReference<>();
        try (SSHClient client = new SSHClient()) {
            client.addHostKeyVerifier(new HostKeyVerifier() {
                @Override
                public boolean verify(String hostname, int port1, PublicKey key) {
                    seen.set(key);
                    return true;
                }

                @Override
                public List<String> findExistingAlgorithms(String hostname, int port1) {
                    return List.of();
                }
            });
            client.setConnectTimeout(12_000);
            client.connect(host, p);
        }
        if (seen.get() == null) {
            throw new IOException("Aucune clé d'hôte reçue de " + host + ":" + p);
        }
        String fingerprint = SecurityUtils.getFingerprint(seen.get());
        trustedHostStore.trust(host, p, fingerprint);
        return new AcceptResult(host, p, fingerprint);
    }

    public record AcceptResult(String host, int port, String fingerprint) {}
}
