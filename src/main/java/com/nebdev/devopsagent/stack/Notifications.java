package com.nebdev.devopsagent.stack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Alertes par e-mail envoyées par le VPS (BIZ-15, ADR-044) : règles des réglages et lecture de l’état.
 * Le mot de passe SMTP n’apparaît dans aucun message d’erreur ni dans {@link Settings#toString()}.
 */
public final class Notifications {

    public static final int MAX_RECIPIENTS = 5;
    static final int MAX_PASSWORD_CHARS = 256;
    static final int MAX_LABEL_CHARS = 60;
    private static final String WHERE = "Alertes e-mail";
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*$");
    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})+$");
    private static final Pattern USER = Pattern.compile("^[A-Za-z0-9._%+@-]{1,128}$");

    public enum Mode {
        SET("set"), TEST("test"), STATUS("status"), REMOVE("remove");

        private final String script;

        Mode(String script) {
            this.script = script;
        }

        public String script() {
            return script;
        }
    }

    /** SSL = port 465 (TLS implicite), STARTTLS = port 587, NONE = relais local seulement. */
    public enum Tls {
        SSL("ssl"), STARTTLS("starttls"), NONE("none");

        private final String script;

        Tls(String script) {
            this.script = script;
        }

        public String script() {
            return script;
        }

        public static Tls parse(String raw) {
            String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            for (Tls t : values()) {
                if (t.script.equals(v)) {
                    return t;
                }
            }
            throw new IllegalArgumentException(OperatorMessage.of(
                    "chiffrement inconnu « " + v + " ».", WHERE, "choisis SSL (465) ou STARTTLS (587)."));
        }
    }

    /**
     * Réglages validés. {@code user} vide = serveur SMTP sans authentification ; {@code password} vide = garder
     * celui déjà posé sur le VPS.
     */
    public record Settings(String host, int port, Tls tls, String user, String password, String from,
                           List<String> to, String label, boolean monthly) {
        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", tls=" + tls + ", user=" + user
                    + ", password=***, from=" + from + ", to=" + to + ", label=" + label + ", monthly=" + monthly + "]";
        }
    }

    /** État lu sur le VPS ; jamais le mot de passe, seulement s’il est posé. */
    public record Status(boolean on, String host, Integer port, String tls, String user, boolean passwordSet,
                         String from, List<String> to, String label, boolean monthly,
                         String lastAt, String lastKind, Boolean lastOk, String lastError) {}

    private Notifications() {}

    public static Settings settings(String host, Integer port, String tls, String user, String password,
                                    String from, String to, String label, Boolean monthly) {
        String h = host == null ? "" : host.strip();
        if (!HOST.matcher(h).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "serveur SMTP invalide.", WHERE, "indique un nom comme smtp.exemple.com."));
        }
        int p = port == null ? 0 : port;
        if (p < 1 || p > 65535) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "port SMTP invalide.", WHERE, "465 (SSL) ou 587 (STARTTLS) en général."));
        }
        Tls t = Tls.parse(tls);
        if (t == Tls.NONE && !isLocal(h)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "envoi sans chiffrement refusé vers " + h + " : le mot de passe circulerait en clair.", WHERE,
                    "choisis SSL ou STARTTLS ; « aucun » est réservé à un relais sur le serveur lui-même (127.0.0.1)."));
        }
        String u = user == null ? "" : user.strip();
        if (!u.isEmpty() && !USER.matcher(u).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "identifiant SMTP invalide.", WHERE, "lettres, chiffres et . _ % + @ - seulement."));
        }
        String pw = password == null ? "" : password;
        requirePassword(pw);
        String f = from == null ? "" : from.strip();
        if (!EMAIL.matcher(f).matches()) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "adresse d’expédition invalide.", WHERE, "indique une adresse autorisée par ton compte SMTP."));
        }
        List<String> rcpt = recipients(to);
        return new Settings(h, p, t, u, u.isEmpty() ? "" : pw, f, rcpt, requireLabel(label),
                !Boolean.FALSE.equals(monthly));
    }

    static List<String> recipients(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : (raw == null ? "" : raw).split("[,;\\s]+")) {
            String a = part.strip();
            if (a.isEmpty()) {
                continue;
            }
            if (!EMAIL.matcher(a).matches()) {
                throw new IllegalArgumentException(OperatorMessage.of(
                        "destinataire invalide : « " + a + " ».", WHERE, "une adresse e-mail par destinataire."));
            }
            out.add(a);
        }
        if (out.isEmpty() || out.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "indique de 1 à " + MAX_RECIPIENTS + " destinataires.", WHERE,
                    "séparés par des virgules (ex. le client et toi)."));
        }
        return List.copyOf(out);
    }

    static void requirePassword(String pw) {
        if (pw.length() > MAX_PASSWORD_CHARS || pw.indexOf('\n') >= 0 || pw.indexOf('\r') >= 0
                || pw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "mot de passe SMTP refusé (trop long ou retour à la ligne).", WHERE,
                    "colle le mot de passe sur une seule ligne."));
        }
    }

    static String requireLabel(String label) {
        String l = label == null ? "" : label.strip();
        if (l.length() > MAX_LABEL_CHARS || l.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(OperatorMessage.of(
                    "nom du serveur dans les messages trop long ou invalide.", WHERE,
                    MAX_LABEL_CHARS + " caractères au plus, sur une ligne."));
        }
        return l;
    }

    static boolean isLocal(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    public static Status parseStatus(String raw) {
        boolean on = false;
        String host = null;
        Integer port = null;
        String tls = null;
        String user = null;
        boolean passwordSet = false;
        String from = null;
        List<String> to = new ArrayList<>();
        String label = null;
        boolean monthly = false;
        String lastAt = null;
        String lastKind = null;
        Boolean lastOk = null;
        String lastError = null;
        for (String line : (raw == null ? "" : raw).split("\\R")) {
            String t = line.strip();
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String v = t.substring(eq + 1);
            switch (t.substring(0, eq)) {
                case "NOTIFY_STATE" -> on = "on".equals(v);
                case "CONF_NOTIFY_HOST" -> host = v;
                case "CONF_NOTIFY_PORT" -> port = parsePort(v);
                case "CONF_NOTIFY_TLS" -> tls = v;
                case "CONF_NOTIFY_USER" -> user = v;
                case "CONF_NOTIFY_PASS_SET" -> passwordSet = "1".equals(v);
                case "CONF_NOTIFY_FROM" -> from = v;
                case "CONF_NOTIFY_TO" -> to = List.of(v.split(","));
                case "CONF_NOTIFY_LABEL_B64" -> label = decode(v);
                case "CONF_NOTIFY_MONTHLY" -> monthly = "1".equals(v);
                case "NOTIFY_LAST_AT" -> lastAt = v;
                case "NOTIFY_LAST_KIND" -> lastKind = v;
                case "NOTIFY_LAST_OK" -> lastOk = "1".equals(v);
                case "NOTIFY_LAST_ERROR" -> lastError = v;
                default -> {
                }
            }
        }
        return new Status(on, host, port, tls, user, passwordSet, from, List.copyOf(to), label, monthly,
                lastAt, lastKind, lastOk, lastError);
    }

    /** @return message d’échec de l’envoi (ligne NOTIFY_SEND_FAILED=), ou {@code null} */
    static String sendError(String raw) {
        for (String line : (raw == null ? "" : raw).split("\\R")) {
            if (line.startsWith("NOTIFY_SEND_FAILED=")) {
                return line.substring("NOTIFY_SEND_FAILED=".length()).strip();
            }
        }
        return null;
    }

    private static Integer parsePort(String v) {
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decode(String v) {
        try {
            return new String(Base64.getDecoder().decode(v.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
