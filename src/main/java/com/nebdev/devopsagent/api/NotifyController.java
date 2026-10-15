package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.risk.UnsafeActionException;
import com.nebdev.devopsagent.ssh.SshTarget;
import com.nebdev.devopsagent.stack.Notifications;
import com.nebdev.devopsagent.stack.NotifyService;
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
import java.util.Map;

/** Alertes e-mail envoyées par le VPS (BIZ-15, ADR-044). */
@RestController
@RequestMapping("/api/v1/notify")
public class NotifyController {

    private final NotifyService notifyService;

    public NotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @GetMapping
    public Notifications.Status status(
            @RequestParam("host") String host,
            @RequestParam(value = "port", required = false) Integer port,
            @RequestParam("user") String user
    ) throws IOException {
        return notifyService.status(target(host, port, user));
    }

    @PostMapping
    public Map<String, Object> configure(@RequestBody NotifyRequest body) throws IOException {
        requireBody(body);
        Notifications.Settings settings = Notifications.settings(body.smtpHost(), body.smtpPort(), body.tls(),
                body.smtpUser(), body.smtpPassword(), body.from(), body.to(), body.label(), body.monthly());
        return Map.of("status", "ok", "risk", "CHANGE",
                "outcome", notifyService.configure(settings, target(body.host(), body.port(), body.user()),
                        Boolean.TRUE.equals(body.confirm())));
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody NotifyRequest body) throws IOException {
        requireBody(body);
        return Map.of("status", "ok", "risk", "CHANGE",
                "outcome", notifyService.test(target(body.host(), body.port(), body.user()),
                        Boolean.TRUE.equals(body.confirm())));
    }

    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestBody NotifyRequest body) throws IOException {
        requireBody(body);
        return Map.of("status", "ok", "risk", "CHANGE",
                "outcome", notifyService.remove(target(body.host(), body.port(), body.user()),
                        Boolean.TRUE.equals(body.confirm())));
    }

    private static void requireBody(NotifyRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Requête vide.");
        }
    }

    private static SshTarget target(String host, Integer port, String user) {
        if (host == null || host.isBlank() || user == null || user.isBlank()) {
            throw new IllegalArgumentException("Indique l’hôte et l’utilisateur SSH.");
        }
        return new SshTarget(host.trim(), port == null ? 22 : port, user.trim());
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
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()));
    }

    /** {@code smtpPassword} n’est jamais renvoyé, journalisé ni enregistré sur ce poste. */
    public record NotifyRequest(String host, Integer port, String user, Boolean confirm,
                                String smtpHost, Integer smtpPort, String tls, String smtpUser, String smtpPassword,
                                String from, String to, String label, Boolean monthly) {
        @Override
        public String toString() {
            return "NotifyRequest[host=" + host + ", smtpHost=" + smtpHost + ", smtpUser=" + smtpUser
                    + ", smtpPassword=***, to=" + to + "]";
        }
    }
}
