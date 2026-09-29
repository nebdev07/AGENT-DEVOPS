package com.nebdev.devopsagent.stack;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class AppDeployPlanService {

    private final AppIntentStore appIntentStore;
    private final GitDeployKeyStore gitDeployKeyStore;

    public AppDeployPlanService(AppIntentStore appIntentStore, GitDeployKeyStore gitDeployKeyStore) {
        this.appIntentStore = appIntentStore;
        this.gitDeployKeyStore = gitDeployKeyStore;
    }

    public AppDeployPlan plan(String appId) throws IOException {
        AppSpec app = appIntentStore.require(appId);
        List<String> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        actions.add("Cloner « " + app.gitUrl() + " » (branche « " + app.gitRef() + " ») sur le VPS.");
        if (GitUrls.ssh(app.gitUrl())) {
            if (!gitDeployKeyStore.present()) {
                return AppDeployPlan.blocked(
                        app.id(),
                        actions,
                        "Dépôt SSH : génère la clé Git lecture seule, ajoute-la en Deploy key (read-only) sur GitHub/GitLab, puis Déployer.");
            }
            actions.add("Poser la clé Git lecture seule sur le compte deploy (IdentitiesOnly).");
        }

        Optional<Boolean> hasDocker = CargoScaffold.dockerfileExists(app);
        AppDeployPlan.Scaffold scaffold = null;

        if (hasDocker.isPresent() && hasDocker.get()) {
            actions.add("Dockerfile déjà présent à la racine — aucun rattrapage.");
            actions.add("Construire l’image Docker (port interne déclaré : " + app.port() + ").");
        } else if (hasDocker.isPresent()) {
            Optional<CargoScaffold.Detection> det = CargoScaffold.propose(app);
            if (det.isEmpty()) {
                actions.add("Constater l’absence de Dockerfile à la racine.");
                return AppDeployPlan.blocked(
                        app.id(),
                        actions,
                        "Pas de Dockerfile et stack non reconnue (Next / Node / Spring Boot seulement pour le rattrapage auto). "
                                + "Ajoute un Dockerfile dans le dépôt ou élargis les templates.");
            }
            CargoScaffold.Detection d = det.get();
            scaffold = new AppDeployPlan.Scaffold(d.kind(), d.port(), d.base64(), d.label());
            actions.add("Rattrapage : injecter « " + d.label() + " » après le clone (confirmé par toi — pas encore pushé dans Git).");
            if (d.port() != app.port()) {
                actions.add("Ajuster le port Traefik / conteneur à " + d.port() + " (détecté pour cette stack ; tu avais " + app.port() + ").");
                warnings.add("Le port effectif sera " + d.port() + " pour ce déploiement.");
            }
            actions.add("Construire l’image Docker avec ce Dockerfile.");
            warnings.add("Le Dockerfile injecté vit sur le VPS. Recopie-le dans le dépôt Git quand tu peux (reproductibilité).");
        } else {
            actions.add("Préflight GitHub indisponible — le VPS décidera (Dockerfile ou rattrapage si template trouvé).");
            Optional<CargoScaffold.Detection> det = CargoScaffold.propose(app);
            if (det.isPresent()) {
                CargoScaffold.Detection d = det.get();
                scaffold = new AppDeployPlan.Scaffold(d.kind(), d.port(), d.base64(), d.label());
                actions.add("Si Dockerfile absent : injecter « " + d.label() + " ».");
            }
            actions.add("Construire l’image Docker.");
        }

        actions.add("Générer le compose Traefik (réseau proxy, aucun port publié) + HTTPS Let’s Encrypt.");
        actions.add("Publier sur https://" + app.hostname() + "/ (lien public ; HTTP redirigé vers HTTPS).");

        for (AppSpec other : appIntentStore.load()) {
            if (!other.id().equals(app.id()) && other.hostname().equalsIgnoreCase(app.hostname())) {
                return AppDeployPlan.blocked(
                        app.id(),
                        actions,
                        "Collision de nom d’hôte avec l’app « " + other.id() + " » (" + other.hostname()
                                + "). Change le champ Nom d’hôte (ex. " + app.id() + ".<IP>.sslip.io), Enregistrer, puis Déployer.");
            }
        }

        if (scaffold != null) {
            return AppDeployPlan.ready(app.id(), actions, warnings, scaffold);
        }
        return AppDeployPlan.ready(app.id(), actions, warnings, null);
    }
}
