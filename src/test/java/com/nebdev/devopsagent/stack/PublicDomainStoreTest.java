package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDomainStoreTest {

    @Test
    void suggestPrefersBaseDomain() {
        assertThat(PublicDomainStore.suggestHost("mon-app", "apps.mondomaine.com", "198.51.100.20"))
                .isEqualTo("mon-app.apps.mondomaine.com");
    }

    @Test
    void suggestFallsBackToSslip() {
        assertThat(PublicDomainStore.suggestHost("mon-app", "", "198.51.100.20"))
                .isEqualTo("mon-app.198.51.100.20.sslip.io");
    }

    @Test
    void normalizeStripsSchemeAndWildcard() {
        assertThat(PublicDomainStore.normalize("https://*.Apps.Exemple.com/"))
                .isEqualTo("apps.exemple.com");
    }

    @Test
    void normalizeRejectsJunk() {
        assertThatThrownBy(() -> PublicDomainStore.normalize("not a domain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cause :");
    }
}
