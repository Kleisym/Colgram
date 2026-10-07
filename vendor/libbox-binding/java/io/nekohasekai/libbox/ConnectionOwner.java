package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ConnectionOwner implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ConnectionOwner() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native StringIterator androidPackageNames();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ConnectionOwner)) {
            return false;
        }
        ConnectionOwner connectionOwner = (ConnectionOwner) obj;
        if (getUserId() != connectionOwner.getUserId()) {
            return false;
        }
        String userName = getUserName();
        String userName2 = connectionOwner.getUserName();
        if (userName == null) {
            if (userName2 != null) {
                return false;
            }
        } else if (!userName.equals(userName2)) {
            return false;
        }
        String processPath = getProcessPath();
        String processPath2 = connectionOwner.getProcessPath();
        if (processPath == null) {
            return processPath2 == null;
        }
        return processPath.equals(processPath2);
    }

    public final native String getProcessPath();

    public final native int getUserId();

    public final native String getUserName();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getUserId()), getUserName(), getProcessPath()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native void setAndroidPackageNames(StringIterator stringIterator);

    public final native void setProcessPath(String str);

    public final native void setUserId(int i);

    public final native void setUserName(String str);

    public String toString() {
        return "ConnectionOwner{UserId:" + getUserId() + ",UserName:" + getUserName() + ",ProcessPath:" + getProcessPath() + ",}";
    }

    public ConnectionOwner(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
