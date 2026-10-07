package io.nekohasekai.libbox;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public interface TailscaleSSHHandler {
    void onAuthBanner(String str);

    void onError(String str);

    void onExit(int i, String str, String str2);

    void onOutput(byte[] bArr);

    void onReady();
}
