package com.nebdev.devopsagent.discovery.model;

import java.util.Locale;
import java.util.Set;

/**
 * Familles d'OS serveur prises en charge en V0 (Linux SSH).
 * Windows Server : spécifié, pas d'executor bash.
 */
public enum OsFamily {
    AUTO,
    UBUNTU,
    DEBIAN,
    RHEL,
    ROCKY,
    ALMA,
    FEDORA,
    ALPINE,
    AMAZON,
    UNKNOWN;

    private static final Set<String> RHEL_LIKE = Set.of(
            "rhel", "centos", "rocky", "almalinux", "ol", "oraclelinux"
    );

    public static OsFamily fromDeclared(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw)) {
            return AUTO;
        }
        try {
            return OsFamily.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public static OsFamily fromOsReleaseId(String id) {
        if (id == null || id.isBlank()) {
            return UNKNOWN;
        }
        String key = id.toLowerCase(Locale.ROOT).replace("\"", "");
        return switch (key) {
            case "ubuntu" -> UBUNTU;
            case "debian" -> DEBIAN;
            case "rocky" -> ROCKY;
            case "almalinux" -> ALMA;
            case "fedora" -> FEDORA;
            case "alpine" -> ALPINE;
            case "amzn", "amznlinux" -> AMAZON;
            default -> RHEL_LIKE.contains(key) ? RHEL : UNKNOWN;
        };
    }

    public boolean compatibleWith(OsFamily detected) {
        if (this == AUTO || this == UNKNOWN || detected == UNKNOWN) {
            return true;
        }
        if (this == detected) {
            return true;
        }
        return isRhelLike() && detected.isRhelLike();
    }

    public boolean isRhelLike() {
        return this == RHEL || this == ROCKY || this == ALMA || this == AMAZON;
    }
}
