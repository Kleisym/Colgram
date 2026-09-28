package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenVPNChallengeResponse implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenVPNChallengeResponse() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenVPNChallengeResponse)) {
            return false;
        }
        OpenVPNChallengeResponse openVPNChallengeResponse = (OpenVPNChallengeResponse) obj;
        String username = getUsername();
        String username2 = openVPNChallengeResponse.getUsername();
        if (username == null) {
            if (username2 != null) {
                return false;
            }
        } else if (!username.equals(username2)) {
            return false;
        }
        String password = getPassword();
        String password2 = openVPNChallengeResponse.getPassword();
        if (password == null) {
            if (password2 != null) {
                return false;
            }
        } else if (!password.equals(password2)) {
            return false;
        }
        String secret = getSecret();
        String secret2 = openVPNChallengeResponse.getSecret();
        if (secret == null) {
            return secret2 == null;
        }
        return secret.equals(secret2);
    }

    public final native String getPassword();

    public final native String getSecret();

    public final native String getUsername();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getUsername(), getPassword(), getSecret()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setPassword(String str);

    public final native void setSecret(String str);

    public final native void setUsername(String str);

    public String toString() {
        return "OpenVPNChallengeResponse{Username:" + getUsername() + ",Password:" + getPassword() + ",Secret:" + getSecret() + ",}";
    }

    public OpenVPNChallengeResponse(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
