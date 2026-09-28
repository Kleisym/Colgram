package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectBrowserRequest implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectBrowserRequest() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native StringIterator callbackURLPrefixes();

    public native StringIterator cookieNames();

    public native StringIterator earlyCookieNames();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectBrowserRequest)) {
            return false;
        }
        OpenConnectBrowserRequest openConnectBrowserRequest = (OpenConnectBrowserRequest) obj;
        String url = getURL();
        String url2 = openConnectBrowserRequest.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        String finalURL = getFinalURL();
        String finalURL2 = openConnectBrowserRequest.getFinalURL();
        if (finalURL == null) {
            if (finalURL2 != null) {
                return false;
            }
        } else if (!finalURL.equals(finalURL2)) {
            return false;
        }
        String cacheID = getCacheID();
        String cacheID2 = openConnectBrowserRequest.getCacheID();
        if (cacheID == null) {
            return cacheID2 == null;
        }
        return cacheID.equals(cacheID2);
    }

    public final native String getCacheID();

    public final native String getFinalURL();

    public final native String getURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getURL(), getFinalURL(), getCacheID()});
    }

    public native StringIterator headerNames();

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setCacheID(String str);

    public final native void setFinalURL(String str);

    public final native void setURL(String str);

    public String toString() {
        return "OpenConnectBrowserRequest{URL:" + getURL() + ",FinalURL:" + getFinalURL() + ",CacheID:" + getCacheID() + ",}";
    }

    public OpenConnectBrowserRequest(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
