package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class ProfilePreview implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public ProfilePreview() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof ProfilePreview)) {
            return false;
        }
        ProfilePreview profilePreview = (ProfilePreview) obj;
        if (getProfileID() != profilePreview.getProfileID()) {
            return false;
        }
        String name = getName();
        String name2 = profilePreview.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        return getType() == profilePreview.getType();
    }

    public final native String getName();

    public final native long getProfileID();

    public final native int getType();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getProfileID()), getName(), Integer.valueOf(getType())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setName(String str);

    public final native void setProfileID(long j);

    public final native void setType(int i);

    public String toString() {
        return "ProfilePreview{ProfileID:" + getProfileID() + ",Name:" + getName() + ",Type:" + getType() + ",}";
    }

    public ProfilePreview(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
