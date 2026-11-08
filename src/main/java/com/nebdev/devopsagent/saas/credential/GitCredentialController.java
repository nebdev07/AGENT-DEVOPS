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

/** Deploy keys Git d'une organisation. Aucune route ne renvoie la clé privée. */
@RestController
@RequestMapping("/api/v2/orgs/{orgId}/git-credentials")
@Tag(name = "client")
public class GitCredentialController {

    private final GitCredentialService gitCredentials;

    public GitCredentialController(GitCredentialService gitCredentials) {
        this.gitCredentials = gitCredentials;
    }

    public record Generate(@NotBlank String gitUrl) {}

    public record ImportKey(@NotBlank String gitUrl, @NotBlank String privateKey) {
        @Override
        public String toString() {
            return "ImportKey[" + gitUrl + ", ***]";
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId) {
        return gitCredentials.list(orgId).stream().map(GitCredentialService::view).toList();
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@PathVariable UUID orgId, @Valid @RequestBody Generate body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GitCredentialService.view(gitCredentials.generate(orgId, body.gitUrl())));
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importKey(@PathVariable UUID orgId, @Valid @RequestBody ImportKey body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(GitCredentialService.view(gitCredentials.importKey(orgId, body.gitUrl(), body.privateKey())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID orgId, @PathVariable UUID id) {
        gitCredentials.delete(orgId, id);
        return ResponseEntity.noContent().build();
    }
}
