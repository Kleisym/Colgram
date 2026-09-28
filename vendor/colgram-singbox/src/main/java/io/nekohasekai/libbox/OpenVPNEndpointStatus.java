package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenVPNEndpointStatus implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenVPNEndpointStatus() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenVPNEndpointStatus)) {
            return false;
        }
        OpenVPNEndpointStatus openVPNEndpointStatus = (OpenVPNEndpointStatus) obj;
        String endpointTag = getEndpointTag();
        String endpointTag2 = openVPNEndpointStatus.getEndpointTag();
        if (endpointTag == null) {
            if (endpointTag2 != null) {
                return false;
            }
        } else if (!endpointTag.equals(endpointTag2)) {
            return false;
        }
        String state = getState();
        String state2 = openVPNEndpointStatus.getState();
        if (state == null) {
            if (state2 != null) {
                return false;
            }
        } else if (!state.equals(state2)) {
            return false;
        }
        String stateText = getStateText();
        String stateText2 = openVPNEndpointStatus.getStateText();
        if (stateText == null) {
            if (stateText2 != null) {
                return false;
            }
        } else if (!stateText.equals(stateText2)) {
            return false;
        }
        OpenVPNChallenge challenge = getChallenge();
        OpenVPNChallenge challenge2 = openVPNEndpointStatus.getChallenge();
        if (challenge == null) {
            if (challenge2 != null) {
                return false;
            }
        } else if (!challenge.equals(challenge2)) {
            return false;
        }
        String error = getError();
        String error2 = openVPNEndpointStatus.getError();
        if (error == null) {
            if (error2 != null) {
                return false;
            }
        } else if (!error.equals(error2)) {
            return false;
        }
        OpenVPNTunnelInfo tunnelInfo = getTunnelInfo();
        OpenVPNTunnelInfo tunnelInfo2 = openVPNEndpointStatus.getTunnelInfo();
        if (tunnelInfo == null) {
            return tunnelInfo2 == null;
        }
        return tunnelInfo.equals(tunnelInfo2);
    }

    public final native OpenVPNChallenge getChallenge();

    public final native String getEndpointTag();

    public final native String getError();

    public final native String getState();

    public final native String getStateText();

    public final native OpenVPNTunnelInfo getTunnelInfo();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getEndpointTag(), getState(), getStateText(), getChallenge(), getError(), getTunnelInfo()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setChallenge(OpenVPNChallenge openVPNChallenge);

    public final native void setEndpointTag(String str);

    public final native void setError(String str);

    public final native void setState(String str);

    public final native void setStateText(String str);

    public final native void setTunnelInfo(OpenVPNTunnelInfo openVPNTunnelInfo);

    public String toString() {
        return "OpenVPNEndpointStatus{EndpointTag:" + getEndpointTag() + ",State:" + getState() + ",StateText:" + getStateText() + ",Challenge:" + getChallenge() + ",Error:" + getError() + ",TunnelInfo:" + getTunnelInfo() + ",}";
    }

    public OpenVPNEndpointStatus(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
