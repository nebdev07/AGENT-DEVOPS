package com.nebdev.devopsagent.stack;

import com.nebdev.devopsagent.risk.ActionGuard;
import com.nebdev.devopsagent.risk.ForbiddenTargetGuard;
import com.nebdev.devopsagent.ssh.SshConnector;
import com.nebdev.devopsagent.ssh.SshTarget;
import org.springframework.stereotype.Service;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Console SQL d’une plateforme : lecture directe, écriture = CHANGE confirmé avec sauvegarde de la base avant.
 * Exécution dans le conteneur mariadb avec l’utilisateur de la plateforme (droits limités à sa base).
 */
@Service
public class PlatformSqlService {

    static final int READ_TIMEOUT_SECONDS = 120;
    static final int WRITE_TIMEOUT_SECONDS = 900;
    /** Doit rester égal à MAX_OUTPUT_BYTES de roles/platform-sql/apply.sh. */
    static final long MAX_OUTPUT_BYTES = 4_000_000;
    private static final String WHERE = "Console SQL";
    private static final Pattern ERROR_LINE = Pattern.compile("\\bat line (\\d+)");

    private final PlatformIntentStore store;
    private final ActionGuard actionGuard;
    private final ForbiddenTargetGuard forbiddenTargetGuard;
    private final SshConnector sshConnector;
    private final SecureRandom random = new SecureRandom();

    public PlatformSqlService(
            PlatformIntentStore store,
            ActionGuard actionGuard,
            ForbiddenTargetGuard forbiddenTargetGuard,
            SshConnector sshConnector
    ) {
        this.store = store;
        this.actionGuard = actionGuard;
        this.forbiddenTargetGuard = forbiddenTargetGuard;
        this.sshConnector = sshConnector;
    }

    public record ResultSet(List<String> columns, List<List<String>> rows) {}

    public record StatementResult(
            int index,
            String sql,
            String status,
            List<String> columns,
            List<List<String>> rows,
            Long affectedRows
    ) {}

    public record SqlResult(
            String platformId,
            String risk,
            String database,
            String backup,
            List<StatementResult> statements,
            String error,
            boolean outputTruncated
    ) {}

    public SqlConsolePolicy.Analysis analyze(String sql) {
        return SqlConsolePolicy.analyze(sql);
    }

