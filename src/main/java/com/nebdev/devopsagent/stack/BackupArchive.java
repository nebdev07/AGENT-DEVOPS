package com.nebdev.devopsagent.stack;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Lecture d’une sauvegarde de l’agent ({@code openssl enc -aes-256-cbc -pbkdf2} d’un dump gzip, ADR-034/041, ou d’une
 * archive tar.gz des volumes de fichiers, BIZ-11).
 * Lecture en flux : le dump n’est jamais entièrement en mémoire. Les octets sont lus en ISO-8859-1 pour être
 * réécrits à l’identique lors d’une extraction.
 */
public final class BackupArchive {

    /** Bases internes au serveur : jamais restaurées ni testées. */
    public static final Set<String> SYSTEM_DATABASES = Set.of("mysql", "information_schema", "performance_schema", "sys");

    private static final Pattern CURRENT_DB = Pattern.compile("^-- Current Database: `([^`]+)`");
    private static final String COMPLETED = "-- Dump completed";
    private static final String NO_DATABASE = "-- VPS Autopilot: no mariadb container";
    private static final String FOOTER_START = "/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;";
    private static final int TAR_BLOCK = 512;

    private BackupArchive() {}

    /**
     * @param ok         dump complet, ou serveur sans base déclaré comme tel
     * @param noDatabase le serveur n’avait pas de conteneur MariaDB au moment de la sauvegarde
     * @param problem    explication lisible si {@code ok} est faux
     */
    public record Verification(
            boolean ok,
            boolean noDatabase,
            boolean completed,
            long sqlBytes,
            Map<String, Integer> tablesByDatabase,
            String problem
    ) {
        public int applicationTables() {
            return tablesByDatabase.entrySet().stream()
                    .filter(e -> !SYSTEM_DATABASES.contains(e.getKey()))
                    .mapToInt(Map.Entry::getValue)
                    .sum();
        }

        public List<String> applicationDatabases() {
            return tablesByDatabase.keySet().stream().filter(d -> !SYSTEM_DATABASES.contains(d)).toList();
        }
    }

    public static Verification verify(Path encFile, String passphrase) {
        Map<String, Integer> tables = new LinkedHashMap<>();
        long bytes = 0;
        boolean completed = false;
        boolean noDatabase = false;
        String firstLine = null;
        try (BufferedReader in = reader(encFile, passphrase)) {
            String db = null;
            String line;
            while ((line = in.readLine()) != null) {
                bytes += line.length() + 1;
                if (firstLine == null && !line.isBlank()) {
                    firstLine = line;
                    noDatabase = line.startsWith(NO_DATABASE);
                }
                Matcher m = CURRENT_DB.matcher(line);
                if (m.find()) {
                    db = m.group(1);
                    tables.putIfAbsent(db, 0);
                } else if (db != null && line.startsWith("CREATE TABLE ")) {
                    tables.merge(db, 1, Integer::sum);
                } else if (line.startsWith(COMPLETED)) {
                    completed = true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return new Verification(false, false, false, bytes, Map.copyOf(tables),
                    "déchiffrement ou décompression impossible (" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " : " + e.getMessage()) + ").");
        }
        if (noDatabase) {
            return new Verification(true, true, false, bytes, Map.of(), null);
        }
        if (!completed) {
            String head = firstLine == null ? "fichier vide" : "début : « " + abbreviate(firstLine, 120) + " »";
            return new Verification(false, false, false, bytes, Map.copyOf(tables),
                    "le fichier ne contient pas de dump SQL complet (" + head + ").");
        }
        return new Verification(true, false, true, bytes, unmodifiable(tables), null);
    }

    /**
     * Écrit (gzip) la seule base {@code database} : en-tête du dump, section de la base, pied du dump.
     * Les lignes {@code CREATE DATABASE} / {@code USE} sont retirées (la base cible est choisie à l’import).
     * @return nombre de {@code CREATE TABLE} écrits
     */
    public static int extractDatabase(Path encFile, String passphrase, String database, Path outGz) throws IOException {
        List<String> footer = new ArrayList<>();
        boolean seenAnyDb = false;
        boolean inTarget = false;
        boolean inFooter = false;
        boolean found = false;
        int created = 0;
        try (BufferedReader in = reader(encFile, passphrase);
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                     new GZIPOutputStream(Files.newOutputStream(outGz)), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher m = CURRENT_DB.matcher(line);
                if (m.find()) {
                    seenAnyDb = true;
                    inTarget = m.group(1).equals(database);
                    found |= inTarget;
                    continue;
                }
                if (line.equals(FOOTER_START)) {
                    inFooter = true;
                    inTarget = false;
                }
                if (inFooter) {
                    footer.add(line);
                    continue;
                }
                boolean header = !seenAnyDb;
                if (!header && !inTarget) {
                    continue;
                }
                if (inTarget && (line.startsWith("CREATE DATABASE ") || line.startsWith("USE `"))) {
                    continue;
                }
                if (inTarget && line.startsWith("CREATE TABLE ")) {
                    created++;
                }
                out.write(line);
                out.write('\n');
            }
            for (String f : footer) {
                out.write(f);
                out.write('\n');
            }
        }
        if (!found) {
            Files.deleteIfExists(outGz);
            throw new IllegalArgumentException("La base « " + database + " » n’est pas dans cette sauvegarde.");
        }
        return created;
    }

