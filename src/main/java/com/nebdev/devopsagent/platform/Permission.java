package com.nebdev.devopsagent.platform;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catalogue stable des permissions RBAC (ADR-046). Les rôles portent un sous-ensemble éditable.
 */
public enum Permission {
    PLATFORM_ADMIN("platform.admin"),
    ACCOUNT_MANAGE("account.manage"),
    ROLE_MANAGE("role.manage"),
    SERVER_ASSIGN("server.assign"),
    SERVER_READ("server.read"),
    SERVER_ANALYZE("server.analyze"),
    SERVER_CHANGE_INSTALL("server.change.install"),
    SERVER_CHANGE_DEPLOY("server.change.deploy"),
    SERVER_CHANGE_BACKUP("server.change.backup"),
    SERVER_CHANGE_NOTIFY("server.change.notify"),
    SERVER_CHANGE_SECRET("server.change.secret"),
    SERVER_JOURNAL("server.journal"),
    AUDIT_READ("audit.read");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Permission parse(String raw) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        for (Permission p : values()) {
            if (p.code.equals(v)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Permission inconnue : " + raw);
    }

    public static Set<String> allCodes() {
        return Arrays.stream(values()).map(Permission::code).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Set<String> parseAll(Iterable<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw) {
            out.add(parse(s).code());
        }
        return out;
    }
}
