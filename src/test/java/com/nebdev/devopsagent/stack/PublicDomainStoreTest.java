package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicDomainStoreTest {

    @Test
    void suggestPrefersBaseDomain() {
        assertThat(PublicDomainStore.suggestHost("my-bourse", "apps.neb.dev", "57.128.186.38"))
                .isEqualTo("my-bourse.apps.neb.dev");
    }

    @Test
    void suggestFallsBackToSslip() {
        assertThat(PublicDomainStore.suggestHost("my-bourse", "", "57.128.186.38"))
                .isEqualTo("my-bourse.57.128.186.38.sslip.io");
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
