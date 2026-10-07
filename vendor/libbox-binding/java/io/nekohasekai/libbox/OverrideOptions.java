package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OverrideOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OverrideOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OverrideOptions)) {
            return false;
        }
        OverrideOptions overrideOptions = (OverrideOptions) obj;
        if (getAutoRedirect() != overrideOptions.getAutoRedirect()) {
            return false;
        }
        StringIterator includePackage = getIncludePackage();
        StringIterator includePackage2 = overrideOptions.getIncludePackage();
        if (includePackage == null) {
            if (includePackage2 != null) {
                return false;
            }
        } else if (!includePackage.equals(includePackage2)) {
            return false;
        }
        StringIterator excludePackage = getExcludePackage();
        StringIterator excludePackage2 = overrideOptions.getExcludePackage();
        if (excludePackage == null) {
            return excludePackage2 == null;
        }
        return excludePackage.equals(excludePackage2);
    }

    public final native boolean getAutoRedirect();

    public final native StringIterator getExcludePackage();

    public final native StringIterator getIncludePackage();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Boolean.valueOf(getAutoRedirect()), getIncludePackage(), getExcludePackage()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAutoRedirect(boolean z);

    public final native void setExcludePackage(StringIterator stringIterator);

    public final native void setIncludePackage(StringIterator stringIterator);

    public String toString() {
        return "OverrideOptions{AutoRedirect:" + getAutoRedirect() + ",IncludePackage:" + getIncludePackage() + ",ExcludePackage:" + getExcludePackage() + ",}";
    }

    public OverrideOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
