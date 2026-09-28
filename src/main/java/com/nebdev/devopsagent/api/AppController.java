package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.discovery.DiscoveryService;
import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.AppDeployPlan;
import com.nebdev.devopsagent.stack.AppDeployService;
import com.nebdev.devopsagent.stack.AppIntentStore;
import com.nebdev.devopsagent.stack.AppSpec;
import com.nebdev.devopsagent.stack.OperatorMessage;
import com.nebdev.devopsagent.stack.PublicDomainStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/apps")
public class AppController {

    private final AppIntentStore appIntentStore;
    private final AppDeployService appDeployService;
    private final PublicDomainStore publicDomainStore;

    public AppController(
            AppIntentStore appIntentStore,
            AppDeployService appDeployService,
            PublicDomainStore publicDomainStore
    ) {
        this.appIntentStore = appIntentStore;
        this.appDeployService = appDeployService;
        this.publicDomainStore = publicDomainStore;
    }

    @GetMapping
    public Map<String, Object> list() throws IOException {
        return Map.of(
                "apps", appIntentStore.load(),
                "publicBaseDomain", publicDomainStore.load(),
                "publicScheme", "https"
        );
    }

    @GetMapping("/public-domain")
    public Map<String, Object> getPublicDomain() throws IOException {
        String base = publicDomainStore.load();
        return Map.of(
                "baseDomain", base,
                "hint", base.isBlank()
                        ? "Sans domaine : fallback slug.<IP>.sslip.io (lab). Pour un lien simple : apps.mondomaine.com + DNS * → IP VPS."
                        : "Liens : https://<identifiant>." + base + "/"
        );
    }

    @PutMapping("/public-domain")
    public Map<String, Object> putPublicDomain(@RequestBody(required = false) PublicDomainBody body) throws IOException {
        String saved = publicDomainStore.save(body == null ? "" : body.baseDomain());
        return Map.of(
                "baseDomain", saved,
                "hint", saved.isBlank()
                        ? "Domaine effacé — suggestions sslip."
                        : "OK. Pointe *. " + saved + " (et " + saved + ") vers l’IP du VPS, puis Enregistrer / Déployer les apps."
        );
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) AppsUpdate body) throws IOException {
        List<AppSpec> incoming = body == null || body.apps() == null ? List.of() : body.apps();
        List<AppSpec> saved = appIntentStore.save(incoming);
        return Map.of(
                "apps", saved,
                "publicBaseDomain", publicDomainStore.load(),
                "publicScheme", "https"
        );
    }

    /** Retire une app de la liste locale (ne stoppe pas le conteneur sur le VPS). */
    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("id") String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucun id fourni pour la suppression.",
                    "Liste Applications — bouton Supprimer",
                    "indique l’identifiant de la ligne (ex. my-bourse)."));
        }
        String slug = id.trim().toLowerCase().replace('_', '-');
        List<AppSpec> current = appIntentStore.load();
        boolean found = current.stream().anyMatch(a -> a.id().equals(slug));
        if (!found) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "aucune ligne « " + slug + " » dans la liste.",
                    "Liste Applications — bouton Supprimer",
                    "rafraîchis la liste (ré-analyser ou recharger la page), puis réessaie."));
        }
        List<AppSpec> kept = current.stream().filter(a -> !a.id().equals(slug)).toList();
        List<AppSpec> saved = appIntentStore.save(kept);
        return Map.of(
                "apps", saved,
                "removed", slug,
                "note", "Ligne retirée de la liste locale uniquement. Le conteneur sur le VPS n’a pas été arrêté."
        );
    }

    @GetMapping("/deploy-plan")
    public Map<String, Object> deployPlan(@RequestParam("id") String id) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Indique l’id d’app (paramètre id).");
        }
        AppDeployPlan plan = appDeployService.plan(id.trim());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", plan.id());
        out.put("actions", plan.actions());
        out.put("warnings", plan.warnings());
        out.put("canProceed", plan.canProceed());
        out.put("blocker", plan.blocker() == null ? "" : plan.blocker());
        if (plan.scaffold() != null) {
            out.put("scaffold", Map.of(
                    "kind", plan.scaffold().kind(),
                    "port", plan.scaffold().port(),
                    "summary", plan.scaffold().summary()
            ));
        }
        return out;
    }

    @PostMapping("/deploy")
    public Map<String, Object> deploy(@RequestBody DeployRequest body) throws IOException {
        if (body == null || body.id() == null || body.id().isBlank()) {
            throw new IllegalArgumentException("Indique l’app à déployer.");
        }
        if (body.host() == null || body.host().isBlank() || body.user() == null || body.user().isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        int port = body.port() == null ? 22 : body.port();
        boolean allowScaffold = body.allowScaffold() == null || Boolean.TRUE.equals(body.allowScaffold());
        DiscoveryService.DiscoveryResult result = appDeployService.deploy(
                body.id().trim(),
                new SshTarget(body.host().trim(), port, body.user().trim()),
                Boolean.TRUE.equals(body.confirm()),
                allowScaffold
        );
        return Map.of(
                "status", "ok",
                "id", body.id().trim(),
                "risk", "CHANGE",
                "snapshot", result.snapshot(),
                "report", result.report()
        );
    }

    @ExceptionHandler(UnsafeActionException.class)
    public ResponseEntity<Map<String, String>> forbidden(UnsafeActionException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> remote(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record AppsUpdate(List<AppSpec> apps) {
        public AppsUpdate {
            apps = apps == null ? List.of() : new ArrayList<>(apps);
        }
    }

    public record DeployRequest(
            String id,
            String host,
            Integer port,
            String user,
            Boolean confirm,
            Boolean allowScaffold
    ) {}

    public record PublicDomainBody(String baseDomain) {}
}
