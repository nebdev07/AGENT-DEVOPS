package com.nebdev.devopsagent.saas.staging;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}/servers/{serverId}/staging")
@Tag(name = "client")
public class StagingController {

    public record SecretBody(String serviceId, String name, String value, Boolean restart) {}

    private final StagingService staging;

    public StagingController(StagingService staging) {
        this.staging = staging;
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> restoreFile(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                                           @RequestParam("kind") String kind,
                                                           @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staging.storeRestoreFile(orgId, serverId, kind, file));
    }

    @PostMapping("/secrets")
    public ResponseEntity<Map<String, Object>> secret(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                                      @RequestBody SecretBody body) {
        boolean restart = body.restart() == null || body.restart();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(staging.storeSecret(orgId, serverId, body.serviceId(), body.name(), body.value(), restart));
    }
}
