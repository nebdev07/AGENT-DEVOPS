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
        return plan(platformId, null);
    }

    /**
     * @param serviceId si non vide : plan pour un seul service (V7-07) ; sinon toute la plateforme
     */
    public AppDeployPlan plan(String platformId, String serviceId) throws IOException {
        return plan(store.require(platformId), serviceId);
    }

    /** Plan à partir d'une spécification déjà chargée (SaaS : spec figée dans l'opération). */
    public AppDeployPlan plan(PlatformSpec p, String serviceId) throws IOException {
        return plan(p, serviceId, true);
    }

    /**
     * @param checkLocalGitKeys {@code false} en SaaS : les Deploy keys sont dans le coffre org, pas sous {@code state/}.
     */
    public AppDeployPlan plan(PlatformSpec p, String serviceId, boolean checkLocalGitKeys) throws IOException {
        PlatformServiceSpec only = resolveOnly(p, serviceId);
        List<PlatformServiceSpec> targets = only == null ? p.services() : List.of(only);
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (only != null) {
            actions.add("CHANGE deploy-platform « " + p.id() + " » — service seul « " + only.id()
                    + " » (les autres services ne sont pas reconstruits).");
            warnings.add("Pour une première mise en ligne de la plateforme, préfère « Tout déployer ».");
        } else {
            actions.add("CHANGE deploy-platform « " + p.id() + " » (" + p.services().size() + " service(s)).");
        }
        for (PlatformServiceSpec s : targets) {
            if (GitUrls.ssh(s.gitUrl()) && checkLocalGitKeys && !gitDeployKeyStore.present(s.gitUrl())) {
                return AppDeployPlan.blocked(
                        p.id(),
                        actions,
                        "Dépôt privé sans clé pour « " + s.id() + " » (" + s.gitUrl()
                                + ") : génère sa clé (Clés Git), ajoute-la en Deploy key lecture seule sur ce dépôt, puis Déployer.");
            }
        }
        if (targets.stream().anyMatch(s -> GitUrls.ssh(s.gitUrl()))) {
            actions.add(checkLocalGitKeys
                    ? "Poser une clé Git lecture seule par dépôt privé sur le compte deploy."
                    : "Utiliser la Deploy key SaaS (coffre organisation) pour chaque dépôt SSH.");
        }
        for (PlatformServiceSpec s : targets) {
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
        boolean needDb = only != null
                ? only.needs().contains("mariadb")
                : PlatformCompose.needsDb(p);
        boolean needRedis = only != null
                ? only.needs().contains("redis")
                : PlatformCompose.needsRedis(p);
        boolean needRedisPrivate = only != null
                ? PlatformCompose.needsRedisPrivate(only)
                : PlatformCompose.needsRedisPrivate(p);
        if (needDb) {
            actions.add("Si MariaDB est là : base/user app_" + p.id().replace('-', '_') + " + db.env.");
        }
        if (needRedis) {
            actions.add("Injecter REDIS_HOST=redis (mot de passe hors git) pour les services qui en ont besoin.");
        }
        if (only != null) {
            actions.add("Laisser les autres services inchangés (pas de rebuild, pas de purge src globale).");
        } else if (p.retainsSources()) {
            actions.add("Après le build : laisser les clones sous platforms/" + p.id()
                    + "/src (copie locale de secours).");
        } else {
            actions.add("Après le build : effacer platforms/" + p.id()
                    + "/src (plus de sources ni .git sur le VPS ; les images restent).");
        }
        if (needRedisPrivate) {
            actions.add("Redis privé « " + PlatformCompose.CACHE_SERVICE
                    + " » (réseau interne à la plateforme, sans mot de passe, non exposé).");
        }
        long envCount = targets.stream().mapToLong(s -> s.env().size()).sum();
        if (envCount > 0) {
            actions.add("Écrire " + envCount + " variable(s) d’exécution dans svc-<id>.env (secrets masqués dans la console).");
        }
        actions.add("Générer le compose Traefik ("
                + (p.pathRouting() ? "PathPrefix, un DNS (préfixe retiré ou conservé par service)" : "sous-domaines Host()")
                + ", aucun port publié)"
                + (serverProfiles.of(p.services().get(0).hostname()).httpOnly() ? ", HTTP seul (profil serveur)." : " + HTTPS."));
        if (only != null) {
            actions.add("docker compose up -d --build " + only.id()
                    + " sous /opt/vps-autopilot/platforms/" + p.id() + "/");
        } else {
            actions.add("docker compose up --build sous /opt/vps-autopilot/platforms/" + p.id() + "/");
        }
        return AppDeployPlan.ready(p.id(), actions, warnings, null);
    }

    public static PlatformServiceSpec resolveOnly(PlatformSpec p, String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        String sid = serviceId.trim().toLowerCase();
        return p.services().stream()
                .filter(s -> s.id().equals(sid))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(OperatorMessage.of(
                        "service « " + sid + " » inconnu sur la plateforme « " + p.id() + " ».",
                        "Déployer plateforme — service",
                        "choisis un id parmi : "
                                + p.services().stream().map(PlatformServiceSpec::id).reduce((a, b) -> a + ", " + b).orElse("—")
                                + ".")));
    }
}
