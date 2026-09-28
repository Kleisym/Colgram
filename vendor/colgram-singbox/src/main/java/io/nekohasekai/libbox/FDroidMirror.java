package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class FDroidMirror implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public FDroidMirror() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof FDroidMirror)) {
            return false;
        }
        FDroidMirror fDroidMirror = (FDroidMirror) obj;
        String url = getURL();
        String url2 = fDroidMirror.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        String country = getCountry();
        String country2 = fDroidMirror.getCountry();
        if (country == null) {
            if (country2 != null) {
                return false;
            }
        } else if (!country.equals(country2)) {
            return false;
        }
        String name = getName();
        String name2 = fDroidMirror.getName();
        if (name == null) {
            return name2 == null;
        }
        return name.equals(name2);
    }

    public final native String getCountry();

    public final native String getName();

    public final native String getURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getURL(), getCountry(), getName()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setCountry(String str);

    public final native void setName(String str);

    public final native void setURL(String str);

    public String toString() {
        return "FDroidMirror{URL:" + getURL() + ",Country:" + getCountry() + ",Name:" + getName() + ",}";
    }

    public FDroidMirror(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
