package io.nekohasekai.libbox;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public interface HTTPRequest {
    HTTPResponse execute();

    void randomUserAgent();

    void setContent(byte[] bArr);

    void setContentString(String str);

    void setHeader(String str, String str2);

    void setMethod(String str);

    void setURL(String str);

    void setUserAgent(String str);
}
