package com.nebdev.devopsagent.audit;

import com.nebdev.devopsagent.audit.AuditReport.Facts;
import com.nebdev.devopsagent.audit.AuditReport.Finding;
import com.nebdev.devopsagent.audit.AuditReport.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHtmlRendererTest {

    private static AuditReport report(List<Finding> findings) {
        return new AuditReport("198.51.100.20", "vps-<test>", Instant.parse("2026-10-13T12:00:00Z"), "Ubuntu 24.04",
                85, "B", findings, List.of("Pare-feu actif."),
                new Facts("4 vCPU", "8 Go", "100 Go", "22, 443", "active", "clés", "Docker"));
    }

    @Test
    void rendersFindingsClientAndEscapesHtml() {
        String html = AuditHtmlRenderer.render(report(List.of(new Finding("ssh-root", Severity.ELEVE, "Accès",
                "Root <autorisé>", "o", "r", "Interdire root"))), "Société & Fils");

        assertThat(html)
                .startsWith("<!DOCTYPE html>")
                .contains("Préparé pour <strong>Société &amp; Fils</strong>")
                .contains("<h1>vps-&lt;test&gt;</h1>")
                .contains("Root &lt;autorisé&gt;")
                .contains("grade-B")
                .contains("1 risque(s) élevé(s)")
                .contains("lecture seule")
                .doesNotContain("<autorisé>");
    }

    @Test
    void cleanReportSaysSo() {
        String html = AuditHtmlRenderer.render(report(List.of()), "");
        assertThat(html).contains("Aucun constat").doesNotContain("Préparé pour");
    }
}
