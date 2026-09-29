package com.nebdev.devopsagent.stack;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PlatformDeployPlanService {

    private final PlatformIntentStore store;
    private final GitDeployKeyStore gitDeployKeyStore;

    public PlatformDeployPlanService(PlatformIntentStore store, GitDeployKeyStore gitDeployKeyStore) {
        this.store = store;
        this.gitDeployKeyStore = gitDeployKeyStore;
    }

    public AppDeployPlan plan(String platformId) throws IOException {
        PlatformSpec p = store.require(platformId);
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        actions.add("CHANGE deploy-platform « " + p.id() + " » (" + p.services().size() + " service(s)).");
        boolean sshGit = p.services().stream().anyMatch(s -> GitUrls.ssh(s.gitUrl()));
        if (sshGit) {
            if (!gitDeployKeyStore.present()) {
                return AppDeployPlan.blocked(
                        p.id(),
                        actions,
                        "Au moins un Git en SSH : génère la clé lecture seule, Deploy key read-only sur chaque dépôt privé, puis Déployer.");
            }
            actions.add("Poser la clé Git lecture seule sur le compte deploy.");
        }
        for (PlatformServiceSpec s : p.services()) {
            String where = s.context().isBlank() ? "Dockerfile à la racine" : "Dockerfile dans « " + s.context() + " »";
            actions.add("Cloner « " + s.id() + " » : " + s.gitUrl() + " @" + s.gitRef() + " — " + where + ".");
            AppSpec probe = AppSpec.normalized(
                    "probe",
                    s.gitUrl(),
                    s.gitRef(),
                    s.hostname(),
                    s.port()
            );
            Optional<Boolean> hasDf = s.context().isBlank()
                    ? DockerfilePreflight.probe(probe)
                    : DockerfilePreflight.probePath(probe, s.context() + "/Dockerfile");
            if (hasDf.isPresent() && !hasDf.get()) {
                return AppDeployPlan.blocked(
                        p.id(),
                        actions,
                        "Pas de Dockerfile pour le service « " + s.id()
                                + " » (GitHub). Ajoute-le au dépôt (racine ou sous-dossier déclaré), pousse, puis Déployer. Pas de rattrapage auto en V7-02.");
            }
            if (hasDf.isEmpty()) {
                warnings.add("Préflight GitHub indisponible pour « " + s.id() + " » — le VPS vérifiera le Dockerfile.");
            }
            actions.add("Construire l’image « " + s.id() + " » (port " + s.port() + ") → "
                    + PlatformCompose.publicUrl(p, s));
        }
        if (PlatformCompose.needsDb(p)) {
            actions.add("Si MariaDB est là : base/user app_" + p.id().replace('-', '_') + " + db.env.");
        }
        if (PlatformCompose.needsRedis(p)) {
            actions.add("Injecter REDIS_HOST=redis (mot de passe hors git) pour les services qui en ont besoin.");
        }
        actions.add("Générer le compose Traefik ("
                + (p.pathRouting() ? "PathPrefix + StripPrefix, un DNS" : "sous-domaines Host()")
                + ", aucun port publié) + HTTPS.");
        actions.add("docker compose up --build sous /opt/vps-autopilot/platforms/" + p.id() + "/");
        return AppDeployPlan.ready(p.id(), actions, warnings, null);
    }
}
