package com.nebdev.devopsagent.stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Règles des secrets d’exécution d’une plateforme (BIZ-05) : nom de variable, valeur acceptée,
 * lecture de la sortie du rôle {@code platform-secret}. Aucune valeur n’est jamais renvoyée.
 */
public final class PlatformSecrets {

    public enum Mode {
        LIST("list"),
        SET("set"),
        UNSET("unset");

        private final String script;

        Mode(String script) {
            this.script = script;
        }

        public String script() {
            return script;
        }
    }

    static final int MAX_VALUE_CHARS = 4096;
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.]{0,127}$");

    private PlatformSecrets() {}

    static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Identifiant de plateforme ou de service invalide : " + id);
        }
    }

    public static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nom de variable invalide : lettres, chiffres, _ et . seulement, sans chiffre au début (ex. MAIL_PASSWORD).");
        }
    }

    /** Le message d’erreur ne cite jamais la valeur. */
    public static void requireValue(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Valeur vide : pour retirer la variable, utilise « Retirer ».");
        }
        if (value.length() > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("Valeur trop longue (" + MAX_VALUE_CHARS + " caractères max).");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\0') {
                throw new IllegalArgumentException("La valeur ne peut pas contenir de retour à la ligne.");
            }
            if (c == '\'') {
                throw new IllegalArgumentException(
                        "La valeur ne peut pas contenir d’apostrophe droite (') : change ce mot de passe chez le fournisseur.");
            }
        }
    }

    /** Sortie de {@code list} : service → noms des variables (ordre du fichier). */
    static Map<String, List<String>> parseList(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            if (line.startsWith("SECRET_SERVICE=")) {
                out.computeIfAbsent(line.substring("SECRET_SERVICE=".length()).strip(), k -> new ArrayList<>());
            } else if (line.startsWith("SECRET_NAME=")) {
                String v = line.substring("SECRET_NAME=".length()).strip();
                int colon = v.indexOf(':');
                if (colon > 0) {
                    out.computeIfAbsent(v.substring(0, colon), k -> new ArrayList<>()).add(v.substring(colon + 1));
                }
            }
        }
        return out;
    }
}
