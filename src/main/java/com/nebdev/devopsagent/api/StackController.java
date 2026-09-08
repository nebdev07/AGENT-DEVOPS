package com.nebdev.devopsagent.api;

import com.nebdev.devopsagent.stack.StackPolicyException;
import com.nebdev.devopsagent.stack.StackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/stack")
public class StackController {

    private final StackService stackService;

    public StackController(StackService stackService) {
        this.stackService = stackService;
    }

    @GetMapping
    public StackService.StackView get() throws IOException {
        return stackService.current();
    }

    @PutMapping
    public StackService.StackView put(@RequestBody(required = false) StackUpdate body) throws IOException {
        List<String> enabled = body == null || body.enabled() == null ? List.of() : body.enabled();
        return stackService.apply(
                new LinkedHashSet<>(enabled),
                body == null ? null : body.details(),
                body != null && Boolean.TRUE.equals(body.acknowledgeWarnings())
        );
    }

    @ExceptionHandler(StackPolicyException.class)
    public ResponseEntity<Map<String, Object>> policy(StackPolicyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", ex.getMessage(),
                "policyBreak", true,
                "enabled", ex.evaluation().enabled(),
                "warnings", ex.evaluation().warnings()
        ));
    }

    public record StackUpdate(
            List<String> enabled,
            java.util.Map<String, List<String>> details,
            Boolean acknowledgeWarnings
    ) {}
}
