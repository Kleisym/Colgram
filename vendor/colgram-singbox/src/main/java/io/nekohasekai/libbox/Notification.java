package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class Notification implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public Notification() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Notification)) {
            return false;
        }
        Notification notification = (Notification) obj;
        String identifier = getIdentifier();
        String identifier2 = notification.getIdentifier();
        if (identifier == null) {
            if (identifier2 != null) {
                return false;
            }
        } else if (!identifier.equals(identifier2)) {
            return false;
        }
        String typeName = getTypeName();
        String typeName2 = notification.getTypeName();
        if (typeName == null) {
            if (typeName2 != null) {
                return false;
            }
        } else if (!typeName.equals(typeName2)) {
            return false;
        }
        if (getTypeID() != notification.getTypeID()) {
            return false;
        }
        String title = getTitle();
        String title2 = notification.getTitle();
        if (title == null) {
            if (title2 != null) {
                return false;
            }
        } else if (!title.equals(title2)) {
            return false;
        }
        String subtitle = getSubtitle();
        String subtitle2 = notification.getSubtitle();
        if (subtitle == null) {
            if (subtitle2 != null) {
                return false;
            }
        } else if (!subtitle.equals(subtitle2)) {
            return false;
        }
        String body = getBody();
        String body2 = notification.getBody();
        if (body == null) {
            if (body2 != null) {
                return false;
            }
        } else if (!body.equals(body2)) {
            return false;
        }
        String openURL = getOpenURL();
        String openURL2 = notification.getOpenURL();
        if (openURL == null) {
            return openURL2 == null;
        }
        return openURL.equals(openURL2);
    }

    public final native String getBody();

    public final native String getIdentifier();

    public final native String getOpenURL();

    public final native String getSubtitle();

    public final native String getTitle();

    public final native int getTypeID();

    public final native String getTypeName();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getIdentifier(), getTypeName(), Integer.valueOf(getTypeID()), getTitle(), getSubtitle(), getBody(), getOpenURL()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setBody(String str);

    public final native void setIdentifier(String str);

    public final native void setOpenURL(String str);

    public final native void setSubtitle(String str);

    public final native void setTitle(String str);

    public final native void setTypeID(int i);

    public final native void setTypeName(String str);

    public String toString() {
        return "Notification{Identifier:" + getIdentifier() + ",TypeName:" + getTypeName() + ",TypeID:" + getTypeID() + ",Title:" + getTitle() + ",Subtitle:" + getSubtitle() + ",Body:" + getBody() + ",OpenURL:" + getOpenURL() + ",}";
    }

    public Notification(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
