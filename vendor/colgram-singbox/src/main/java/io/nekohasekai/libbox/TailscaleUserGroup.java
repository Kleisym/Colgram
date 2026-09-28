package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscaleUserGroup implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscaleUserGroup() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscaleUserGroup)) {
            return false;
        }
        TailscaleUserGroup tailscaleUserGroup = (TailscaleUserGroup) obj;
        if (getUserID() != tailscaleUserGroup.getUserID()) {
            return false;
        }
        String loginName = getLoginName();
        String loginName2 = tailscaleUserGroup.getLoginName();
        if (loginName == null) {
            if (loginName2 != null) {
                return false;
            }
        } else if (!loginName.equals(loginName2)) {
            return false;
        }
        String displayName = getDisplayName();
        String displayName2 = tailscaleUserGroup.getDisplayName();
        if (displayName == null) {
            if (displayName2 != null) {
                return false;
            }
        } else if (!displayName.equals(displayName2)) {
            return false;
        }
        String profilePicURL = getProfilePicURL();
        String profilePicURL2 = tailscaleUserGroup.getProfilePicURL();
        if (profilePicURL == null) {
            return profilePicURL2 == null;
        }
        return profilePicURL.equals(profilePicURL2);
    }

    public final native String getDisplayName();

    public final native String getLoginName();

    public final native String getProfilePicURL();

    public final native long getUserID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getUserID()), getLoginName(), getDisplayName(), getProfilePicURL()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native TailscalePeerIterator peers();

    public final native void setDisplayName(String str);

    public final native void setLoginName(String str);

    public final native void setProfilePicURL(String str);

    public final native void setUserID(long j);

    public String toString() {
        return "TailscaleUserGroup{UserID:" + getUserID() + ",LoginName:" + getLoginName() + ",DisplayName:" + getDisplayName() + ",ProfilePicURL:" + getProfilePicURL() + ",}";
    }

    public TailscaleUserGroup(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
