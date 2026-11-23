package org.colgram.core;

public final class ColgramAccountVaultPolicyTest {
    private static void check(boolean c, String m) {
        if (!c) throw new AssertionError(m);
    }

    public static void main(String[] args) throws Exception {
        check(!ColgramAccountVaultPolicy.badge(true).equals(ColgramAccountVaultPolicy.badge(false)), "badges differ");
        check(ColgramAccountVaultPolicy.label("Bob", true).startsWith("Bob"), "label prefix");
        boolean[] pinned = {false, true, false, false, false};
        long[] used = {50, 1, 10, 30, 20};
        check(ColgramAccountVaultPolicy.pickEvictableSlot(pinned, used, 2) == 4, "LRU unpinned non-active slot is 4, got "
                + ColgramAccountVaultPolicy.pickEvictableSlot(pinned, used, 2));
        boolean[] all = {true, true};
        check(ColgramAccountVaultPolicy.pickEvictableSlot(all, new long[]{1, 2}, 0) == -1, "all pinned -> -1");
        check(ColgramAccountVaultPolicy.pickEvictableSlotAllowPinnedFallback(all, new long[]{1, 2}, 0) == 1,
                "fallback picks non-active pinned slot 1");
        check(ColgramAccountVaultPolicy.pillText(true).contains("Бот"), "bot pill text");
        check(ColgramAccountVaultPolicy.pillText(false).contains("Чел"), "human pill text");
        check("uid_42".equals(ColgramAccountVaultPolicy.stableAccountKey(42L, 0)), "stableAccountKey uid");
        check(ColgramAccountVaultPolicy.nextPinOrder(new int[]{2, 5, 1}) == 6, "nextPinOrder");
        Integer[] ord = ColgramAccountVaultPolicy.order(pinned, new int[]{0, 0, 0, 0, 0}, used);
        check(ord[0] == 1, "pinned first");
        check(ord[1] == 0, "then most recently used");

        byte[] salt = ColgramCryptoVault.newSalt();
        byte[] key = ColgramCryptoVault.deriveKey("pass".toCharArray(), salt);
        check(key.length == 32, "key length");
        byte[] plain = "secret session".getBytes("UTF-8");
        byte[] aad = "uid_42".getBytes("UTF-8");
        byte[] blob = ColgramCryptoVault.encryptWithAad(key, plain, aad);
        check(!java.util.Arrays.equals(blob, plain), "ciphertext differs");
        check(java.util.Arrays.equals(ColgramCryptoVault.decryptWithAad(key, blob, aad), plain), "roundtrip AAD");
        boolean aadThrew = false;
        try { ColgramCryptoVault.decryptWithAad(key, blob, "uid_99".getBytes("UTF-8")); } catch (java.security.GeneralSecurityException e) { aadThrew = true; }
        check(aadThrew, "mismatched AAD must fail");
        byte[] bad = blob.clone();
        bad[bad.length - 1] ^= 1;
        boolean threw = false;
        try { ColgramCryptoVault.decryptWithAad(key, bad, aad); } catch (java.security.GeneralSecurityException e) { threw = true; }
        check(threw, "tamper must be detected");
        byte[] wrong = ColgramCryptoVault.deriveKey("other".toCharArray(), salt);
        threw = false;
        try { ColgramCryptoVault.decryptWithAad(wrong, blob, aad); } catch (java.security.GeneralSecurityException e) { threw = true; }
        check(threw, "wrong key must fail");
        System.out.println("ColgramAccountVaultPolicyTest: PASS");
    }
}