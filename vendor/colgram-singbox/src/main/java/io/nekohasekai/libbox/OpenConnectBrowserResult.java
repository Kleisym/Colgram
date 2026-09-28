package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectBrowserResult implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectBrowserResult(String str) {
        int i__NewOpenConnectBrowserResult = __NewOpenConnectBrowserResult(str);
        this.refnum = i__NewOpenConnectBrowserResult;
        Seq.trackGoRef(i__NewOpenConnectBrowserResult, this);
    }

    private static native int __NewOpenConnectBrowserResult(String str);

    public native void addCookie(String str, String str2);

    public native void addHeader(String str, String str2);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectBrowserResult)) {
            return false;
        }
        String finalURL = getFinalURL();
        String finalURL2 = ((OpenConnectBrowserResult) obj).getFinalURL();
        if (finalURL == null) {
            return finalURL2 == null;
        }
        return finalURL.equals(finalURL2);
    }

    public final native String getFinalURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getFinalURL()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setFinalURL(String str);

    public String toString() {
        return "OpenConnectBrowserResult{FinalURL:" + getFinalURL() + ",}";
    }

    public OpenConnectBrowserResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