    public SqlResult run(String platformId, SshTarget target, String sql, boolean confirmed) throws IOException {
        SqlConsolePolicy.Analysis analysis = SqlConsolePolicy.analyze(sql);
        if (analysis.kind() == SqlConsolePolicy.Kind.WRITE) {
            actionGuard.assertConfirmedChange("sql-write-platform", confirmed);
        }
        forbiddenTargetGuard.assertAllowed(target.host());
        PlatformSpec platform = store.require(platformId);
        if (!PlatformCompose.needsDb(platform)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "la plateforme « " + platform.id() + " » n’a pas de base MariaDB.",
                    WHERE,
                    "coche MariaDB sur le service qui utilise la base, Enregistrer, Déployer."));
        }
        SqlConsolePolicy.Prepared prepared = SqlConsolePolicy.prepare(analysis);
        String remoteDir = "/tmp/autopilot-sql-" + platform.id() + "-" + HexFormat.of().formatHex(nextBytes());
        String script = RoleScript.loadPlatformSql(platform, remoteDir, analysis.kind());
        int timeout = switch (analysis.kind()) {
            case READ -> READ_TIMEOUT_SECONDS;
            case WRITE -> WRITE_TIMEOUT_SECONDS;
        };
        Path tmp = Files.createTempFile("autopilot-sql-", ".sql");
        String raw;
        try {
            Files.writeString(tmp, prepared.script(), StandardCharsets.UTF_8);
            sshConnector.uploadFiles(target, remoteDir, Map.of("script.sql", tmp));
            raw = sshConnector.runStdinScript(script, target, timeout);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            throwIfKnown(msg);
            throw new IllegalStateException(OperatorMessage.of(
                    "la console SQL n’a pas pu s’exécuter.", WHERE, "extrait : " + excerpt(msg)), e);
        } finally {
            Files.deleteIfExists(tmp);
        }
        throwIfKnown(raw);
        if (!raw.contains("SQL_DONE")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "le script s’est arrêté sans SQL_DONE.", WHERE, "extrait : " + excerpt(raw)));
        }
        return interpret(platform.id(), analysis, prepared, raw);
    }

    static SqlResult interpret(String platformId, SqlConsolePolicy.Analysis analysis,
                               SqlConsolePolicy.Prepared prepared, String raw) {
        String database = marker(raw, "SQL_DB=");
        String backup = marker(raw, "SQL_BACKUP=");
        long bytes = parseLong(marker(raw, "SQL_OUTPUT_BYTES="));
        int begin = raw.indexOf("###SQL_BEGIN###");
        int end = raw.lastIndexOf("###SQL_END###");
        String xml = begin >= 0 && end > begin ? raw.substring(begin + "###SQL_BEGIN###".length(), end) : "";
        List<ResultSet> sets = parseXml(xml);
        String error = raw.contains("SQL_FAILED") ? errorText(raw) : "";
        int failedAt = error.isEmpty() ? Integer.MAX_VALUE : failedStatement(error, prepared.startLines());

        List<StatementResult> out = new ArrayList<>();
        int next = 0;
        List<SqlConsolePolicy.Statement> statements = analysis.statements();
        for (int i = 0; i < statements.size(); i++) {
            SqlConsolePolicy.Statement s = statements.get(i);
            if (i > failedAt) {
                out.add(new StatementResult(i + 1, s.sql(), "skipped", List.of(), List.of(), null));
                continue;
            }
            if (i == failedAt) {
                out.add(new StatementResult(i + 1, s.sql(), "error", List.of(), List.of(), null));
                continue;
            }
            ResultSet rs = null;
            if (s.producesResult() && next < sets.size()) {
                rs = sets.get(next++);
            }
            Long affected = null;
            if (s.affectsRows() && next < sets.size()) {
                ResultSet count = sets.get(next++);
                if (!count.rows().isEmpty() && !count.rows().get(0).isEmpty()) {
                    affected = parseLong(count.rows().get(0).get(0));
                }
            }
            out.add(new StatementResult(i + 1, s.sql(), "ok",
                    rs == null ? List.of() : rs.columns(),
                    rs == null ? List.of() : rs.rows(),
                    affected));
        }
        String risk = switch (analysis.kind()) {
            case READ -> "READ";
            case WRITE -> "CHANGE";
        };
        return new SqlResult(platformId, risk, database, backup, List.copyOf(out), error,
                bytes > MAX_OUTPUT_BYTES);
    }

    /** Le client mariadb --xml écrit un document par résultat : on découpe sur la déclaration XML. */
    static List<ResultSet> parseXml(String output) {
        List<ResultSet> sets = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return sets;
        }
        for (String chunk : output.split("<\\?xml[^>]*\\?>")) {
            if (chunk.isBlank()) {
                continue;
            }
            ResultSetHandler handler = new ResultSetHandler();
            try {
                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setNamespaceAware(false);
                SAXParser parser = factory.newSAXParser();
                parser.parse(new InputSource(new StringReader(chunk.strip())), handler);
            } catch (Exception e) {
                handler.finishPartial();
            }
            sets.addAll(handler.sets);
        }
        return sets;
    }

    private static final class ResultSetHandler extends DefaultHandler {
        final List<ResultSet> sets = new ArrayList<>();
        private List<String> columns;
        private List<List<String>> rows;
        private List<String> row;
        private StringBuilder value;
        private boolean nil;
        private String field;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) {
            switch (qName) {
                case "resultset" -> {
                    columns = new ArrayList<>();
                    rows = new ArrayList<>();
                }
                case "row" -> row = new ArrayList<>();
                case "field" -> {
                    field = atts.getValue("name");
                    nil = "true".equals(atts.getValue("xsi:nil"));
                    value = new StringBuilder();
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (value != null) {
                value.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "field" -> {
                    if (row != null) {
                        row.add(nil ? null : value.toString());
                        if (rows.isEmpty() && columns != null) {
                            columns.add(field == null ? "" : field);
                        }
                    }
                    value = null;
                }
                case "row" -> {
                    if (rows != null && row != null) {
                        rows.add(Collections.unmodifiableList(row));
                    }
                    row = null;
                }
                case "resultset" -> finishPartial();
                default -> {
                }
            }
        }

        void finishPartial() {
            if (rows != null) {
                sets.add(new ResultSet(List.copyOf(columns), List.copyOf(rows)));
            }
            rows = null;
            columns = null;
        }
    }

    static int failedStatement(String error, List<Integer> startLines) {
        Matcher m = ERROR_LINE.matcher(error);
        if (!m.find()) {
            return startLines.size() - 1;
        }
        int line = Integer.parseInt(m.group(1));
        int index = -1;
        for (int i = 0; i < startLines.size(); i++) {
            if (startLines.get(i) <= line) {
                index = i;
            }
        }
        return index;
    }

    private static String errorText(String raw) {
        List<String> lines = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line.startsWith("SQL_ERROR=")) {
                String t = line.substring("SQL_ERROR=".length()).strip();
                if (!t.isEmpty() && !t.matches("-+")) {
                    lines.add(t);
                }
                if (t.startsWith("ERROR")) {
                    errors.add(t);
                }
            }
        }
        List<String> shown = errors.isEmpty() ? lines : errors;
        return shown.isEmpty() ? "erreur SQL (détail indisponible)." : String.join(" ", shown);
    }

    private static String marker(String raw, String prefix) {
        int end = raw.indexOf("###SQL_BEGIN###");
        String head = end >= 0 ? raw.substring(0, end) : raw;
        for (String line : head.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void throwIfKnown(String raw) {
        if (raw.contains("NEED_PASSWORDLESS_SUDO")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "l’utilisateur SSH n’a pas sudo sans mot de passe.", WHERE,
                    "utilise le compte de l’agent (sudoers NOPASSWD)."));
        }
        if (raw.contains("NEED_MARIADB")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "conteneur mariadb absent.", WHERE, "installe MariaDB (Préparer le serveur)."));
        }
        if (raw.contains("NEED_DB_ENV")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la base de cette plateforme n’existe pas encore sur ce serveur.", WHERE,
                    "Déployer la plateforme (ou Restaurer les données), puis réessaie."));
        }
        if (raw.contains("SQL_BACKUP_FAILED")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "la sauvegarde de la base avant écriture a échoué : rien n’a été exécuté.", WHERE,
                    "vérifie l’espace disque du VPS, puis réessaie."));
        }
        if (raw.contains("NEED_SQL_SPEC")) {
            throw new IllegalStateException(OperatorMessage.of(
                    "script introuvable sur le VPS.", WHERE, "réessaie (l’envoi SFTP a pu être interrompu)."));
        }
    }

    private byte[] nextBytes() {
        byte[] b = new byte[4];
        random.nextBytes(b);
        return b;
    }

    private static String excerpt(String raw) {
        String e = raw.replace('\n', ' ').strip();
        return e.length() > 240 ? e.substring(e.length() - 240) : e;
    }
}
