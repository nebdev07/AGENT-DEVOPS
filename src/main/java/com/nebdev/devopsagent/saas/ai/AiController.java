package com.nebdev.devopsagent.saas.ai;

import com.nebdev.devopsagent.saas.operation.OperationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/orgs/{orgId}")
@Tag(name = "client")
public class AiController {

    private final RecommendationService recommendations;

    public AiController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @PostMapping("/servers/{serverId}/recommendations")
    public Map<String, Object> recommend(@PathVariable UUID orgId, @PathVariable UUID serverId,
                                         @RequestParam(required = false) String locale) {
        return recommendations.recommend(orgId, serverId, locale);
    }

    @PostMapping("/ai/interactions/{interactionId}/recommendations/{index}/draft")
    public ResponseEntity<Map<String, Object>> draft(@PathVariable UUID orgId, @PathVariable UUID interactionId,
                                                     @PathVariable int index) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(OperationService.view(recommendations.draft(orgId, interactionId, index)));
    }
}
