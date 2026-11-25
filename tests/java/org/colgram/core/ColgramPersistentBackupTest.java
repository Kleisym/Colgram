package org.colgram.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure-Java JVM unit tests for ColgramPersistentBackup:
 * - Lossless gzip snapshot encode/decode across prefs, tgnet auth keys, vault DB, and vaulted accounts
 * - AES-256-GCM envelope round-trip for both device-key and user-passphrase modes
 * - Tamper detection on header/flags/salt/ciphertext and wrong-passphrase rejection
 * - Path traversal rejection on snapshot relative paths
 */
public final class ColgramPersistentBackupTest {

    public static void main(String[] args) throws Exception {
        testSnapshotAndEnvelopeRoundTrip();
        testPassphraseProtectionAndWrongPasswordRejected();
        testEnvelopeTamperRejected();
        testPathTraversalWhitelistRejected();
        System.out.println("ColgramPersistentBackupTest PASSED");
    }

    private static void testSnapshotAndEnvelopeRoundTrip() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("shared_prefs/userconfing.xml", "<map><string name=\"user\">TEST_USER</string></map>".getBytes(StandardCharsets.UTF_8));
        files.put("shared_prefs/colgram_secure_config.xml", "<map><boolean name=\"pm_mute\" value=\"true\"/></map>".getBytes(StandardCharsets.UTF_8));
        files.put("files/tgnet.dat", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        files.put("files/account1/tgnet.dat", new byte[]{11, 22, 33, 44, 55});
        files.put("databases/colgram_vault.db", "SQLite format 3\0mock".getBytes(StandardCharsets.US_ASCII));
        files.put("vault_raw/uid_777.bin", new byte[]{0x7f, 0x01, 0x02, 0x03});

        byte[] snap = ColgramBackupCodec.encodeSnapshot(files, 1795599999000L);
        char[] devKey = "Xiaomi|Xiaomi|mondrian|23013PC75G|qcom|kalama|mondrian|colgram_persistent_vault_v1".toCharArray();
        byte[] env = ColgramBackupCodec.encryptEnvelope(snap, devKey, false);

        assertTrue(!ColgramBackupCodec.isPassphraseProtected(env),
                "device-key backup must not report passphrase-protected");

        byte[] decrypted = ColgramBackupCodec.decryptEnvelope(env, devKey);
        Map<String, byte[]> restored = ColgramBackupCodec.decodeSnapshot(decrypted);
        assertTrue(restored.size() == files.size(),
                "restored entry count mismatch: " + restored.size() + " vs " + files.size());
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            assertTrue(Arrays.equals(e.getValue(), restored.get(e.getKey())),
                    "payload mismatch for " + e.getKey());
        }
    }

    private static void testPassphraseProtectionAndWrongPasswordRejected() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("shared_prefs/userconfing.xml", "<map><string name=\"user\">SECRET</string></map>".getBytes(StandardCharsets.UTF_8));
        byte[] snap = ColgramBackupCodec.encodeSnapshot(files, 1795599999000L);
        byte[] env = ColgramBackupCodec.encryptEnvelope(snap, "CorrectHorseBattery".toCharArray(), true);

        assertTrue(ColgramBackupCodec.isPassphraseProtected(env),
                "passphrase backup must report isPassphraseProtected=true");

        boolean rejected = false;
        try {
            ColgramBackupCodec.decryptEnvelope(env, "WrongPassword".toCharArray());
        } catch (GeneralSecurityException expected) {
            rejected = true;
        }
        assertTrue(rejected, "wrong passphrase must throw GeneralSecurityException");

        byte[] ok = ColgramBackupCodec.decryptEnvelope(env, "CorrectHorseBattery".toCharArray());
        Map<String, byte[]> decoded = ColgramBackupCodec.decodeSnapshot(ok);
        assertTrue(decoded.containsKey("shared_prefs/userconfing.xml"), "valid passphrase must decrypt snapshot");
    }

    private static void testEnvelopeTamperRejected() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("files/tgnet.dat", new byte[]{9, 8, 7, 6, 5});
        byte[] snap = ColgramBackupCodec.encodeSnapshot(files, 1795599999000L);
        char[] key = "test-device-key".toCharArray();
        byte[] env = ColgramBackupCodec.encryptEnvelope(snap, key, false);

        // Flip flag byte (authenticated via AAD) -> must reject!
        byte[] tamperedFlag = Arrays.copyOf(env, env.length);
        tamperedFlag[4] ^= 0x01;
        boolean flagRejected = false;
        try {
            ColgramBackupCodec.decryptEnvelope(tamperedFlag, key);
        } catch (GeneralSecurityException expected) {
            flagRejected = true;
        }
        assertTrue(flagRejected, "flag bit flip must be rejected by GCM AAD");

        // Flip last ciphertext/tag byte -> must reject!
        byte[] tamperedTag = Arrays.copyOf(env, env.length);
        tamperedTag[tamperedTag.length - 1] ^= 0x55;
        boolean tagRejected = false;
        try {
            ColgramBackupCodec.decryptEnvelope(tamperedTag, key);
        } catch (GeneralSecurityException expected) {
            tagRejected = true;
        }
        assertTrue(tagRejected, "ciphertext/tag bit flip must be rejected");
    }

    private static void testPathTraversalWhitelistRejected() {
        assertTrue(ColgramBackupCodec.isAllowedRelativePath("shared_prefs/userconfing.xml"), "valid pref path");
        assertTrue(ColgramBackupCodec.isAllowedRelativePath("files/account1/tgnet.dat"), "valid account dat path");
        assertTrue(ColgramBackupCodec.isAllowedRelativePath("databases/colgram_vault.db"), "valid db path");
        assertTrue(ColgramBackupCodec.isAllowedRelativePath("vault_raw/uid_1.bin"), "valid vault path");

        assertTrue(!ColgramBackupCodec.isAllowedRelativePath("../shared_prefs/userconfing.xml"), "reject ..");
        assertTrue(!ColgramBackupCodec.isAllowedRelativePath("files/../../etc/passwd"), "reject nested ..");
        assertTrue(!ColgramBackupCodec.isAllowedRelativePath("/data/data/evil"), "reject leading /");
        assertTrue(!ColgramBackupCodec.isAllowedRelativePath("files\\tgnet.dat"), "reject backslash");
        assertTrue(!ColgramBackupCodec.isAllowedRelativePath("lib/libevil.so"), "reject non-whitelisted dir");
        assertTrue(!ColgramBackupCodec.isAllowedRelativePath(""), "reject empty");
    }

    private static void assertTrue(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }
}
