package com.nebdev.devopsagent.saas.apitoken;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Jetons d'API d'une organisation. Le secret n'est montré qu'une fois, à la création. */
@RestController
@RequestMapping("/api/v2/orgs/{orgId}/api-tokens")
@Tag(name = "client")
public class ApiTokenController {

    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    public record NewToken(@NotBlank String name, @NotEmpty Set<String> scopes, @Min(1) @Max(365) int days) {}

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID orgId) {
        return tokens.list(orgId).stream().map(ApiTokenService::view).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID orgId, @Valid @RequestBody NewToken r) {
        ApiTokenService.Created c = tokens.create(orgId, r.name(), r.scopes(), r.days());
        Map<String, Object> out = new LinkedHashMap<>(ApiTokenService.view(c.token()));
        out.put("secret", c.secret());
        out.put("notice", "Shown once: store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID orgId, @PathVariable UUID tokenId) {
        tokens.revoke(orgId, tokenId);
        return ResponseEntity.noContent().build();
    }
}