    /**
     * Archive des volumes de fichiers ({@code files-*.tar.gz.enc}, BIZ-11) : entrées {@code <volume>/_data/…}.
     * @param filesByVolume fichiers ordinaires par volume (un volume vide est présent avec 0)
     * @param bytesByVolume taille cumulée des fichiers par volume
     */
    public record FilesVerification(
            boolean ok,
            Map<String, Integer> filesByVolume,
            Map<String, Long> bytesByVolume,
            String problem
    ) {
        public int totalFiles() {
            return filesByVolume.values().stream().mapToInt(Integer::intValue).sum();
        }

        public long totalBytes() {
            return bytesByVolume.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * Lit toute l’archive (déchiffrement, gzip, en-têtes tar avec somme de contrôle, fin d’archive) sans rien écrire.
     */
    public static FilesVerification verifyFiles(Path encFile, String passphrase) {
        Map<String, Integer> files = new LinkedHashMap<>();
        Map<String, Long> bytes = new LinkedHashMap<>();
        try (InputStream in = plainStream(encFile, passphrase)) {
            byte[] block = new byte[TAR_BLOCK];
            String longName = null;
            String paxPath = null;
            while (true) {
                if (in.readNBytes(block, 0, TAR_BLOCK) < TAR_BLOCK) {
                    return filesProblem(files, bytes, "archive tronquée (fin d’archive tar absente).");
                }
                if (isZero(block)) {
                    in.transferTo(OutputStream.nullOutputStream());
                    break;
                }
                if (!checksumMatches(block)) {
                    return filesProblem(files, bytes, "en-tête tar corrompu (somme de contrôle).");
                }
                char type = (char) block[156];
                long size = tarNumber(block, 124, 12);
                long padded = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
                if (type == 'L' || type == 'x') {
                    byte[] data = in.readNBytes((int) padded);
                    if (data.length < padded) {
                        return filesProblem(files, bytes, "archive tronquée.");
                    }
                    String text = new String(data, 0, (int) size, StandardCharsets.UTF_8);
                    if (type == 'L') {
                        longName = trimNul(text);
                    } else {
                        paxPath = paxPath(text);
                    }
                    continue;
                }
                if (type == 'K' || type == 'g') {
                    skipFully(in, padded);
                    continue;
                }
                String name = longName != null ? longName : paxPath != null ? paxPath : headerName(block);
                longName = null;
                paxPath = null;
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                String[] parts = name.split("/");
                if (name.startsWith("/") || Arrays.asList(parts).contains("..") || parts.length < 2
                        || !"_data".equals(parts[1])) {
                    return filesProblem(files, bytes, "chemin inattendu dans l’archive : « " + abbreviate(name, 120) + " ».");
                }
                files.putIfAbsent(parts[0], 0);
                bytes.putIfAbsent(parts[0], 0L);
                if (type == '0' || type == '\0' || type == '7') {
                    files.merge(parts[0], 1, Integer::sum);
                    bytes.merge(parts[0], size, Long::sum);
                }
                skipFully(in, padded);
            }
        } catch (IOException | RuntimeException e) {
            return filesProblem(files, bytes, "déchiffrement ou décompression impossible (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " : " + e.getMessage()) + ").");
        }
        if (files.isEmpty()) {
            return filesProblem(files, bytes, "archive sans aucun volume.");
        }
        return new FilesVerification(true, unmodifiable(files), unmodifiableLongs(bytes), null);
    }

    private static FilesVerification filesProblem(Map<String, Integer> files, Map<String, Long> bytes, String problem) {
        return new FilesVerification(false, unmodifiable(files), unmodifiableLongs(bytes), problem);
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean checksumMatches(byte[] h) {
        long sum = 0;
        for (int i = 0; i < TAR_BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        }
        return sum == tarNumber(h, 148, 8);
    }

    /** Champ numérique tar : octal ASCII, ou base 256 si le bit de poids fort est posé (GNU). */
    static long tarNumber(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            long v = h[off] & 0x7f;
            for (int i = off + 1; i < off + len; i++) {
                v = (v << 8) | (h[i] & 0xff);
            }
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte b = h[i];
            if (b == 0 || b == ' ') {
                if (v > 0) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new IllegalStateException("champ numérique tar invalide");
            }
            v = (v << 3) + (b - '0');
        }
        return v;
    }

    private static String headerName(byte[] h) {
        String name = cString(h, 0, 100);
        if ("ustar".equals(cString(h, 257, 5))) {
            String prefix = cString(h, 345, 155);
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    private static String cString(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static String trimNul(String s) {
        int i = s.indexOf('\0');
        return i < 0 ? s : s.substring(0, i);
    }

    /** Enregistrements pax « longueur clé=valeur\n » : seule la clé {@code path} est retenue. */
    private static String paxPath(String text) {
        for (String rec : text.split("\n")) {
            int sp = rec.indexOf(' ');
            if (sp > 0 && rec.startsWith("path=", sp + 1)) {
                return rec.substring(sp + 1 + "path=".length());
            }
        }
        return null;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) {
                if (in.read() < 0) {
                    throw new IOException("archive tronquée");
                }
                s = 1;
            }
            left -= s;
        }
    }

    private static Map<String, Long> unmodifiableLongs(Map<String, Long> m) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static BufferedReader reader(Path encFile, String passphrase) throws IOException {
        return new BufferedReader(new InputStreamReader(plainStream(encFile, passphrase), StandardCharsets.ISO_8859_1),
                64 * 1024);
    }

    private static InputStream plainStream(Path encFile, String passphrase) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(encFile));
        try {
            byte[] header = raw.readNBytes(16);
            if (header.length < 16 || !"Salted__".equals(new String(header, 0, 8, StandardCharsets.US_ASCII))) {
                throw new IOException("en-tête openssl absent");
            }
            byte[] salt = Arrays.copyOfRange(header, 8, 16);
            // openssl enc -aes-256-cbc -pbkdf2 : PBKDF2-HMAC-SHA256, 10 000 itérations, clé 32 o + IV 16 o.
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyIv = skf.generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, 10_000, 48 * 8)).getEncoded();
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Arrays.copyOfRange(keyIv, 0, 32), "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(keyIv, 32, 48)));
            return new GZIPInputStream(new CipherInputStream(raw, cipher), 64 * 1024);
        } catch (IOException e) {
            raw.close();
            throw e;
        } catch (Exception e) {
            raw.close();
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Map<String, Integer> unmodifiable(Map<String, Integer> m) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static String abbreviate(String v, int max) {
        String t = v.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
