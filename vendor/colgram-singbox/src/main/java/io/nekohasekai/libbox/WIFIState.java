package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class WIFIState implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public WIFIState(String str, String str2) {
        int i__NewWIFIState = __NewWIFIState(str, str2);
        this.refnum = i__NewWIFIState;
        Seq.trackGoRef(i__NewWIFIState, this);
    }

    private static native int __NewWIFIState(String str, String str2);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof WIFIState)) {
            return false;
        }
        WIFIState wIFIState = (WIFIState) obj;
        String ssid = getSSID();
        String ssid2 = wIFIState.getSSID();
        if (ssid == null) {
            if (ssid2 != null) {
                return false;
            }
        } else if (!ssid.equals(ssid2)) {
            return false;
        }
        String bssid = getBSSID();
        String bssid2 = wIFIState.getBSSID();
        if (bssid == null) {
            return bssid2 == null;
        }
        return bssid.equals(bssid2);
    }

    public final native String getBSSID();

    public final native String getSSID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getSSID(), getBSSID()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setBSSID(String str);

    public final native void setSSID(String str);

    public String toString() {
        return "WIFIState{SSID:" + getSSID() + ",BSSID:" + getBSSID() + ",}";
    }

    public WIFIState(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
