package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectEndpointStatus implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectEndpointStatus() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectEndpointStatus)) {
            return false;
        }
        OpenConnectEndpointStatus openConnectEndpointStatus = (OpenConnectEndpointStatus) obj;
        String endpointTag = getEndpointTag();
        String endpointTag2 = openConnectEndpointStatus.getEndpointTag();
        if (endpointTag == null) {
            if (endpointTag2 != null) {
                return false;
            }
        } else if (!endpointTag.equals(endpointTag2)) {
            return false;
        }
        String state = getState();
        String state2 = openConnectEndpointStatus.getState();
        if (state == null) {
            if (state2 != null) {
                return false;
            }
        } else if (!state.equals(state2)) {
            return false;
        }
        String stateText = getStateText();
        String stateText2 = openConnectEndpointStatus.getStateText();
        if (stateText == null) {
            if (stateText2 != null) {
                return false;
            }
        } else if (!stateText.equals(stateText2)) {
            return false;
        }
        OpenConnectAuthChallenge authChallenge = getAuthChallenge();
        OpenConnectAuthChallenge authChallenge2 = openConnectEndpointStatus.getAuthChallenge();
        if (authChallenge == null) {
            if (authChallenge2 != null) {
                return false;
            }
        } else if (!authChallenge.equals(authChallenge2)) {
            return false;
        }
        String error = getError();
        String error2 = openConnectEndpointStatus.getError();
        if (error == null) {
            if (error2 != null) {
                return false;
            }
        } else if (!error.equals(error2)) {
            return false;
        }
        OpenConnectTunnelInfo tunnelInfo = getTunnelInfo();
        OpenConnectTunnelInfo tunnelInfo2 = openConnectEndpointStatus.getTunnelInfo();
        if (tunnelInfo == null) {
            return tunnelInfo2 == null;
        }
        return tunnelInfo.equals(tunnelInfo2);
    }

    public final native OpenConnectAuthChallenge getAuthChallenge();

    public final native String getEndpointTag();

    public final native String getError();

    public final native String getState();

    public final native String getStateText();

    public final native OpenConnectTunnelInfo getTunnelInfo();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getEndpointTag(), getState(), getStateText(), getAuthChallenge(), getError(), getTunnelInfo()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAuthChallenge(OpenConnectAuthChallenge openConnectAuthChallenge);

    public final native void setEndpointTag(String str);

    public final native void setError(String str);

    public final native void setState(String str);

    public final native void setStateText(String str);

    public final native void setTunnelInfo(OpenConnectTunnelInfo openConnectTunnelInfo);

    public String toString() {
        return "OpenConnectEndpointStatus{EndpointTag:" + getEndpointTag() + ",State:" + getState() + ",StateText:" + getStateText() + ",AuthChallenge:" + getAuthChallenge() + ",Error:" + getError() + ",TunnelInfo:" + getTunnelInfo() + ",}";
    }

    public OpenConnectEndpointStatus(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
