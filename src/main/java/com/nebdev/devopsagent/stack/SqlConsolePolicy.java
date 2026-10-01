package com.nebdev.devopsagent.stack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Console SQL d’une plateforme : découpe un script en instructions et le classe en lecture ou écriture.
 * Tout ce qui détruit une base / un schéma, touche aux droits ou sort du conteneur est refusé.
 * Pur : aucune I/O.
 */
public final class SqlConsolePolicy {

    public enum Kind { READ, WRITE }

    /** Instruction prête à exécuter (sans commentaires), mot-clé de tête, et sortie attendue. */
    public record Statement(String sql, String keyword, boolean producesResult, boolean affectsRows) {}

    public record Analysis(Kind kind, List<Statement> statements, List<String> warnings) {}

    /** Script envoyé au client mariadb, et ligne de départ (1-based) de chaque instruction. */
    public record Prepared(String script, List<Integer> startLines) {}

    static final int MAX_CHARS = 5_000_000;
    static final int SELECT_LIMIT = 1000;
    static final String ROW_COUNT_ALIAS = "autopilot_rows";
    private static final String WHERE = "Console SQL";

    private static final Set<String> READ = Set.of("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH");
    private static final Set<String> WRITE = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "ALTER", "CREATE", "RENAME", "SET",
            "START", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "LOCK", "UNLOCK",
            "ANALYZE", "OPTIMIZE", "CHECK", "REPAIR");
    private static final Set<String> TABLE_MAINTENANCE = Set.of("ANALYZE", "OPTIMIZE", "CHECK", "REPAIR");
    private static final Set<String> AFFECTS_ROWS = Set.of("INSERT", "UPDATE", "DELETE", "REPLACE");

    private static final Pattern FIRST_WORD = Pattern.compile("^[\\s(]*([A-Za-z]+)");
    private static final Pattern CREATE_REFUSED = Pattern.compile(
            "^CREATE\\s+(OR\\s+REPLACE\\s+)?(DATABASE|SCHEMA|USER|ROLE|SERVER)\\b");
    private static final Pattern CREATE_REPLACE_TABLE = Pattern.compile(
            "^CREATE\\s+OR\\s+REPLACE\\s+(TEMPORARY\\s+)?TABLE\\b");
    private static final Pattern ALTER_REFUSED = Pattern.compile("^ALTER\\s+(DATABASE|SCHEMA|USER|SERVER|INSTANCE)\\b");
    private static final Pattern SET_REFUSED = Pattern.compile(
            "^SET\\s+(PASSWORD|GLOBAL|@@GLOBAL|PERSIST|ROLE|DEFAULT\\s+ROLE|STATEMENT)\\b");
    private static final Pattern ANYWHERE_REFUSED = Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b|\\bLOAD_FILE\\s*\\(");
    private static final Pattern HAS_WHERE = Pattern.compile("\\bWHERE\\b");

    private SqlConsolePolicy() {}

    public static Analysis analyze(String sql) {
        if (sql == null || sql.isBlank()) {
            throw refused("le script est vide.", "écris une requête, ex. SELECT * FROM ma_table LIMIT 20;");
        }
        if (sql.length() > MAX_CHARS) {
            throw refused("script trop long (" + sql.length() + " caractères, max " + MAX_CHARS + ").",
                    "pour un dump complet, utilise « Restaurer les données ».");
        }
        List<String[]> parts = split(sql);
        if (parts.isEmpty()) {
            throw refused("le script ne contient que des commentaires.", "ajoute au moins une instruction SQL.");
        }
        List<Statement> statements = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Kind kind = Kind.READ;
        int index = 0;
        for (String[] part : parts) {
            index++;
            String exec = part[0].strip();
            String code = part[1].strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
            String keyword = firstWord(code);
            String label = "instruction " + index + " (" + abbreviate(exec) + ")";
            check(keyword, code, label);
            boolean read = READ.contains(keyword);
            if (!read) {
                kind = Kind.WRITE;
            }
            boolean affects = AFFECTS_ROWS.contains(keyword);
            if ((keyword.equals("UPDATE") || keyword.equals("DELETE")) && !HAS_WHERE.matcher(code).find()) {
                warnings.add(label + " : pas de WHERE, toutes les lignes de la table sont concernées.");
            }
            statements.add(new Statement(exec, keyword, read || TABLE_MAINTENANCE.contains(keyword), affects));
        }
        return new Analysis(kind, List.copyOf(statements), List.copyOf(warnings));
    }

    /** Limite les SELECT à {@value #SELECT_LIMIT} lignes et compte les lignes modifiées après chaque écriture. */
    public static Prepared prepare(Analysis analysis) {
        StringBuilder b = new StringBuilder("SET SESSION sql_select_limit=" + SELECT_LIMIT + ";\n");
        int line = 2;
        List<Integer> starts = new ArrayList<>();
        for (Statement s : analysis.statements()) {
            starts.add(line);
            b.append(s.sql()).append(";\n");
            line += s.sql().split("\n", -1).length;
            if (s.affectsRows()) {
                b.append("SELECT ROW_COUNT() AS ").append(ROW_COUNT_ALIAS).append(";\n");
                line++;
            }
        }
        return new Prepared(b.toString(), List.copyOf(starts));
    }

    private static void check(String keyword, String code, String label) {
        if (keyword.isEmpty()) {
            throw refused(label + " : instruction illisible.", "commence chaque instruction par un mot-clé SQL (SELECT, UPDATE…).");
        }
        if (keyword.equals("DROP") || keyword.equals("TRUNCATE")) {
            throw refused(label + " : " + keyword + " est refusé dans la console.",
                    "supprimer une table ou la vider entièrement reste hors console (DESTRUCTIVE). "
                            + "Pour remplacer toute la base, utilise « Restaurer les données ».");
        }
        if (keyword.equals("DELIMITER")) {
            throw refused(label + " : DELIMITER n’est pas supporté.",
                    "procédures stockées : passe par « Restaurer les données » avec un dump.");
        }
        if (!READ.contains(keyword) && !WRITE.contains(keyword)) {
            throw refused(label + " : « " + keyword + " » n’est pas autorisé dans la console.",
                    "autorisé : SELECT, SHOW, DESCRIBE, EXPLAIN, INSERT, UPDATE, DELETE, REPLACE, ALTER, CREATE (table, index, vue), transactions.");
        }
        if (CREATE_REFUSED.matcher(code).find() || ALTER_REFUSED.matcher(code).find()) {
            throw refused(label + " : créer ou modifier une base ou un utilisateur est refusé.",
                    "la console travaille uniquement dans la base de la plateforme.");
        }
        if (CREATE_REPLACE_TABLE.matcher(code).find()) {
            throw refused(label + " : CREATE OR REPLACE TABLE supprime la table existante.",
                    "utilise CREATE TABLE IF NOT EXISTS, ou ALTER TABLE.");
        }
        if (SET_REFUSED.matcher(code).find()) {
            throw refused(label + " : ce SET touche au serveur ou aux droits.",
                    "seuls les réglages de session sont permis (SET NAMES, SET @variable, SET SESSION …).");
        }
        if (ANYWHERE_REFUSED.matcher(code).find()) {
            throw refused(label + " : lire ou écrire des fichiers du serveur est refusé.",
                    "retire INTO OUTFILE / INTO DUMPFILE / LOAD_FILE.");
        }
    }

    /**
     * Découpe sur « ; » hors chaînes et commentaires. Pour chaque instruction : [texte à exécuter, code sans chaînes].
     * Les commentaires exécutables « /*! … *&#47; » sont gardés comme du code (le serveur les exécute).
     */
    static List<String[]> split(String sql) {
        List<String[]> out = new ArrayList<>();
        StringBuilder exec = new StringBuilder();
        StringBuilder code = new StringBuilder();
        int n = sql.length();
        int i = 0;
        boolean inExecComment = false;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"' || c == '`') {
                int end = quoteEnd(sql, i, c);
                exec.append(sql, i, end);
                code.append(c == '`' ? sql.substring(i, end) : "''");
                i = end;
                continue;
            }
            if (c == '#' || (c == '-' && next == '-' && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                boolean executable = i + 2 < n && (sql.charAt(i + 2) == '!'
                        || (sql.charAt(i + 2) == 'M' && i + 3 < n && sql.charAt(i + 3) == '!'));
                if (executable) {
                    i += sql.charAt(i + 2) == '!' ? 3 : 4;
                    while (i < n && Character.isDigit(sql.charAt(i))) {
                        i++;
                    }
                    inExecComment = true;
                    exec.append(' ');
                    code.append(' ');
                    continue;
                }
                int close = sql.indexOf("*/", i + 2);
                if (close < 0) {
                    throw refused("commentaire /* non fermé.", "ferme le commentaire avec */.");
                }
                exec.append(' ');
                code.append(' ');
                i = close + 2;
                continue;
            }
            if (c == '*' && next == '/' && inExecComment) {
                inExecComment = false;
                exec.append(' ');
                code.append(' ');
                i += 2;
                continue;
            }
            if (c == '\\') {
                throw refused("barre oblique inverse hors d’une chaîne (commande client mariadb).",
                        "la console n’exécute que du SQL : retire les commandes \\…");
            }
            if (c == ';') {
                flush(out, exec, code);
                i++;
                continue;
            }
            exec.append(c);
            code.append(c);
            i++;
        }
        flush(out, exec, code);
        return out;
    }

    private static int quoteEnd(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw refused("guillemet " + quote + " non fermé.", "vérifie les chaînes de caractères du script.");
    }

    private static void flush(List<String[]> out, StringBuilder exec, StringBuilder code) {
        if (!code.toString().isBlank()) {
            out.add(new String[] {exec.toString(), code.toString()});
        }
        exec.setLength(0);
        code.setLength(0);
    }

    private static String firstWord(String code) {
        Matcher m = FIRST_WORD.matcher(code);
        return m.find() ? m.group(1) : "";
    }

    private static String abbreviate(String sql) {
        String s = sql.replaceAll("\\s+", " ").strip();
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }

    private static IllegalArgumentException refused(String what, String todo) {
        return new IllegalArgumentException(OperatorMessage.of(what, WHERE, todo));
    }
}
