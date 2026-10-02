package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlConsoleTest {

    @Test
    void selectsAndShowsAreRead() {
        var a = SqlConsolePolicy.analyze("SELECT * FROM membre LIMIT 5; SHOW TABLES;\n-- note\nDESCRIBE membre");
        assertThat(a.kind()).isEqualTo(SqlConsolePolicy.Kind.READ);
        assertThat(a.statements()).extracting(SqlConsolePolicy.Statement::keyword)
                .containsExactly("SELECT", "SHOW", "DESCRIBE");
    }

    @Test
    void anyWriteMakesTheScriptAChangeAndWarnsWithoutWhere() {
        var a = SqlConsolePolicy.analyze("SELECT 1; UPDATE membre SET actif = 0; DELETE FROM log WHERE id = 3;");
        assertThat(a.kind()).isEqualTo(SqlConsolePolicy.Kind.WRITE);
        assertThat(a.warnings()).hasSize(1).first().asString().contains("pas de WHERE");
    }

    @Test
    void semicolonsAndKeywordsInsideStringsAreIgnored() {
        var a = SqlConsolePolicy.analyze("INSERT INTO note(t) VALUES ('a; DROP TABLE x; INTO OUTFILE ''y''')");
        assertThat(a.statements()).hasSize(1);
        assertThat(a.statements().get(0).sql()).contains("DROP TABLE x");
    }

    @Test
    void destructiveOrServerLevelStatementsAreRefused() {
        for (String sql : List.of(
                "DROP TABLE membre",
                "truncate membre",
                "SELECT 1; DROP DATABASE app_arc",
                "CREATE DATABASE autre",
                "CREATE USER x IDENTIFIED BY 'y'",
                "GRANT ALL ON *.* TO x",
                "USE mysql",
                "SET GLOBAL max_connections = 1",
                "SET PASSWORD = 'x'",
                "SELECT * FROM membre INTO OUTFILE '/tmp/x'",
                "SELECT LOAD_FILE('/etc/passwd')",
                "CREATE OR REPLACE TABLE membre (id INT)",
                "/*!50000 DROP TABLE membre */",
                "DELIMITER $$",
                "SELECT 1\n\\! rm -rf /",
                "LOAD DATA INFILE 'x' INTO TABLE t")) {
            assertThatThrownBy(() -> SqlConsolePolicy.analyze(sql)).as(sql).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void executableCommentsOfDumpsAreKeptAsCode() {
        var a = SqlConsolePolicy.analyze("/*!40101 SET NAMES utf8mb4 */;\nINSERT INTO t VALUES (1);");
        assertThat(a.statements()).extracting(SqlConsolePolicy.Statement::keyword).containsExactly("SET", "INSERT");
        assertThat(a.statements().get(0).sql()).contains("SET NAMES utf8mb4").doesNotContain("/*");
    }

    @Test
    void unclosedQuoteIsRefused() {
        assertThatThrownBy(() -> SqlConsolePolicy.analyze("SELECT 'abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void prepareLimitsSelectsAndCountsModifiedRows() {
        var a = SqlConsolePolicy.analyze("SELECT 1;\nUPDATE t SET a = 1\nWHERE id = 2;\nSELECT 2");
        var p = SqlConsolePolicy.prepare(a);
        assertThat(p.script()).startsWith("SET SESSION sql_select_limit=1000;\n");
        assertThat(p.script()).contains("SELECT ROW_COUNT() AS " + SqlConsolePolicy.ROW_COUNT_ALIAS);
        assertThat(p.startLines()).containsExactly(2, 3, 6);
    }

    @Test
    void interpretMapsXmlResultsRowCountsAndNulls() {
        var a = SqlConsolePolicy.analyze("SELECT id, nom FROM membre; UPDATE membre SET nom = 'x' WHERE id = 1; ALTER TABLE membre ADD c INT");
        var p = SqlConsolePolicy.prepare(a);
        String raw = String.join("\n",
                "SQL_DB=app_arc",
                "SQL_BACKUP=/opt/vps-autopilot/platforms/arc/backups/app_arc-before-sql-1.sql.gz",
                "SQL_OUTPUT_BYTES=400",
                "###SQL_BEGIN###",
                "<?xml version=\"1.0\"?>",
                "<resultset statement=\"SELECT id, nom FROM membre\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
                "  <row><field name=\"id\">1</field><field name=\"nom\" xsi:nil=\"true\" /></row>",
                "  <row><field name=\"id\">2</field><field name=\"nom\">Awa &amp; Co</field></row>",
                "</resultset>",
                "<?xml version=\"1.0\"?>",
                "<resultset statement=\"SELECT ROW_COUNT()\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">",
                "  <row><field name=\"autopilot_rows\">1</field></row>",
                "</resultset>",
                "###SQL_END###",
                "SQL_DONE");
        var r = PlatformSqlService.interpret("arc", a, p, raw);
        assertThat(r.risk()).isEqualTo("CHANGE");
        assertThat(r.database()).isEqualTo("app_arc");
        assertThat(r.backup()).endsWith(".sql.gz");
        assertThat(r.error()).isEmpty();
        var select = r.statements().get(0);
        assertThat(select.columns()).containsExactly("id", "nom");
        assertThat(select.rows()).hasSize(2);
        assertThat(select.rows().get(0)).isEqualTo(Arrays.asList("1", null));
        assertThat(select.rows().get(1).get(1)).isEqualTo("Awa & Co");
        assertThat(r.statements().get(1).affectedRows()).isEqualTo(1L);
        assertThat(r.statements()).extracting(PlatformSqlService.StatementResult::status).containsOnly("ok");
    }

    @Test
    void interpretMarksTheFailingStatementAndSkipsTheRest() {
        var a = SqlConsolePolicy.analyze("SELECT 1;\nSELECT * FROM absente;\nSELECT 3");
        var p = SqlConsolePolicy.prepare(a);
        String raw = String.join("\n",
                "SQL_DB=app_arc",
                "SQL_OUTPUT_BYTES=100",
                "###SQL_BEGIN###",
                "<?xml version=\"1.0\"?>",
                "<resultset statement=\"SELECT 1\"><row><field name=\"1\">1</field></row></resultset>",
                "###SQL_END###",
                "SQL_FAILED",
                "SQL_ERROR=--------------",
                "SQL_ERROR=SELECT * FROM absente",
                "SQL_ERROR=--------------",
                "SQL_ERROR=ERROR 1146 (42S02) at line 3: Table 'app_arc.absente' doesn't exist",
                "SQL_DONE");
        var r = PlatformSqlService.interpret("arc", a, p, raw);
        assertThat(r.error()).startsWith("ERROR 1146").doesNotContain("---");
        assertThat(r.statements()).extracting(PlatformSqlService.StatementResult::status)
                .containsExactly("ok", "error", "skipped");
        assertThat(r.statements().get(0).rows()).hasSize(1);
    }

    @Test
    void bitColumnsWrittenAsRawBytesDoNotDropTheRows() {
        String xml = String.join("\n",
                "<?xml version=\"1.0\"?>",
                "<resultset statement=\"SELECT id, is_locked FROM users\">",
                "  <row><field name=\"id\">2</field><field name=\"is_locked\">\u0001</field></row>",
                "  <row><field name=\"id\">7</field><field name=\"is_locked\">\u0000</field></row>",
                "</resultset>");
        var sets = PlatformSqlService.parseXml(xml);
        assertThat(sets).hasSize(1);
        assertThat(sets.get(0).columns()).containsExactly("id", "is_locked");
        assertThat(sets.get(0).rows()).containsExactly(List.of("2", "1"), List.of("7", "0"));
    }
}
