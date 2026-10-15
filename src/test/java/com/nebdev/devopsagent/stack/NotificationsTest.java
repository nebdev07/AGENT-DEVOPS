package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationsTest {

    private static final String SECRET = "S3cr\"et:p@ss$HOME\\x";

    private static Notifications.Settings valid(String tls, String host) {
        return Notifications.settings(host, 465, tls, "alertes@exemple.com", SECRET, "alertes@exemple.com",
                "client@exemple.com, ops@exemple.com;client@exemple.com", "Production client", true);
    }

    @Test
    void settingsAreValidatedWithoutLeakingThePassword() {
        Notifications.Settings s = valid("ssl", "smtp.exemple.com");
        assertThat(s.to()).containsExactly("client@exemple.com", "ops@exemple.com");
        assertThat(s.tls()).isEqualTo(Notifications.Tls.SSL);
        assertThat(s.toString()).doesNotContain(SECRET).contains("password=***");

        assertThatThrownBy(() -> valid("none", "smtp.exemple.com"))
                .hasMessageContaining("sans chiffrement").hasMessageNotContaining(SECRET);
        assertThat(valid("none", "127.0.0.1").tls()).isEqualTo(Notifications.Tls.NONE);
        assertThatThrownBy(() -> valid("ssl", "smtp exemple"))
                .hasMessageContaining("serveur SMTP invalide");
        assertThatThrownBy(() -> Notifications.settings("smtp.exemple.com", 587, "starttls", "u", "a\nb",
                "a@exemple.com", "b@exemple.com", "", true))
                .hasMessageContaining("mot de passe").hasMessageNotContaining("a\nb");
        assertThatThrownBy(() -> Notifications.recipients("a@x.com,b@x.com,c@x.com,d@x.com,e@x.com,f@x.com"))
                .hasMessageContaining("de 1 à 5");
        assertThatThrownBy(() -> Notifications.recipients("pas-une-adresse")).hasMessageContaining("destinataire");
        assertThat(Notifications.settings("smtp.exemple.com", 25, "starttls", "", SECRET, "a@exemple.com",
                "b@exemple.com", null, null).password()).isEmpty();
    }

    @Test
    void scriptCarriesThePasswordOnlyInBase64() {
        String script = RoleScript.loadNotify(Notifications.Mode.SET, valid("starttls", "smtp.exemple.com"));
        assertThat(script).doesNotContain(SECRET)
                .contains("NOTIFY_PASS_B64=" + Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8)))
                .contains("NOTIFY_TO=client@exemple.com,ops@exemple.com\n")
                .contains("NOTIFY_TLS=starttls\n")
                .contains("OnFailure=vps-autopilot-notify-failure@%n.service")
                .contains("ExecStartPost=-$DRILL_SCRIPT");

        String status = RoleScript.loadNotify(Notifications.Mode.STATUS, null);
        assertThat(status).startsWith("NOTIFY_MODE=status\nset -eu\n").doesNotContain("NOTIFY_PASS_B64=\n");
    }

    @Test
    void statusIsParsedWithoutPassword() {
        String raw = """
                NOTIFY_STATE=on
                CONF_NOTIFY_HOST=smtp.exemple.com
                CONF_NOTIFY_PORT=465
                CONF_NOTIFY_TLS=ssl
                CONF_NOTIFY_USER=alertes@exemple.com
                CONF_NOTIFY_FROM=alertes@exemple.com
                CONF_NOTIFY_TO=client@exemple.com,ops@exemple.com
                CONF_NOTIFY_MONTHLY=1
                CONF_NOTIFY_LABEL_B64=UHJvZHVjdGlvbiBjbGllbnQ=
                CONF_NOTIFY_PASS_SET=1
                NOTIFY_LAST_AT=2026-10-15T09:00:00Z
                NOTIFY_LAST_KIND=test
                NOTIFY_LAST_OK=0
                NOTIFY_LAST_ERROR=curl: (67) Login denied
                NOTIFY_DONE
                """;
        Notifications.Status s = Notifications.parseStatus(raw);
        assertThat(s.on()).isTrue();
        assertThat(s.port()).isEqualTo(465);
        assertThat(s.to()).isEqualTo(List.of("client@exemple.com", "ops@exemple.com"));
        assertThat(s.label()).isEqualTo("Production client");
        assertThat(s.passwordSet()).isTrue();
        assertThat(s.lastOk()).isFalse();
        assertThat(s.lastError()).contains("Login denied");

        assertThat(Notifications.sendError("x\nNOTIFY_SEND_FAILED=curl: (7) Failed to connect\n"))
                .isEqualTo("curl: (7) Failed to connect");
        assertThat(Notifications.parseStatus("NOTIFY_STATE=off\nNOTIFY_DONE\n").on()).isFalse();
    }
}
