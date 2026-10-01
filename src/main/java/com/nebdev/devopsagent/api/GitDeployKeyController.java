package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.stack.AppIntentStore;
import com.nebdev.devopsagent.stack.AppSpec;
import com.nebdev.devopsagent.stack.GitDeployKeyStore;
import com.nebdev.devopsagent.stack.PlatformIntentStore;
import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/git-deploy-key")
public class GitDeployKeyController {

    private final GitDeployKeyStore store;
    private final AppIntentStore apps;
    private final PlatformIntentStore platforms;

    public GitDeployKeyController(GitDeployKeyStore store, AppIntentStore apps, PlatformIntentStore platforms) {
        this.store = store;
        this.apps = apps;
        this.platforms = platforms;
    }

    @GetMapping
    public Map<String, Object> get() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean present = store.present();
        out.put("present", present);
        out.put("publicKey", present ? store.publicKeyText() : "");
        out.put("fingerprint", present ? store.fingerprint() : "");
        out.put("note", present
                ? "Ajoute cette clé en Deploy key lecture seule (pas d’accès write) sur GitHub ou GitLab."
                : "Génère une clé Ed25519 locale (state/git-deploy, hors git), puis colle la publique sur le dépôt.");
        return out;
    }

    @PostMapping
    public Map<String, Object> generate(@RequestBody(required = false) GenerateRequest body) throws IOException {
        if (body == null || !Boolean.TRUE.equals(body.confirm())) {
            throw new IllegalArgumentException("Indique confirm: true pour générer (ou régénérer) la clé Git.");
        }
        store.generate(Boolean.TRUE.equals(body.rotate()));
        return get();
    }

    /** Une clé par dépôt SSH (GitHub refuse la même Deploy key sur deux dépôts). */
    @GetMapping("/repos")
    public Map<String, Object> repos() throws IOException {
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        for (AppSpec app : apps.load()) {
            track(usedBy, app.gitUrl(), "app " + app.id());
        }
        for (PlatformSpec p : platforms.load()) {
            for (PlatformServiceSpec s : p.services()) {
                track(usedBy, s.gitUrl(), p.id() + " / " + s.id());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : usedBy.entrySet()) {
            String url = e.getKey();
            boolean own = store.hasRepoKey(url);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("repo", url);
            row.put("keyId", GitDeployKeyStore.keyId(url));
            row.put("usedBy", e.getValue());
            row.put("present", own);
            row.put("fallback", !own && store.present());
            row.put("publicKey", own ? store.publicKeyText(url) : "");
            row.put("fingerprint", own ? store.fingerprint(url) : "");
            row.put("deployKeyPage", GitDeployKeyStore.deployKeyPage(url));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repos", rows);
        out.put("note", rows.isEmpty()
                ? "Aucun dépôt git@… enregistré. Les dépôts HTTPS publics n’ont pas besoin de clé."
                : "Une clé par dépôt : génère, copie la publique, colle-la en Deploy key (lecture seule) sur ce dépôt.");
        return out;
    }

    @PostMapping("/repos")
    public Map<String, Object> generateForRepo(@RequestBody(required = false) RepoRequest body) throws IOException {
        if (body == null || !Boolean.TRUE.equals(body.confirm())) {
            throw new IllegalArgumentException("Indique confirm: true pour générer (ou régénérer) la clé du dépôt.");
        }
        if (body.repo() == null || body.repo().isBlank()) {
            throw new IllegalArgumentException("Indique le dépôt (git@github.com:org/repo.git).");
        }
        store.generate(body.repo().trim(), Boolean.TRUE.equals(body.rotate()));
        return repos();
    }

    private static void track(Map<String, List<String>> usedBy, String gitUrl, String label) {
        if (GitDeployKeyStore.keyId(gitUrl).isEmpty()) {
            return;
        }
        usedBy.computeIfAbsent(gitUrl.trim(), k -> new ArrayList<>()).add(label);
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record GenerateRequest(Boolean confirm, Boolean rotate) {}

    public record RepoRequest(String repo, Boolean confirm, Boolean rotate) {}
}
