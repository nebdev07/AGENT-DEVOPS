package com.nebdev.devopsagent.saas.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Script de rotation : aucune valeur ne peut sortir du heredoc figé ni être interprétée par le shell. */
class LocalEngineAdapterTest {

    @Test
    void rotationScriptRefusesShellMetacharacters() {
        assertThatThrownBy(() -> LocalEngineAdapter.rotateScript("ssh-rsa AAAA; rm -rf /", "add"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEngineAdapter.rotateScript("ssh-rsa AAAA\nAUTOPILOT_KEY\nreboot", "add"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEngineAdapter.rotateScript("$(id)", "remove"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rotationScriptQuotesTheKeyAndNeverEmptiesAuthorizedKeys() {
        String add = LocalEngineAdapter.rotateScript("ssh-rsa AAAAB3NzaC1yc2E= vps-autopilot@x", "add");
        assertThat(add).contains("<<'AUTOPILOT_KEY'").contains("\nssh-rsa AAAAB3NzaC1yc2E= vps-autopilot@x\nAUTOPILOT_KEY\n")
                .contains("grep -qxF").contains("ROTATE_ADDED").contains("printf '%s\\n'");
        String remove = LocalEngineAdapter.rotateScript("AAAAB3NzaC1yc2E=", "remove");
        assertThat(remove).contains("ROTATE_WOULD_EMPTY").contains("grep -vF").contains("ROTATE_REMOVED");
    }

    @Test
    void failuresAreClassifiedForTheServerState() {
        assertThat(LocalEngineAdapter.classify("Authentification refusée (ubuntu@h)")).isEqualTo("auth_failed");
        assertThat(LocalEngineAdapter.classify("Empreinte SSH inconnue pour h")).isEqualTo("host_key_mismatch");
        assertThat(LocalEngineAdapter.classify("Commande distante exit=3")).isEqualTo("remote_failure");
        assertThat(LocalEngineAdapter.exitCodeOf("Commande distante exit=3\n###STDOUT###")).isEqualTo(3);
    }
}
