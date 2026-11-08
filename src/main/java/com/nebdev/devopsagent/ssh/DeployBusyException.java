package com.nebdev.devopsagent.ssh;

/**
 * Un déploiement est déjà en cours sur cet hôte.
 */
public class DeployBusyException extends RuntimeException {

    private final DeployLock.Holder holder;

    public DeployBusyException(DeployLock.Holder holder) {
        super(messageFor(holder));
        this.holder = holder;
    }

    public DeployLock.Holder holder() {
        return holder;
    }

    private static String messageFor(DeployLock.Holder holder) {
        if (holder == null) {
            return "Un déploiement est déjà en cours sur ce serveur. Attends la fin avant d’en lancer un autre.";
        }
        return "Un déploiement est déjà en cours sur " + holder.host()
                + " (« " + holder.label() + " », " + holder.kind()
                + ", démarré " + holder.startedAt()
                + "). Attends la fin — ne relance pas : le build continue souvent sur le VPS même si le suivi SSH a sauté.";
    }
}
