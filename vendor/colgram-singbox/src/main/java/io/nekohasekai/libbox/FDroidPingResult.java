package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class FDroidPingResult implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public FDroidPingResult() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof FDroidPingResult)) {
            return false;
        }
        FDroidPingResult fDroidPingResult = (FDroidPingResult) obj;
        String url = getURL();
        String url2 = fDroidPingResult.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        if (getLatencyMs() != fDroidPingResult.getLatencyMs()) {
            return false;
        }
        String error = getError();
        String error2 = fDroidPingResult.getError();
        if (error == null) {
            return error2 == null;
        }
        return error.equals(error2);
    }

    public final native String getError();

    public final native int getLatencyMs();

    public final native String getURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getURL(), Integer.valueOf(getLatencyMs()), getError()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setError(String str);

    public final native void setLatencyMs(int i);

    public final native void setURL(String str);

    public String toString() {
        return "FDroidPingResult{URL:" + getURL() + ",LatencyMs:" + getLatencyMs() + ",Error:" + getError() + ",}";
    }

    public FDroidPingResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
