package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ProfileContent implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ProfileContent() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native byte[] encode();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ProfileContent)) {
            return false;
        }
        ProfileContent profileContent = (ProfileContent) obj;
        String name = getName();
        String name2 = profileContent.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        if (getType() != profileContent.getType()) {
            return false;
        }
        String config = getConfig();
        String config2 = profileContent.getConfig();
        if (config == null) {
            if (config2 != null) {
                return false;
            }
        } else if (!config.equals(config2)) {
            return false;
        }
        String remotePath = getRemotePath();
        String remotePath2 = profileContent.getRemotePath();
        if (remotePath == null) {
            if (remotePath2 != null) {
                return false;
            }
        } else if (!remotePath.equals(remotePath2)) {
            return false;
        }
        return getAutoUpdate() == profileContent.getAutoUpdate() && getAutoUpdateInterval() == profileContent.getAutoUpdateInterval() && getLastUpdated() == profileContent.getLastUpdated();
    }

    public final native boolean getAutoUpdate();

    public final native int getAutoUpdateInterval();

    public final native String getConfig();

    public final native long getLastUpdated();

    public final native String getName();

    public final native String getRemotePath();

    public final native int getType();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getName(), Integer.valueOf(getType()), getConfig(), getRemotePath(), Boolean.valueOf(getAutoUpdate()), Integer.valueOf(getAutoUpdateInterval()), Long.valueOf(getLastUpdated())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAutoUpdate(boolean z);

    public final native void setAutoUpdateInterval(int i);

    public final native void setConfig(String str);

    public final native void setLastUpdated(long j);

    public final native void setName(String str);

    public final native void setRemotePath(String str);

    public final native void setType(int i);

    public String toString() {
        return "ProfileContent{Name:" + getName() + ",Type:" + getType() + ",Config:" + getConfig() + ",RemotePath:" + getRemotePath() + ",AutoUpdate:" + getAutoUpdate() + ",AutoUpdateInterval:" + getAutoUpdateInterval() + ",LastUpdated:" + getLastUpdated() + ",}";
    }

    public ProfileContent(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
