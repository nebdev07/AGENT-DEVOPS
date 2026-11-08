package com.nebdev.devopsagent.journal;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {

    @TempDir
    Path dir;

    @Test
    void catalogLogsActionsButNotConsoleReads() {
        assertEquals(ActionCatalog.Risk.CHANGE, ActionCatalog.classify("POST", "/api/v1/backups/drill").risk());
        assertEquals(ActionCatalog.Risk.READ, ActionCatalog.classify("POST", "/api/v1/discover").risk());
        assertEquals(ActionCatalog.Risk.READ, ActionCatalog.classify("GET", "/api/v1/audit/report").risk());
        assertEquals(ActionCatalog.Risk.LOCAL, ActionCatalog.classify("PUT", "/api/v1/platforms").risk());
        assertNull(ActionCatalog.classify("GET", "/api/v1/backups"));
        assertNull(ActionCatalog.classify("GET", "/api/v1/journal"));
        assertNull(ActionCatalog.classify("POST", "/index.html"));
        assertEquals("POST /api/v1/new-thing", ActionCatalog.classify("POST", "/api/v1/new-thing").label());
    }

    @Test
    void storeAppendsPerServerAndListsNewestFirst() throws IOException {
        JournalStore store = new JournalStore(dir);
        store.append(entry("1.2.3.4", "2026-10-14T08:00:00Z", JournalEntry.OK));
        store.append(entry("1.2.3.4", "2026-10-14T09:00:00Z", JournalEntry.FAILED));
        store.append(entry("", "2026-10-14T10:00:00Z", JournalEntry.OK));
        Files.writeString(dir.resolve("1.2.3.4.jsonl"), "{tronq", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        List<JournalEntry> list = store.list("1.2.3.4", 0);
        assertEquals(2, list.size());
        assertEquals("2026-10-14T09:00:00Z", list.get(0).at());
        assertEquals(1, store.list("1.2.3.4", 1).size());
        assertEquals(1, store.list(null, 0).size());
        assertEquals(List.of("1.2.3.4", JournalStore.LOCAL_KEY), store.keys());
        assertEquals(JournalStore.LOCAL_KEY, JournalStore.key("..."));
        assertEquals("a_b", JournalStore.key("a/b"));
    }

    @Test
    void filterRecordsWhitelistedFieldsOnlyNeverSqlOrPassword() throws Exception {
        JournalStore store = new JournalStore(dir);
        JournalFilter filter = new JournalFilter(store);
        MockHttpServletRequest req = jsonPost("/api/v1/platforms/sql",
                "{\"id\":\"arc\",\"host\":\"1.2.3.4\",\"user\":\"ubuntu\",\"sql\":\"UPDATE users SET pwd='x'\",\"password\":\"s3cret\",\"confirm\":true}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain(new EchoServlet(200, "{\"risk\":\"CHANGE\",\"rows\":[]}")));

        assertEquals("{\"risk\":\"CHANGE\",\"rows\":[]}", res.getContentAsString());
        List<JournalEntry> list = store.list("1.2.3.4", 0);
        assertEquals(1, list.size());
        JournalEntry e = list.get(0);
        assertEquals("Requête SQL sur une plateforme", e.action());
        assertEquals("ubuntu", e.user());
        assertEquals(JournalEntry.OK, e.outcome());
        assertEquals("arc", e.details().get("id"));
        assertEquals("true", e.details().get("confirm"));
        assertEquals("CHANGE", e.details().get("risk"));
        assertTrue(e.details().containsKey("summary"));
        assertFalse(e.details().containsKey("sql"));
        assertFalse(e.details().containsKey("password"));
        String raw = Files.readString(dir.resolve("1.2.3.4.jsonl"));
        assertFalse(raw.contains("UPDATE"));
        assertFalse(raw.contains("s3cret"));
    }

    @Test
    void secretValueIsNeverJournaled() throws Exception {
        JournalStore store = new JournalStore(dir);
        JournalFilter filter = new JournalFilter(store);
        MockHttpServletRequest req = jsonPost("/api/v1/platforms/secrets",
                "{\"id\":\"arc\",\"service\":\"api\",\"name\":\"MAIL_PASSWORD\",\"value\":\"Hunter2!\",\"host\":\"1.2.3.4\",\"user\":\"ubuntu\",\"confirm\":true}");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain(new EchoServlet(200, "{\"status\":\"ok\"}")));

        JournalEntry e = store.list("1.2.3.4", 0).get(0);
        assertEquals("Secret d’exécution posé", e.action());
        assertEquals("arc", e.details().get("id"));
        assertEquals("api", e.details().get("service"));
        assertEquals("MAIL_PASSWORD", e.details().get("name"));
        assertEquals("true", e.details().get("confirm"));
        assertTrue(e.details().containsKey("summary"));
        assertFalse(e.details().containsKey("value"));
        assertFalse(Files.readString(dir.resolve("1.2.3.4.jsonl")).contains("Hunter2"));
    }

    @Test
    void filterRecordsRefusalWithErrorMessage() throws Exception {
        JournalStore store = new JournalStore(dir);
        JournalFilter filter = new JournalFilter(store);
        MockHttpServletRequest req = jsonPost("/api/v1/backups/run", "{\"host\":\"9.9.9.9\",\"user\":\"root\"}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain(new EchoServlet(400, "{\"error\":\"Confirmation requise.\"}")));

        JournalEntry e = store.list("9.9.9.9", 0).get(0);
        assertEquals(JournalEntry.FAILED, e.outcome());
        assertEquals(400, e.status());
        assertEquals("Confirmation requise.", e.error());
        assertEquals(400, res.getStatus());
    }

    @Test
    void filterUsesQueryParametersAndSkipsConsoleReads() throws Exception {
        JournalStore store = new JournalStore(dir);
        JournalFilter filter = new JournalFilter(store);
        MockHttpServletRequest del = new MockHttpServletRequest("DELETE", "/api/v1/platforms");
        del.addParameter("id", "demo");
        filter.doFilter(del, new MockHttpServletResponse(), new MockFilterChain(new EchoServlet(200, "{}")));
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/backups");
        get.addParameter("host", "1.2.3.4");
        filter.doFilter(get, new MockHttpServletResponse(), new MockFilterChain(new EchoServlet(200, "{}")));

        Map<String, String> details = store.list(null, 0).get(0).details();
        assertEquals("demo", details.get("id"));
        assertTrue(details.containsKey("summary"));
        assertTrue(store.list("1.2.3.4", 0).isEmpty());
    }

    @Test
    void pageFiltersByUtcDateAndPaginates() throws Exception {
        JournalStore store = new JournalStore(dir);
        store.append(entry("1.2.3.4", "2026-10-20T10:00:00Z", JournalEntry.OK));
        store.append(entry("1.2.3.4", "2026-10-20T22:00:00Z", JournalEntry.OK));
        store.append(entry("1.2.3.4", "2026-10-21T08:00:00Z", JournalEntry.FAILED));
        store.append(entry("1.2.3.4", "2026-10-21T09:00:00Z", JournalEntry.OK));

        JournalStore.Page day = store.page("1.2.3.4", "2026-10-21", 1, 10);
        assertEquals(2, day.total());
        assertEquals("2026-10-21", day.date());
        assertEquals(1, day.totalPages());
        assertTrue(day.dates().contains("2026-10-20"));
        assertTrue(day.dates().contains("2026-10-21"));

        JournalStore.Page p1 = store.page("1.2.3.4", "", 1, 2);
        assertEquals(4, p1.total());
        assertEquals(2, p1.totalPages());
        assertEquals(2, p1.entries().size());
        JournalStore.Page p2 = store.page("1.2.3.4", "", 2, 2);
        assertEquals(2, p2.page());
        assertEquals(2, p2.entries().size());
    }

    @Test
    void csvNeutralisesFormulasAndHtmlEscapes() {
        JournalEntry e = new JournalEntry("2026-10-14T08:00:00Z", "dell", "1.2.3.4", "ubuntu", "POST",
                "/api/v1/install", "Installation d’un composant", "CHANGE", JournalEntry.FAILED, 400, 12,
                Map.of("component", "=cmd"), "<script>\"x\"");
        String csv = JournalExport.csv(List.of(e));
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("\"14/10/2026 08:00:00\""));
        assertTrue(csv.contains("Composant : =cmd") || csv.contains("component"));
        assertEquals("\"'=1+1\"", JournalExport.cell("=1+1"));
        assertTrue(csv.contains("\"<script>\"\"x\"\"\""));

        String html = JournalExport.html("1.2.3.4", "Client <A>", List.of(e));
        assertTrue(html.contains("Client &lt;A&gt;"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>\"x\""));
        assertTrue(html.contains("1 action(s) : 1 modification(s)"));
        assertTrue(html.contains("échec"));
        assertTrue(html.contains("class=\"timeline\"") || html.contains("Chronologie") || html.contains("<article"));
    }

    private static JournalEntry entry(String host, String at, String outcome) {
        return new JournalEntry(at, "dell", host, "ubuntu", "POST", "/api/v1/discover", "Analyse du serveur",
                "READ", outcome, 200, 5, Map.of(), null);
    }

    private static MockHttpServletRequest jsonPost(String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /** Lit tout le corps (comme Jackson) puis répond avec le statut et le JSON donnés. */
    private static final class EchoServlet extends HttpServlet {
        private final int status;
        private final String body;

        EchoServlet(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            req.getInputStream().readAllBytes();
            resp.setStatus(status);
            resp.setContentType("application/json");
            resp.getWriter().write(body);
            resp.getWriter().flush();
        }
    }
}
