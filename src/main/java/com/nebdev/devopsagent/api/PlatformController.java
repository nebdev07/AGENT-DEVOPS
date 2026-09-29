package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.stack.PlatformIntentStore;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platforms")
public class PlatformController {

    private final PlatformIntentStore store;

    public PlatformController(PlatformIntentStore store) {
        this.store = store;
    }

    @GetMapping
    public Map<String, Object> list() throws IOException {
        return Map.of(
                "platforms", store.load(),
                "deployReady", false,
                "note", "Déclaration locale seulement (V7-01). Déployer la plateforme : V7-02, pas encore."
        );
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody(required = false) PlatformsUpdate body) throws IOException {
        List<PlatformSpec> incoming = body == null || body.platforms() == null ? List.of() : body.platforms();
        String vpsIp = body == null ? null : body.vpsIp();
        List<PlatformSpec> saved = store.save(incoming, vpsIp);
        return Map.of(
                "platforms", saved,
                "deployReady", false,
                "note", "Enregistré sur ce poste. Aucun CHANGE SSH. Le bouton Déployer viendra en V7-02."
        );
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam("id") String id) throws IOException {
        List<PlatformSpec> saved = store.delete(id);
        return Map.of(
                "platforms", saved,
                "removed", id.trim().toLowerCase(),
                "note", "Ligne retirée de la liste locale. Rien n’a été arrêté sur un VPS."
        );
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    public record PlatformsUpdate(List<PlatformSpec> platforms, String vpsIp) {
        public PlatformsUpdate {
            platforms = platforms == null ? List.of() : new ArrayList<>(platforms);
        }
    }
}
