package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class AndroidVPNType implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public AndroidVPNType() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof AndroidVPNType)) {
            return false;
        }
        AndroidVPNType androidVPNType = (AndroidVPNType) obj;
        String coreType = getCoreType();
        String coreType2 = androidVPNType.getCoreType();
        if (coreType == null) {
            if (coreType2 != null) {
                return false;
            }
        } else if (!coreType.equals(coreType2)) {
            return false;
        }
        String corePath = getCorePath();
        String corePath2 = androidVPNType.getCorePath();
        if (corePath == null) {
            if (corePath2 != null) {
                return false;
            }
        } else if (!corePath.equals(corePath2)) {
            return false;
        }
        String goVersion = getGoVersion();
        String goVersion2 = androidVPNType.getGoVersion();
        if (goVersion == null) {
            return goVersion2 == null;
        }
        return goVersion.equals(goVersion2);
    }

    public final native String getCorePath();

    public final native String getCoreType();

    public final native String getGoVersion();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getCoreType(), getCorePath(), getGoVersion()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setCorePath(String str);

    public final native void setCoreType(String str);

    public final native void setGoVersion(String str);

    public String toString() {
        return "AndroidVPNType{CoreType:" + getCoreType() + ",CorePath:" + getCorePath() + ",GoVersion:" + getGoVersion() + ",}";
    }

    public AndroidVPNType(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
