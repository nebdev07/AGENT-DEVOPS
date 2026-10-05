package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CargoScaffoldTest {

    /** Test réseau : AUTOPILOT_SAMPLE_NEXT_REPO = dépôt GitHub public Next.js sans Dockerfile racine. */
    @Test
    void detectsNextOnSampleRepo() {
        String repo = System.getenv("AUTOPILOT_SAMPLE_NEXT_REPO");
        assumeTrue(repo != null && !repo.isBlank(), "AUTOPILOT_SAMPLE_NEXT_REPO non défini — test réseau ignoré");
        AppSpec app = AppSpec.normalized("mon-app", repo, "main", "mon-app.example.com", 8080);
        assumeTrue(CargoScaffold.dockerfileExists(app).equals(Optional.of(false)),
                "le dépôt a un Dockerfile (ou GitHub injoignable) — rattrapage non applicable");
        assertThat(CargoScaffold.propose(app)).isPresent();
        assertThat(CargoScaffold.propose(app).orElseThrow().kind()).isEqualTo("next");
        assertThat(CargoScaffold.propose(app).orElseThrow().port()).isEqualTo(3000);
    }

    @Test
    void roleScriptIncludesScaffoldB64() {
        AppSpec app = AppSpec.normalized(
                "mon-app",
                "https://github.com/organisation/mon-app.git",
                "main",
                "mon-app.example.com",
                3000
        );
        String script = RoleScript.loadApp(app, "YWJj");
        assertThat(script).contains("APP_SCAFFOLD_B64=YWJj");
        assertThat(script).contains("APP_PORT=3000");
    }
}
