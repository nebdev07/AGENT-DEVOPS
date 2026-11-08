package com.nebdev.devopsagent.saas;

import com.nebdev.devopsagent.saas.ai.AiProvider;
import com.nebdev.devopsagent.saas.ai.RecommendationValidator;
import com.nebdev.devopsagent.saas.shared.CryptoService;
import com.nebdev.devopsagent.saas.credential.SshKeys;
import com.nebdev.devopsagent.saas.identity.Totp;
import com.nebdev.devopsagent.saas.shared.Hashing;
import net.schmizz.sshj.SSHClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaasUnitTest {

    static final String KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void envelopeEncryptionBindsCiphertextToOrganizationAndPurpose() {
        CryptoService crypto = new CryptoService(KEY, "k1");
        byte[] dekA = crypto.newWrappedDek("org-a");
        byte[] ct = crypto.encrypt(dekA, "org-a", "credential|1", "secret".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(crypto.decrypt(dekA, "org-a", "credential|1", ct), StandardCharsets.UTF_8)).isEqualTo("secret");
        assertThatThrownBy(() -> crypto.decrypt(dekA, "org-a", "credential|2", ct)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.decrypt(dekA, "org-b", "credential|1", ct)).isInstanceOf(IllegalStateException.class);
        CryptoService otherKek = new CryptoService(java.util.Base64.getEncoder().encodeToString(new byte[]{
                9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9}), "k2");
        assertThatThrownBy(() -> otherKek.decrypt(dekA, "org-a", "credential|1", ct)).isInstanceOf(IllegalStateException.class);
        assertThat(crypto.encrypt(dekA, "org-a", "x", new byte[]{1})).isNotEqualTo(crypto.encrypt(dekA, "org-a", "x", new byte[]{1}));
    }

    @Test
    void masterKeyIsMandatoryAndMustBe32Bytes() {
        assertThatThrownBy(() -> new CryptoService("", "k1")).hasMessageContaining("AUTOPILOT_MASTER_KEY");
        assertThatThrownBy(() -> new CryptoService("c2hvcnQ=", "k1")).hasMessageContaining("32");
    }

    @Test
    void totpMatchesRfc6238Vector() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        // RFC 6238 annexe B, SHA1, T = 59 s → 94287082 ; sur 6 chiffres : 287082.
        assertThat(Totp.generate(secret, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.generate(secret, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.verify(secret, "abcdef", java.time.Instant.ofEpochSecond(59))).isFalse();
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void canonicalHashIgnoresKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("y", 2, "x", 3));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("x", 3, "y", 2));
        b.put("b", 1);
        assertThat(Hashing.canonicalHash(a)).isEqualTo(Hashing.canonicalHash(b));
        b.put("b", 2);
        assertThat(Hashing.canonicalHash(a)).isNotEqualTo(Hashing.canonicalHash(b));
    }

    @Test
    void generatedSshKeyIsLoadableBySshjAndImportRejectsEncryptedKeys() throws Exception {
        SshKeys.Pair pair = SshKeys.generate("vps-autopilot@test");
        assertThat(pair.publicOpenSsh()).startsWith("ssh-rsa ").endsWith(" vps-autopilot@test");
        assertThat(pair.fingerprint()).matches("SHA256:[A-Za-z0-9+/]{43}");
        try (SSHClient c = new SSHClient()) {
            assertThat(c.loadKeys(pair.privatePem(), null, null).getPrivate()).isNotNull();
        }
        SshKeys.Pair reparsed = SshKeys.parse(pair.privatePem(), "vps-autopilot@test");
        assertThat(reparsed.fingerprint()).isEqualTo(pair.fingerprint());
        assertThat(pair.toString()).doesNotContain("PRIVATE");
        assertThatThrownBy(() -> SshKeys.parse("-----BEGIN ENCRYPTED PRIVATE KEY-----\nxx\n", "c"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshKeys.parse("not a key", "c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aiOutputIsFilteredByDeterministicPolicy() {
        AiProvider.Advice advice = new AiProvider.Advice(List.of(
                new AiProvider.Recommendation("Install Docker", "why", "medium", "install_docker"),
                new AiProvider.Recommendation("Wipe disk", "x", "HIGH", "RM_RF_ROOT"),
                new AiProvider.Recommendation("Rotate key", "x", "HIGH", "ROTATE_CREDENTIAL"),
                new AiProvider.Recommendation("", "no title", "LOW", null),
                new AiProvider.Recommendation("Advice only", "text\u0007", "LOW", "null")), "s");
        RecommendationValidator.Result r = RecommendationValidator.validate(advice, Set.of("INSTALL_DOCKER"));
        assertThat(r.accepted()).extracting(AiProvider.Recommendation::title).containsExactly("Install Docker", "Advice only");
        assertThat(r.accepted().get(0).operationType()).isEqualTo("INSTALL_DOCKER");
        assertThat(r.accepted().get(1).operationType()).isNull();
        assertThat(r.accepted().get(1).rationale()).isEqualTo("text");
        assertThat(r.rejected()).isEqualTo(3);
    }

    @Test
    void webhookSignatureIsHmacOfTimestampAndBody() {
        String sig = com.nebdev.devopsagent.saas.billing.ManualPaymentProvider.sign("s", "1", "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(sig).hasSize(64).isEqualTo(com.nebdev.devopsagent.saas.billing.ManualPaymentProvider.sign("s", "1",
                "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(sig).isNotEqualTo(com.nebdev.devopsagent.saas.billing.ManualPaymentProvider.sign("s", "2",
                "{}".getBytes(StandardCharsets.UTF_8)));
    }
}
