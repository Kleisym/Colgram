package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenVPNChallenge implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenVPNChallenge() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenVPNChallenge)) {
            return false;
        }
        OpenVPNChallenge openVPNChallenge = (OpenVPNChallenge) obj;
        String id = getID();
        String id2 = openVPNChallenge.getID();
        if (id == null) {
            if (id2 != null) {
                return false;
            }
        } else if (!id.equals(id2)) {
            return false;
        }
        String kind = getKind();
        String kind2 = openVPNChallenge.getKind();
        if (kind == null) {
            if (kind2 != null) {
                return false;
            }
        } else if (!kind.equals(kind2)) {
            return false;
        }
        String username = getUsername();
        String username2 = openVPNChallenge.getUsername();
        if (username == null) {
            if (username2 != null) {
                return false;
            }
        } else if (!username.equals(username2)) {
            return false;
        }
        String message = getMessage();
        String message2 = openVPNChallenge.getMessage();
        if (message == null) {
            if (message2 != null) {
                return false;
            }
        } else if (!message.equals(message2)) {
            return false;
        }
        String url = getURL();
        String url2 = openVPNChallenge.getURL();
        if (url == null) {
            if (url2 != null) {
                return false;
            }
        } else if (!url.equals(url2)) {
            return false;
        }
        String secretMessage = getSecretMessage();
        String secretMessage2 = openVPNChallenge.getSecretMessage();
        if (secretMessage == null) {
            if (secretMessage2 != null) {
                return false;
            }
        } else if (!secretMessage.equals(secretMessage2)) {
            return false;
        }
        if (getEcho() != openVPNChallenge.getEcho()) {
            return false;
        }
        String previousError = getPreviousError();
        String previousError2 = openVPNChallenge.getPreviousError();
        if (previousError == null) {
            if (previousError2 != null) {
                return false;
            }
        } else if (!previousError.equals(previousError2)) {
            return false;
        }
        return getDeadline() == openVPNChallenge.getDeadline();
    }

    public final native long getDeadline();

    public final native boolean getEcho();

    public final native String getID();

    public final native String getKind();

    public final native String getMessage();

    public final native String getPreviousError();

    public final native String getSecretMessage();

    public final native String getURL();

    public final native String getUsername();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getID(), getKind(), getUsername(), getMessage(), getURL(), getSecretMessage(), Boolean.valueOf(getEcho()), getPreviousError(), Long.valueOf(getDeadline())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setDeadline(long j);

    public final native void setEcho(boolean z);

    public final native void setID(String str);

    public final native void setKind(String str);

    public final native void setMessage(String str);

    public final native void setPreviousError(String str);

    public final native void setSecretMessage(String str);

    public final native void setURL(String str);

    public final native void setUsername(String str);

    public String toString() {
        return "OpenVPNChallenge{ID:" + getID() + ",Kind:" + getKind() + ",Username:" + getUsername() + ",Message:" + getMessage() + ",URL:" + getURL() + ",SecretMessage:" + getSecretMessage() + ",Echo:" + getEcho() + ",PreviousError:" + getPreviousError() + ",Deadline:" + getDeadline() + ",}";
    }

    public OpenVPNChallenge(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
