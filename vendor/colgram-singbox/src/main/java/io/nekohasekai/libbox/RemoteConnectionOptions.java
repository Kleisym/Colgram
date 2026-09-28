package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class RemoteConnectionOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public RemoteConnectionOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof RemoteConnectionOptions)) {
            return false;
        }
        RemoteConnectionOptions remoteConnectionOptions = (RemoteConnectionOptions) obj;
        String url = getURL();
        String url2 = remoteConnectionOptions.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        String secret = getSecret();
        String secret2 = remoteConnectionOptions.getSecret();
        if (secret == null) {
            return secret2 == null;
        }
        return secret.equals(secret2);
    }

    public final native String getSecret();

    public final native String getURL();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getURL(), getSecret()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setSecret(String str);

    public final native void setURL(String str);

    public String toString() {
        return "RemoteConnectionOptions{URL:" + getURL() + ",Secret:" + getSecret() + ",}";
    }

    public RemoteConnectionOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
