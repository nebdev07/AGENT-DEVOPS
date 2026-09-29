package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.stack.GitDeployKeyStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/git-deploy-key")
public class GitDeployKeyController {

    private final GitDeployKeyStore store;

    public GitDeployKeyController(GitDeployKeyStore store) {
        this.store = store;
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

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record GenerateRequest(Boolean confirm, Boolean rotate) {}
}
