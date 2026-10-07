package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ImportRemoteProfile implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ImportRemoteProfile() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ImportRemoteProfile)) {
            return false;
        }
        ImportRemoteProfile importRemoteProfile = (ImportRemoteProfile) obj;
        String name = getName();
        String name2 = importRemoteProfile.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        String url = getURL();
        String url2 = importRemoteProfile.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        String host = getHost();
        String host2 = importRemoteProfile.getHost();
        if (host == null) {
            return host2 == null;
        }
        return host.equals(host2);
    }

    public final native String getHost();

    public final native String getName();

    public final native String getURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getName(), getURL(), getHost()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setHost(String str);

    public final native void setName(String str);

    public final native void setURL(String str);

    public String toString() {
        return "ImportRemoteProfile{Name:" + getName() + ",URL:" + getURL() + ",Host:" + getHost() + ",}";
    }

    public ImportRemoteProfile(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
