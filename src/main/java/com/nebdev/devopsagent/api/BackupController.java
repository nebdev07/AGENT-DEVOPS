package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.BackupService;
import com.nebdev.devopsagent.stack.PlatformRestoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Sauvegardes MariaDB (ADR-041) : liste locale, sauvegarde à la demande, copie, vérification, exercice, restauration. */
@RestController
@RequestMapping("/api/v1/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    public record TargetRequest(String host, Integer port, String user, Boolean confirm, String file,
                                String platformId, Boolean foreignKeyChecks) {
        SshTarget target() {
            if (host == null || host.isBlank() || user == null || user.isBlank()) {
                throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
            }
            return new SshTarget(host.trim(), port == null ? 22 : port, user.trim());
        }
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam("host") String host) throws IOException {
        List<BackupService.LocalBackup> files = backupService.list(host);
        return Map.of("host", host.trim(), "backups", files);
    }

    @PostMapping("/run")
    public BackupService.LocalBackup run(@RequestBody TargetRequest body) throws IOException {
        return backupService.runNow(body.target(), Boolean.TRUE.equals(body.confirm()));
    }

    @PostMapping("/fetch-latest")
    public BackupService.LocalBackup fetchLatest(@RequestBody TargetRequest body) throws IOException {
        return backupService.fetchLatest(body.target());
    }

    @PostMapping("/verify")
    public BackupService.LocalBackup verify(@RequestBody TargetRequest body) throws IOException {
        if (body == null || body.host() == null) {
            throw new IllegalArgumentException("Indique le serveur.");
        }
        return backupService.verify(body.host().trim(), body.file());
    }

    @PostMapping("/drill")
    public BackupService.LocalBackup drill(@RequestBody TargetRequest body) throws IOException {
        return backupService.drill(body.target(), body.file(), Boolean.TRUE.equals(body.confirm()));
    }

    @PostMapping("/restore-platform")
    public Map<String, Object> restorePlatform(@RequestBody TargetRequest body) throws IOException {
        PlatformRestoreService.RestoreResult result = backupService.restorePlatform(
                body.platformId() == null ? null : body.platformId().trim(),
                body.target(),
                body.file(),
                Boolean.TRUE.equals(body.confirm()),
                !Boolean.FALSE.equals(body.foreignKeyChecks()));
        return Map.of("status", "ok", "id", result.platformId(), "risk", "CHANGE", "report", result.report());
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
    public ResponseEntity<Map<String, String>> io(IOException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "SSH : " + ex.getMessage()));
    }
}
