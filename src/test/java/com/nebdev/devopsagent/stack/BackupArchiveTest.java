package com.nebdev.devopsagent.stack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupArchiveTest {

    private static final String PASS = "0123456789abcdef0123456789abcdef";

    private static final String DUMP = """
            /*M!999999\\- enable the sandbox mode */
            -- MariaDB dump 10.19  Distrib 11.4.5-MariaDB
            /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
            /*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;

            --
            -- Current Database: `mysql`
            --

            CREATE DATABASE /*!32312 IF NOT EXISTS*/ `mysql` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

            USE `mysql`;
            CREATE TABLE `user` (
              `Host` char(255) NOT NULL
            );

            --
            -- Current Database: `app_shop`
            --

            CREATE DATABASE /*!32312 IF NOT EXISTS*/ `app_shop` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

            USE `app_shop`;
            CREATE TABLE `orders` (
              `id` int NOT NULL
            );
            INSERT INTO `orders` VALUES (1),(2);
            CREATE TABLE `items` (
              `id` int NOT NULL
            );

            --
            -- Current Database: `app_blog`
            --

            CREATE DATABASE /*!32312 IF NOT EXISTS*/ `app_blog` /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

            USE `app_blog`;
            CREATE TABLE `posts` (
              `id` int NOT NULL
            );
            /*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;
            /*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;

            -- Dump completed on 2026-10-13 10:00:00
            """;

    @TempDir
    Path dir;

    @Test
    void completeDumpIsVerifiedWithTablesPerDatabase() throws Exception {
        Path enc = encrypt(DUMP, PASS);
        BackupArchive.Verification v = BackupArchive.verify(enc, PASS);
        assertThat(v.ok()).isTrue();
        assertThat(v.completed()).isTrue();
        assertThat(v.tablesByDatabase()).containsEntry("mysql", 1).containsEntry("app_shop", 2).containsEntry("app_blog", 1);
        assertThat(v.applicationDatabases()).containsExactly("app_shop", "app_blog");
        assertThat(v.applicationTables()).isEqualTo(3);
    }

    @Test
    void truncatedDumpIsRejected() throws Exception {
        String truncated = DUMP.substring(0, DUMP.indexOf("-- Dump completed"));
        BackupArchive.Verification v = BackupArchive.verify(encrypt(truncated, PASS), PASS);
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("pas de dump SQL complet");
    }

    @Test
    void dockerErrorCapturedInsteadOfDumpIsRejected() throws Exception {
        String legacy = "OCI runtime exec failed: exec failed: unable to start container process: "
                + "exec: \"mysqldump\": executable file not found in $PATH: unknown\n";
        BackupArchive.Verification v = BackupArchive.verify(encrypt(legacy, PASS), PASS);
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("mysqldump");
    }

    @Test
    void wrongPassphraseIsRejected() throws Exception {
        BackupArchive.Verification v = BackupArchive.verify(encrypt(DUMP, PASS), "ffffffffffffffffffffffffffffffff");
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("déchiffrement ou décompression impossible");
    }

    @Test
    void serverWithoutDatabaseIsAcceptedAsSuch() throws Exception {
        BackupArchive.Verification v = BackupArchive.verify(encrypt("-- VPS Autopilot: no mariadb container\n", PASS), PASS);
        assertThat(v.ok()).isTrue();
        assertThat(v.noDatabase()).isTrue();
    }

    @Test
    void extractKeepsOnlyTargetDatabaseWithHeaderAndFooter() throws Exception {
        Path enc = encrypt(DUMP, PASS);
        Path out = dir.resolve("shop.sql.gz");
        int tables = BackupArchive.extractDatabase(enc, PASS, "app_shop", out);
        assertThat(tables).isEqualTo(2);
        String sql = gunzip(out);
        assertThat(sql).contains("SET @OLD_TIME_ZONE", "CREATE TABLE `orders`", "INSERT INTO `orders`",
                "CREATE TABLE `items`", "SET TIME_ZONE=@OLD_TIME_ZONE", "-- Dump completed");
        assertThat(sql).doesNotContain("`posts`", "`user`", "CREATE DATABASE", "USE `");
    }

    @Test
    void extractOfMissingDatabaseFailsAndLeavesNoFile() throws Exception {
        Path enc = encrypt(DUMP, PASS);
        Path out = dir.resolve("none.sql.gz");
        assertThatThrownBy(() -> BackupArchive.extractDatabase(enc, PASS, "app_none", out))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app_none");
        assertThat(out).doesNotExist();
    }

    @Test
    void filesArchiveCountsFilesAndBytesPerVolume() throws Exception {
        TarBuilder tar = new TarBuilder()
                .dir("plat_shop_uploads/_data/")
                .file("plat_shop_uploads/_data/a.jpg", "12345")
                .dir("plat_shop_uploads/_data/docs/")
                .file("plat_shop_uploads/_data/docs/b.pdf", "abc")
                .dir("plat_blog_uploads/_data/")
                .end();
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(tar.bytes(), PASS), PASS);
        assertThat(v.ok()).isTrue();
        assertThat(v.filesByVolume()).containsEntry("plat_shop_uploads", 2).containsEntry("plat_blog_uploads", 0);
        assertThat(v.bytesByVolume()).containsEntry("plat_shop_uploads", 8L);
        assertThat(v.totalFiles()).isEqualTo(2);
    }

    @Test
    void filesArchiveWithGnuLongNameIsRead() throws Exception {
        String longName = "plat_shop_uploads/_data/" + "x".repeat(120) + ".txt";
        TarBuilder tar = new TarBuilder().longName(longName).file("././@LongLink-target", "hello").end();
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(tar.bytes(), PASS), PASS);
        assertThat(v.ok()).isTrue();
        assertThat(v.filesByVolume()).containsEntry("plat_shop_uploads", 1);
    }

    @Test
    void truncatedFilesArchiveIsRejected() throws Exception {
        byte[] full = new TarBuilder().file("plat_shop_uploads/_data/a.jpg", "12345").bytes();
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(full, PASS), PASS);
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("tronquée");
    }

    @Test
    void filesArchiveWithEscapingPathIsRejected() throws Exception {
        TarBuilder tar = new TarBuilder().file("plat_shop_uploads/_data/../../etc/passwd", "x").end();
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(tar.bytes(), PASS), PASS);
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("chemin inattendu");
    }

    @Test
    void filesArchiveWithCorruptedHeaderIsRejected() throws Exception {
        byte[] bytes = new TarBuilder().file("plat_shop_uploads/_data/a.jpg", "12345").end().bytes();
        bytes[10] ^= 0x01;
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(bytes, PASS), PASS);
        assertThat(v.ok()).isFalse();
        assertThat(v.problem()).contains("somme de contrôle");
    }

    @Test
    void filesArchiveWithWrongPassphraseIsRejected() throws Exception {
        byte[] bytes = new TarBuilder().file("plat_shop_uploads/_data/a.jpg", "12345").end().bytes();
        BackupArchive.FilesVerification v = BackupArchive.verifyFiles(encrypt(bytes, PASS), "ffffffffffffffffffffffffffffffff");
        assertThat(v.ok()).isFalse();
    }

    /** Archive tar minimale (ustar, GNU {@code L} pour les noms longs), comme {@code tar -czf} sur le VPS. */
    private static final class TarBuilder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private String pendingLongName;

        TarBuilder dir(String name) {
            header(name, '5', 0);
            return this;
        }

        TarBuilder file(String name, String content) {
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            header(pendingLongName != null ? pendingLongName.substring(0, 99) : name, '0', data.length);
            pendingLongName = null;
            body(data);
            return this;
        }

        TarBuilder longName(String name) {
            byte[] data = (name + "\0").getBytes(StandardCharsets.UTF_8);
            header("././@LongLink", 'L', data.length);
            body(data);
            pendingLongName = name;
            return this;
        }

        TarBuilder end() {
            out.writeBytes(new byte[1024]);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private void header(String name, char type, long size) {
            byte[] h = new byte[512];
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(n, 0, h, 0, Math.min(n.length, 100));
            put(h, 100, "0000644");
            put(h, 108, "0000000");
            put(h, 116, "0000000");
            put(h, 124, String.format("%011o", size));
            put(h, 136, "00000000000");
            Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = (byte) type;
            put(h, 257, "ustar");
            put(h, 263, "00");
            long sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, String.format("%06o", sum));
            h[154] = 0;
            h[155] = ' ';
            out.writeBytes(h);
        }

        private void body(byte[] data) {
            out.writeBytes(data);
            int pad = (512 - data.length % 512) % 512;
            out.writeBytes(new byte[pad]);
        }

        private static void put(byte[] h, int off, String v) {
            byte[] b = v.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(b, 0, h, off, b.length);
        }
    }

    /** Même format que {@code openssl enc -aes-256-cbc -pbkdf2} d’un flux gzip. */
    private Path encrypt(String sql, String pass) throws Exception {
        return encrypt(sql.getBytes(StandardCharsets.UTF_8), pass);
    }

    private Path encrypt(byte[] plain, String pass) throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(plain);
        }
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        byte[] keyIv = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pass.toCharArray(), salt, 10_000, 48 * 8)).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(keyIv, 0, 32), "AES"),
                new IvParameterSpec(Arrays.copyOfRange(keyIv, 32, 48)));
        byte[] body = cipher.doFinal(gz.toByteArray());
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("Salted__".getBytes(StandardCharsets.US_ASCII));
        file.write(salt);
        file.write(body);
        Path enc = Files.createTempFile(dir, "dump-", ".sql.gz.enc");
        Files.write(enc, file.toByteArray());
        return enc;
    }

    private static String gunzip(Path gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
