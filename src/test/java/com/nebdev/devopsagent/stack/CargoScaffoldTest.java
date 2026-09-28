package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CargoScaffoldTest {

    @Test
    void detectsNextOnBourseRepo() {
        AppSpec app = AppSpec.normalized(
                "my-bourse",
                "https://github.com/nebdev07/BOURSE.git",
                "main",
                "my-bourse.example.com",
                8080
        );
        assertThat(CargoScaffold.dockerfileExists(app)).contains(false);
        assertThat(CargoScaffold.propose(app)).isPresent();
        assertThat(CargoScaffold.propose(app).orElseThrow().kind()).isEqualTo("next");
        assertThat(CargoScaffold.propose(app).orElseThrow().port()).isEqualTo(3000);
    }

    @Test
    void roleScriptIncludesScaffoldB64() {
        AppSpec app = AppSpec.normalized(
                "my-bourse",
                "https://github.com/nebdev07/BOURSE.git",
                "main",
                "my-bourse.example.com",
                3000
        );
        String script = RoleScript.loadApp(app, "YWJj");
        assertThat(script).contains("APP_SCAFFOLD_B64=YWJj");
        assertThat(script).contains("APP_PORT=3000");
    }
}
