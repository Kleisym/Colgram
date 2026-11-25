package org.colgram.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure-Java snapshot codec and AES-256-GCM encrypted envelope for {@link ColgramPersistentBackup}.
 * Intentionally free of any Android framework imports so it runs natively in host JVM tests.
 */
public final class ColgramBackupCodec {

    static final byte[] ENVELOPE_MAGIC = new byte[]{'C', 'G', 'B', '1'};
    static final byte FLAG_DEVICE_KEY = 0x00;
    static final byte FLAG_USER_PASSPHRASE = 0x01;
    static final int SALT_LEN = 16;
    static final int HEADER_LEN = ENVELOPE_MAGIC.length + 1 + SALT_LEN;

    static final int SNAPSHOT_MAGIC = 0x43474256; // "CGBV"
    static final int SNAPSHOT_VERSION = 1;
    static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    static final int MAX_TOTAL_BYTES = 96 * 1024 * 1024;

    private ColgramBackupCodec() {}

    public static boolean isAllowedRelativePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 256) return false;
        if (path.startsWith("/") || path.startsWith("\\")) return false;
        if (path.indexOf('\0') >= 0 || path.contains("..") || path.contains("\\")) return false;
        return path.startsWith("shared_prefs/")
                || path.startsWith("files/")
                || path.startsWith("databases/")
                || path.startsWith("vault_raw/");
    }

    public static byte[] encodeSnapshot(Map<String, byte[]> entries, long createdAtMs) throws Exception {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Snapshot entries empty");
        }
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream(16384);
        try (GZIPOutputStream gz = new GZIPOutputStream(rawOut);
             DataOutputStream dos = new DataOutputStream(gz)) {
            dos.writeInt(SNAPSHOT_MAGIC);
            dos.writeInt(SNAPSHOT_VERSION);
            dos.writeLong(createdAtMs);
            int validCount = 0;
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (isAllowedRelativePath(e.getKey()) && e.getValue() != null) validCount++;
            }
            dos.writeInt(validCount);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (!isAllowedRelativePath(e.getKey()) || e.getValue() == null) continue;
                byte[] val = e.getValue();
                if (val.length > MAX_ENTRY_BYTES) {
                    throw new IllegalArgumentException("Entry too large: " + e.getKey());
                }
                dos.writeUTF(e.getKey());
                dos.writeInt(val.length);
                if (val.length > 0) dos.write(val);
            }
            dos.flush();
        }
        return rawOut.toByteArray();
    }

    public static Map<String, byte[]> decodeSnapshot(byte[] gzipped) throws Exception {
        if (gzipped == null || gzipped.length < 4) {
            throw new IllegalArgumentException("Empty snapshot");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        long totalBytes = 0L;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(gzipped));
             DataInputStream dis = new DataInputStream(gz)) {
            int magic = dis.readInt();
            if (magic != SNAPSHOT_MAGIC) {
                throw new IllegalStateException("Invalid snapshot magic");
            }
            int version = dis.readInt();
            if (version != SNAPSHOT_VERSION) {
                throw new IllegalStateException("Unsupported snapshot version: " + version);
            }
            dis.readLong(); // createdAtMs
            int count = dis.readInt();
            if (count < 0 || count > 2048) {
                throw new IllegalStateException("Invalid entry count: " + count);
            }
            for (int i = 0; i < count; i++) {
                String path = dis.readUTF();
                int len = dis.readInt();
                if (!isAllowedRelativePath(path) || len < 0 || len > MAX_ENTRY_BYTES) {
                    throw new SecurityException("Rejected unsafe snapshot entry: " + path);
                }
                totalBytes += len;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new SecurityException("Snapshot exceeded size limit");
                }
                byte[] buf = new byte[len];
                if (len > 0) dis.readFully(buf);
                out.put(path, buf);
            }
        }
        return out;
    }

    public static boolean isPassphraseProtected(byte[] envelope) {
        if (envelope == null || envelope.length < HEADER_LEN) return false;
        for (int i = 0; i < ENVELOPE_MAGIC.length; i++) {
            if (envelope[i] != ENVELOPE_MAGIC[i]) return false;
        }
        return envelope[ENVELOPE_MAGIC.length] == FLAG_USER_PASSPHRASE;
    }

    public static byte[] encryptEnvelope(byte[] snapshotPlain, char[] keyMaterial, boolean userPassphrase)
            throws GeneralSecurityException {
        if (snapshotPlain == null || snapshotPlain.length == 0) {
            throw new GeneralSecurityException("Empty payload");
        }
        if (keyMaterial == null || keyMaterial.length == 0) {
            throw new GeneralSecurityException("Empty key material");
        }
        byte[] salt = ColgramCryptoVault.newSalt();
        byte[] header = new byte[HEADER_LEN];
        System.arraycopy(ENVELOPE_MAGIC, 0, header, 0, ENVELOPE_MAGIC.length);
        header[ENVELOPE_MAGIC.length] = userPassphrase ? FLAG_USER_PASSPHRASE : FLAG_DEVICE_KEY;
        System.arraycopy(salt, 0, header, ENVELOPE_MAGIC.length + 1, SALT_LEN);

        byte[] key = ColgramCryptoVault.deriveKey(keyMaterial, salt);
        try {
            byte[] cipherBlob = ColgramCryptoVault.encryptWithAad(key, snapshotPlain, header);
            byte[] out = new byte[HEADER_LEN + cipherBlob.length];
            System.arraycopy(header, 0, out, 0, HEADER_LEN);
            System.arraycopy(cipherBlob, 0, out, HEADER_LEN, cipherBlob.length);
            return out;
        } finally {
            ColgramCryptoVault.zeroize(key);
        }
    }

    public static byte[] decryptEnvelope(byte[] envelope, char[] keyMaterial)
            throws GeneralSecurityException {
        if (envelope == null || envelope.length <= HEADER_LEN + 29) {
            throw new GeneralSecurityException("Truncated backup file");
        }
        for (int i = 0; i < ENVELOPE_MAGIC.length; i++) {
            if (envelope[i] != ENVELOPE_MAGIC[i]) {
                throw new GeneralSecurityException("Invalid Colgram backup header");
            }
        }
        if (keyMaterial == null || keyMaterial.length == 0) {
            throw new GeneralSecurityException("Missing decryption key");
        }
        byte[] header = Arrays.copyOfRange(envelope, 0, HEADER_LEN);
        byte[] salt = Arrays.copyOfRange(envelope, ENVELOPE_MAGIC.length + 1, HEADER_LEN);
        byte[] cipherBlob = Arrays.copyOfRange(envelope, HEADER_LEN, envelope.length);
        byte[] key = ColgramCryptoVault.deriveKey(keyMaterial, salt);
        try {
            return ColgramCryptoVault.decryptWithAad(key, cipherBlob, header);
        } finally {
            ColgramCryptoVault.zeroize(key);
        }
    }
}
