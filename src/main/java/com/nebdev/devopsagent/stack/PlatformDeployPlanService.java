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
    private final ServerProfiles serverProfiles;

    public PlatformDeployPlanService(PlatformIntentStore store, GitDeployKeyStore gitDeployKeyStore,
                                     ServerProfiles serverProfiles) {
        this.store = store;
        this.gitDeployKeyStore = gitDeployKeyStore;
        this.serverProfiles = serverProfiles;
    }

    public AppDeployPlan plan(String platformId) throws IOException {
        PlatformSpec p = store.require(platformId);
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        actions.add("CHANGE deploy-platform « " + p.id() + " » (" + p.services().size() + " service(s)).");
        for (PlatformServiceSpec s : p.services()) {
            if (GitUrls.ssh(s.gitUrl()) && !gitDeployKeyStore.present(s.gitUrl())) {
                return AppDeployPlan.blocked(
                        p.id(),
                        actions,
                        "Dépôt privé sans clé pour « " + s.id() + " » (" + s.gitUrl()
                                + ") : génère sa clé (Clés Git), ajoute-la en Deploy key lecture seule sur ce dépôt, puis Déployer.");
            }
        }
        if (p.services().stream().anyMatch(s -> GitUrls.ssh(s.gitUrl()))) {
            actions.add("Poser une clé Git lecture seule par dépôt privé sur le compte deploy.");
        }
        for (PlatformServiceSpec s : p.services()) {
            String where = s.templated()
                    ? "Dockerfile fourni par l’agent (modèle " + s.template() + ")"
                    : s.context().isBlank() ? "Dockerfile à la racine" : "Dockerfile dans « " + s.context() + " »";
            actions.add("Cloner « " + s.id() + " » : " + s.gitUrl() + " @" + s.gitRef() + " — " + where + ".");
            if (s.templated() || GitUrls.ssh(s.gitUrl())) {
                actions.add("Construire l’image « " + s.id() + " » (port " + s.port() + ") → "
                        + PlatformCompose.publicUrl(p, s, serverProfiles.of(s.hostname())));
                continue;
            }
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
                                + " » (GitHub). Choisis un modèle Dockerfile (next, spring-boot-11…) pour ce service, ou ajoute un Dockerfile au dépôt, puis Déployer.");
            }
            if (hasDf.isEmpty()) {
                warnings.add("Préflight GitHub indisponible pour « " + s.id() + " » — le VPS vérifiera le Dockerfile.");
            }
            actions.add("Construire l’image « " + s.id() + " » (port " + s.port() + ") → "
                    + PlatformCompose.publicUrl(p, s, serverProfiles.of(s.hostname())));
        }
        if (PlatformCompose.needsDb(p)) {
            actions.add("Si MariaDB est là : base/user app_" + p.id().replace('-', '_') + " + db.env.");
        }
        if (PlatformCompose.needsRedis(p)) {
            actions.add("Injecter REDIS_HOST=redis (mot de passe hors git) pour les services qui en ont besoin.");
        }
        if (PlatformCompose.needsRedisPrivate(p)) {
            actions.add("Redis privé « " + PlatformCompose.CACHE_SERVICE
                    + " » (réseau interne à la plateforme, sans mot de passe, non exposé).");
        }
        long envCount = p.services().stream().mapToLong(s -> s.env().size()).sum();
        if (envCount > 0) {
            actions.add("Écrire " + envCount + " variable(s) d’exécution dans svc-<id>.env (secrets masqués dans la console).");
        }
        actions.add("Générer le compose Traefik ("
                + (p.pathRouting() ? "PathPrefix, un DNS (préfixe retiré ou conservé par service)" : "sous-domaines Host()")
                + ", aucun port publié)"
                + (serverProfiles.of(p.services().get(0).hostname()).httpOnly() ? ", HTTP seul (profil serveur)." : " + HTTPS."));
        actions.add("docker compose up --build sous /opt/vps-autopilot/platforms/" + p.id() + "/");
        return AppDeployPlan.ready(p.id(), actions, warnings, null);
    }
}
