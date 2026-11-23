package org.colgram.core;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM helper. Pure javax.crypto: format = magic(1) | iv(12) | ciphertext+tag.
 * Keys come either from a raw 32-byte key (e.g. AndroidKeyStore-wrapped) or PBKDF2-HMAC-SHA256.
 */
public final class ColgramCryptoVault {
    private ColgramCryptoVault() {}

    private static final byte MAGIC = 0x43;
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] newSalt() {
        byte[] s = new byte[16];
        RANDOM.nextBytes(s);
        return s;
    }

    public static byte[] deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public static byte[] encrypt(byte[] key, byte[] plain) throws GeneralSecurityException {
        return encryptWithAad(key, plain, null);
    }

    public static byte[] encryptWithAad(byte[] key, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey k = new SecretKeySpec(key, "AES");
        c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null && aad.length > 0) {
            c.updateAAD(aad);
        }
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[1 + IV_LEN + ct.length];
        out[0] = MAGIC;
        System.arraycopy(iv, 0, out, 1, IV_LEN);
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.length);
        return out;
    }

    public static byte[] decrypt(byte[] key, byte[] blob) throws GeneralSecurityException {
        return decryptWithAad(key, blob, null);
    }

    public static byte[] decryptWithAad(byte[] key, byte[] blob, byte[] aad) throws GeneralSecurityException {
        if (blob == null || blob.length < 1 + IV_LEN + 16 || blob[0] != MAGIC) {
            throw new GeneralSecurityException("bad vault blob");
        }
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey k = new SecretKeySpec(key, "AES");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN));
        if (aad != null && aad.length > 0) {
            c.updateAAD(aad);
        }
        return c.doFinal(blob, 1 + IV_LEN, blob.length - 1 - IV_LEN);
    }

    public static void zeroize(byte[] data) {
        if (data != null) {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }
}
