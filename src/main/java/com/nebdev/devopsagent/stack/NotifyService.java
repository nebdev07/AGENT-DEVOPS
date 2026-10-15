package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Alertes e-mail posées sur le VPS (BIZ-15, ADR-044) : le serveur prévient seul, même poste opérateur éteint,
 * quand la sauvegarde ou l’exercice de restauration échoue, et après chaque exercice mensuel réussi.
 */
@Service
public class NotifyService {

    static final int TIMEOUT_SECONDS = 180;
    private static final String WHERE = "Alertes e-mail";

    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;

    public NotifyService(ActionGuard actionGuard, ForbiddenTargetGuard forbiddenTargetGuard, SshConnector sshConnector) {
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
    }

    /** @param testOk le message d’essai est parti ; {@code error} = réponse du serveur SMTP sinon */
    public record Outcome(String action, boolean testOk, String error, Notifications.Status status) {}

    public Notifications.Status status(SshTarget target) throws IOException {
        forbiddenTargetGuard.assertAllowed(target.host());
        return Notifications.parseStatus(run(RoleScript.loadNotify(Notifications.Mode.STATUS, null), target));
    }

    public Outcome configure(Notifications.Settings settings, SshTarget target, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("configure-notify", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        String raw = run(RoleScript.loadNotify(Notifications.Mode.SET, settings), target);
        if (!raw.contains("NOTIFY_CONFIGURED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté avant la fin.", WHERE, "réessaie ; le mot de passe n’est pas affiché."));
        }
        return new Outcome("CONFIGURED", raw.contains("NOTIFY_TEST_OK"), Notifications.sendError(raw), status(target));
    }

    public Outcome test(SshTarget target, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("test-notify", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        String raw = run(RoleScript.loadNotify(Notifications.Mode.TEST, null), target);
        return new Outcome("TESTED", raw.contains("NOTIFY_TEST_OK"), Notifications.sendError(raw), status(target));
    }

    public Outcome remove(SshTarget target, boolean confirmed) throws IOException {
        actionGuard.assertConfirmedChange("configure-notify", confirmed);
        forbiddenTargetGuard.assertAllowed(target.host());
        String raw = run(RoleScript.loadNotify(Notifications.Mode.REMOVE, null), target);
        if (!raw.contains("NOTIFY_REMOVED")) {
            throw new IllegalStateException(OperatorMessage.of("retrait interrompu.", WHERE, "réessaie."));
        }
        return new Outcome("REMOVED", false, null, status(target));
    }

    private String run(String script, SshTarget target) throws IOException {
        String raw;
        try {
            raw = sshConnector.runStdinScript(script, target, TIMEOUT_SECONDS);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(msg);
            throw new IllegalStateException(OperatorMessage.of("connexion au VPS interrompue.", WHERE, "réessaie."), e);
        }
        throwIfKnown(raw);
        return raw;
    }

    static void throwIfKnown(String raw) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur SSH n’a pas sudo sans mot de passe.", WHERE,
                    "utilise le compte de l’agent (sudoers NOPASSWD)."));
        }
        if (raw.contains("NEED_SYSTEMD") || raw.contains("NEED_CURL")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "systemd ou curl absent sur le serveur.", WHERE, "serveur Ubuntu ou Debian requis."));
        }
        if (raw.contains("NOTIFY_NOT_CONFIGURED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "aucune alerte n’est configurée sur ce serveur.", WHERE, "enregistre d’abord les réglages."));
        }
        if (raw.contains("NEED_NOTIFY_PASSWORD")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun mot de passe SMTP n’est encore posé sur ce serveur.", WHERE, "saisis le mot de passe."));
        }
        if (raw.contains("NEED_NOTIFY_SPEC")) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "réglages refusés par le VPS.", WHERE, "vérifie le serveur SMTP, le port et les adresses."));
        }
    }
}
