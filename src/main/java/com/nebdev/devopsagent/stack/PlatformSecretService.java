package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Secrets d’exécution posés depuis le cockpit (BIZ-05) : écrits directement dans
 * {@code svc-<service>.local.env} sur le VPS, jamais dans l’intention locale, jamais renvoyés à l’écran.
 */
@Service
public class PlatformSecretService {

    static final int TIMEOUT_SECONDS = 300;
    private static final String WHERE = "Secrets d’exécution";

    private final PlatformIntentStore store;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;

    public PlatformSecretService(
            PlatformIntentStore store,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector
    ) {
        this.store = store;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
    }

    /** @param action SECRET_ADDED, SECRET_REPLACED, SECRET_REMOVED ou SECRET_ABSENT ; restart : RESTARTED, RESTART_SKIPPED… */
    public record Outcome(String platformId, String service, String name, String action, String restart) {}

    /** Noms des variables par service (les valeurs ne quittent jamais le VPS). */
    public Map<String, List<String>> list(String platformId, SshTarget target) throws IOException {
        forbiddenTargetGuard.assertAllowed(target.host());
        PlatformSpec platform = store.require(platformId);
        String raw = run(RoleScript.loadPlatformSecret(platform.id(), PlatformSecrets.Mode.LIST, null, null, null, false),
                target);
        if (!raw.contains("SECRET_LIST_DONE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "lecture des noms de variables interrompue.", WHERE, "réessaie."));
        }
        return PlatformSecrets.parseList(raw);
    }

    public Outcome set(String platformId, String serviceId, String name, String value, boolean restart,
                       SshTarget target, boolean confirmed) throws IOException {
        PlatformSecrets.requireName(name);
        PlatformSecrets.requireValue(value);
        return apply(PlatformSecrets.Mode.SET, platformId, serviceId, name, value, restart, target, confirmed);
    }

    public Outcome unset(String platformId, String serviceId, String name, boolean restart,
                         SshTarget target, boolean confirmed) throws IOException {
        PlatformSecrets.requireName(name);
        return apply(PlatformSecrets.Mode.UNSET, platformId, serviceId, name, null, restart, target, confirmed);
    }

    private Outcome apply(PlatformSecrets.Mode mode, String platformId, String serviceId, String name, String value,
                          boolean restart, SshTarget target, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("set-platform-secret", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        PlatformSpec platform = store.require(platformId);
        String sid = serviceId == null ? "" : serviceId.strip();
        boolean known = platform.services().stream().anyMatch(s -> s.id().equals(sid));
        if (!known) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "le service « " + sid + " » n’existe pas dans la plateforme « " + platform.id() + " ».",
                    WHERE, "choisis un service de la liste."));
        }
        String raw = run(RoleScript.loadPlatformSecret(platform.id(), mode, sid, name, value, restart), target);
        if (!raw.contains("SECRET_DONE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté avant la fin.", WHERE, "réessaie ; rien n’est affiché de la valeur."));
        }
        return new Outcome(platform.id(), sid, name, action(raw), restartState(raw));
    }

    private String run(String script, SshTarget target) throws IOException {
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, TIMEOUT_SECONDS);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(msg);
            throw new IllegalStateException(OperatorMessage.of(
                    "connexion au VPS interrompue.", WHERE, "réessaie."), e);
        }
        throwIfKnown(raw);
        return raw;
    }

    static String action(String raw) {
        for (String m : List.of("SECRET_ADDED", "SECRET_REPLACED", "SECRET_REMOVED", "SECRET_ABSENT")) {
            if (raw.contains(m + "=")) {
                return m;
            }
        }
        return "";
    }

    static String restartState(String raw) {
        for (String line : raw.split("\\R")) {
            if (line.startsWith("RESTARTED=") || line.startsWith("RESTART_FAILED=")) {
                return line.substring(0, line.indexOf('='));
            }
            if (line.startsWith("RESTART_SKIPPED")) {
                return line.strip();
            }
        }
        return "";
    }

    static void throwIfKnown(String raw) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur SSH n’a pas sudo sans mot de passe.", WHERE,
                    "utilise le compte de l’agent (sudoers NOPASSWD)."));
        }
        if (raw.contains("NEED_PLATFORM_DEPLOYED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la plateforme (ou ce service) n’est pas encore déployée sur ce serveur.", WHERE,
                    "Déployer d’abord, puis pose le secret."));
        }
        if (raw.contains("NEED_SECRET_SPEC")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "nom ou valeur refusé par le VPS.", WHERE,
                    "nom : lettres, chiffres, _ et . ; valeur sans apostrophe ni retour à la ligne."));
        }
    }
}
