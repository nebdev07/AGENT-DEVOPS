package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSecretsTest {

    @Test
    void namesFollowEnvFileRules() {
        PlatformSecrets.requireName("OVH_MAIL_PASSWORD");
        PlatformSecrets.requireName("spring.mail.password");
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireName("1ABC"));
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireName("A B"));
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireName("A=B"));
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireName(""));
    }

    @Test
    void valueRulesNeverEchoTheValue() {
        PlatformSecrets.requireValue("p@ss$word#with spaces\"and\\slash");
        IllegalArgumentException quote = assertThrows(IllegalArgumentException.class,
                () -> PlatformSecrets.requireValue("abc'TOPSECRET"));
        assertFalse(quote.getMessage().contains("TOPSECRET"));
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireValue("a\nb"));
        assertThrows(IllegalArgumentException.class, () -> PlatformSecrets.requireValue(""));
        assertThrows(IllegalArgumentException.class,
                () -> PlatformSecrets.requireValue("x".repeat(PlatformSecrets.MAX_VALUE_CHARS + 1)));
    }

    @Test
    void scriptCarriesValueOnlyInBase64() {
        String value = "S3cr3t$HOME`id`";
        String script = RoleScript.loadPlatformSecret("arc", PlatformSecrets.Mode.SET, "api", "MAIL_PASSWORD",
                value, true);
        assertFalse(script.contains(value));
        assertTrue(script.contains("SECRET_VALUE_B64="
                + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "\n"));
        assertTrue(script.startsWith("PLATFORM_ID=arc\nSECRET_MODE=set\nSECRET_SERVICE=api\nSECRET_NAME=MAIL_PASSWORD\n"));
        assertTrue(script.contains("SECRET_RESTART=1\n"));
        long writes = 0;
        for (String line : script.split("\n")) {
            assertFalse(line.contains("echo") && line.matches(".*\\$\\{?VALUE\\b.*"),
                    "la valeur ne doit jamais être affichée : " + line);
            if (line.contains("printf") && line.contains("\"$VALUE\"")) {
                writes++;
                assertTrue(line.strip().endsWith("\\"), "seule écriture : vers le tube tee du fichier final");
            }
        }
        assertEquals(1, writes);

        String unset = RoleScript.loadPlatformSecret("arc", PlatformSecrets.Mode.UNSET, "api", "MAIL_PASSWORD",
                "ignored", false);
        assertFalse(unset.substring(0, unset.indexOf("set -eu")).contains("SECRET_VALUE_B64"));
        assertTrue(unset.contains("SECRET_RESTART=0\n"));
        String list = RoleScript.loadPlatformSecret("arc", PlatformSecrets.Mode.LIST, null, null, null, false);
        assertTrue(list.startsWith("PLATFORM_ID=arc\nSECRET_MODE=list\nset -eu\n"));

        assertThrows(IllegalArgumentException.class, () -> RoleScript.loadPlatformSecret("arc;rm",
                PlatformSecrets.Mode.LIST, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> RoleScript.loadPlatformSecret("arc",
                PlatformSecrets.Mode.SET, "api", "X", "a'b", true));
    }

    @Test
    void parsesNamesAndOutcome() {
        String raw = "SECRET_SERVICE=api\nSECRET_NAME=api:OVH_MAIL_PASSWORD\nSECRET_NAME=api:UMAMI_TOKEN\n"
                + "SECRET_SERVICE=site\nSECRET_LIST_DONE\n";
        assertEquals(Map.of("api", List.of("OVH_MAIL_PASSWORD", "UMAMI_TOKEN"), "site", List.of()),
                PlatformSecrets.parseList(raw));

        String done = "SECRET_REPLACED=MAIL_PASSWORD\nRESTARTED=api\nSECRET_DONE\n";
        assertEquals("SECRET_REPLACED", PlatformSecretService.action(done));
        assertEquals("RESTARTED", PlatformSecretService.restartState(done));
        assertEquals("RESTART_SKIPPED_NOT_DEPLOYED",
                PlatformSecretService.restartState("SECRET_ADDED=X\nRESTART_SKIPPED_NOT_DEPLOYED\nSECRET_DONE\n"));
        assertThrows(IllegalStateException.class, () -> PlatformSecretService.throwIfKnown("NEED_PLATFORM_DEPLOYED"));
    }
}
