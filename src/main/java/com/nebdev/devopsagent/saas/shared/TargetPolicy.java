package com.nebdev.devopsagent.saas.shared;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cibles SSH autorisées (anti-SSRF, directive §6/§8). Une organisation ne doit pas pouvoir faire ouvrir au control plane
 * ou au runner une connexion vers le réseau interne de la plateforme : boucle locale, réseaux privés, lien local
 * (dont l'adresse de métadonnées cloud 169.254.169.254), CGNAT, multicast, adresses non routables.
 *
 * <p>Contrôlé à l'enregistrement du serveur <b>et</b> juste avant chaque connexion, sur l'adresse résolue ; la connexion
 * se fait ensuite vers cette adresse précise (pas de seconde résolution DNS exploitable par rebinding).
 * {@code allowPrivate} n'est destiné qu'au lab et aux déploiements auto-hébergés sur réseau privé.
 */
public final class TargetPolicy {

    private TargetPolicy() {}

    /** Refus explicite : code d'erreur stable (traduit par l'API). */
    public static final class Refused extends RuntimeException {
        private final String code;

        public Refused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static Set<String> normalize(Collection<String> denied) {
        return denied == null ? Set.of() : denied.stream().map(d -> d.strip().toLowerCase(Locale.ROOT))
                .filter(d -> !d.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * À l'enregistrement : refuse les adresses littérales interdites et les noms qui résolvent vers elles. Un nom qui
     * ne résout pas encore est accepté (il sera contrôlé à la connexion).
     */
    public static void checkRegistration(String host, boolean allowPrivate, Set<String> denied) {
        String h = clean(host);
        denyListed(h, denied);
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            return;
        }
        for (InetAddress a : all) {
            check(h, a, allowPrivate, denied);
        }
    }

    /** Juste avant la connexion : résolution obligatoire, toutes les adresses contrôlées, première adresse retenue. */
    public static InetAddress resolveForConnection(String host, boolean allowPrivate, Set<String> denied) {
        String h = clean(host);
        denyListed(h, denied);
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(h);
        } catch (UnknownHostException e) {
            throw new Refused("server_unreachable", "Nom d'hôte introuvable : " + h);
        }
        for (InetAddress a : all) {
            check(h, a, allowPrivate, denied);
        }
        return all[0];
    }

    private static void check(String host, InetAddress a, boolean allowPrivate, Set<String> denied) {
        if (denied.contains(a.getHostAddress().toLowerCase(Locale.ROOT))) {
            throw new Refused("target_not_allowed", host + " → " + a.getHostAddress() + " : cible interdite par la plateforme.");
        }
        if (isAlwaysForbidden(a) || (!allowPrivate && isPrivate(a))) {
            throw new Refused("target_not_allowed", host + " → " + a.getHostAddress()
                    + " : adresse interne ou non routable, refusée.");
        }
    }

    private static void denyListed(String host, Set<String> denied) {
        if (denied.contains(host.toLowerCase(Locale.ROOT))) {
            throw new Refused("target_not_allowed", host + " : cible interdite par la plateforme.");
        }
    }

    private static String clean(String host) {
        String h = host == null ? "" : host.strip();
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.isEmpty()) {
            throw new Refused("invalid_server", "Hôte vide.");
        }
        return h;
    }

    /** Jamais une cible SSH, même en lab : non spécifiée, multicast, diffusion, métadonnées cloud. */
    static boolean isAlwaysForbidden(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = v4(a);
        if (b != null) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0 || b0 >= 240 || (b0 == 169 && b1 == 254 && (b[2] & 0xff) == 169 && (b[3] & 0xff) == 254);
        }
        return false;
    }

    /** Réseaux internes : refusés hors lab. */
    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
            return true;
        }
        byte[] b = v4(a);
        if (b != null) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 127 || b0 == 10 || (b0 == 100 && b1 >= 64 && b1 <= 127) // CGNAT
                    || (b0 == 169 && b1 == 254) || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168)
                    || (b0 == 198 && (b1 == 18 || b1 == 19)); // bancs d'essai réseau
        }
        if (a instanceof Inet6Address) {
            int first = a.getAddress()[0] & 0xff;
            return (first & 0xfe) == 0xfc; // fc00::/7 (adresses uniques locales)
        }
        return false;
    }

    /** IPv4, y compris une IPv4 encapsulée dans une IPv6 (::ffff:a.b.c.d), sinon null. */
    private static byte[] v4(InetAddress a) {
        if (a instanceof Inet4Address) {
            return a.getAddress();
        }
        byte[] b = a.getAddress();
        if (b.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                return Arrays.copyOfRange(b, 12, 16);
            }
        }
        return null;
    }
}
