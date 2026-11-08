package com.nebdev.devopsagent.saas.identity;

import java.util.Locale;
import java.util.Set;

/** Langues supportées par l'interface et les e-mails (directive §15 : anglais et français d'abord). */
public final class Locales {

    public static final Set<String> SUPPORTED = Set.of("en", "fr");

    private Locales() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en";
        }
        String lang = Locale.forLanguageTag(raw.strip().replace('_', '-')).getLanguage();
        return SUPPORTED.contains(lang) ? lang : "en";
    }
}
