package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class PlatformUser implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public PlatformUser() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof PlatformUser)) {
            return false;
        }
        PlatformUser platformUser = (PlatformUser) obj;
        String username = getUsername();
        String username2 = platformUser.getUsername();
        if (username == null) {
            if (username2 != null) {
                return false;
            }
        } else if (!username.equals(username2)) {
            return false;
        }
        if (getUid() != platformUser.getUid() || getGid() != platformUser.getGid()) {
            return false;
        }
        String homeDir = getHomeDir();
        String homeDir2 = platformUser.getHomeDir();
        if (homeDir == null) {
            if (homeDir2 != null) {
                return false;
            }
        } else if (!homeDir.equals(homeDir2)) {
            return false;
        }
        String shell = getShell();
        String shell2 = platformUser.getShell();
        if (shell == null) {
            return shell2 == null;
        }
        return shell.equals(shell2);
    }

    public final native int getGid();

    public final native String getHomeDir();

    public final native String getShell();

    public final native int getUid();

    public final native String getUsername();

    public native Int32Iterator groups();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getUsername(), Integer.valueOf(getUid()), Integer.valueOf(getGid()), getHomeDir(), getShell()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setGid(int i);

    public native void setGroups(Int32Iterator int32Iterator);

    public final native void setHomeDir(String str);

    public final native void setShell(String str);

    public final native void setUid(int i);

    public final native void setUsername(String str);

    public String toString() {
        return "PlatformUser{Username:" + getUsername() + ",Uid:" + getUid() + ",Gid:" + getGid() + ",HomeDir:" + getHomeDir() + ",Shell:" + getShell() + ",}";
    }

    public PlatformUser(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
