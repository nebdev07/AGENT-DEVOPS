package com.nebdev.devopsagent.saas.credential;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Clés SSH par serveur. Aucune route ne renvoie jamais une clé privée. */
@RestController
@RequestMapping("/api/v2/orgs/{orgId}/servers/{serverId}/credentials")
@Tag(name = "client")
public class CredentialController {

    private final CredentialService credentials;

    public CredentialController(CredentialService credentials) {
        this.credentials = credentials;
    }

    public record ImportKey(@NotBlank String privateKey) {
        @Override
        public String toString() {
            return "ImportKey[***]";
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return credentials.list(orgId, serverId).stream().map(CredentialService::view).toList();
    }

    /** Génère une clé : la clé publique est à poser dans ~/.ssh/authorized_keys (ou via une rotation). */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CredentialService.view(credentials.generate(orgId, serverId)));
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKey(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                                         @Valid @RequestBody ImportKey k) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CredentialService.view(credentials.importKey(orgId, serverId, k.privateKey())));
    }

    @DeleteMapping("/active")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID serverId) {
        credentials.revokeActive(orgId, serverId);
        return ResponseEntity.noContent().build();
    }
}
