package com.nebdev.devopsagent.journal;

import com.nebdev.devopsagent.stack.PlatformServiceSpec;
import com.nebdev.devopsagent.stack.PlatformSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeployActivityTest {

    @Test
    void labelsAndClonesProduceReadableSummary() {
        PlatformSpec platform = PlatformSpec.normalized("arc", "arc-nwanyo.org", "path", List.of(
                new PlatformServiceSpec("www", "git@github.com:nebdev07/arc-client.git", "main", "", 3000, "",
                        List.of(), Map.of(), "/", "next", Map.of(), false),
                new PlatformServiceSpec("backoffice", "git@github.com:nebdev07/arc-backoffice.git", "main", "", 3000, "",
                        List.of(), Map.of(), "/arc-backoffice", "next", Map.of(), true),
                new PlatformServiceSpec("api", "git@github.com:nebdev07/arc-backend.git", "main", "", 8080, "src",
                        List.of("mariadb"), Map.of(), "/arc-api", "spring-boot-11", Map.of(), true)
        ), null);

        String log = """
                CLONED=www@ea2e20c
                CLONED=backoffice@5d77ade
                CLONED=api@4f84426
                PLATFORM_DEPLOYED
                """;
        Map<String, String> details = DeployActivity.details(platform, log, true);
        assertThat(details.get("summary")).contains("Déployé 3 applications");
        assertThat(details.get("summary")).contains("frontoffice");
        assertThat(details.get("summary")).contains("back-office");
        assertThat(details.get("summary")).contains("backend");
        assertThat(details.get("commits")).contains("www@ea2e20c");
        assertThat(details.get("commits")).contains("api@4f84426");
        assertThat(details.get("apps")).contains("www@ea2e20");
    }
}
