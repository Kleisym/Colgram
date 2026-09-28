package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscaleEndpointStatus implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscaleEndpointStatus() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscaleEndpointStatus)) {
            return false;
        }
        TailscaleEndpointStatus tailscaleEndpointStatus = (TailscaleEndpointStatus) obj;
        String endpointTag = getEndpointTag();
        String endpointTag2 = tailscaleEndpointStatus.getEndpointTag();
        if (endpointTag == null) {
            if (endpointTag2 != null) {
                return false;
            }
        } else if (!endpointTag.equals(endpointTag2)) {
            return false;
        }
        String backendState = getBackendState();
        String backendState2 = tailscaleEndpointStatus.getBackendState();
        if (backendState == null) {
            if (backendState2 != null) {
                return false;
            }
        } else if (!backendState.equals(backendState2)) {
            return false;
        }
        String stateText = getStateText();
        String stateText2 = tailscaleEndpointStatus.getStateText();
        if (stateText == null) {
            if (stateText2 != null) {
                return false;
            }
        } else if (!stateText.equals(stateText2)) {
            return false;
        }
        String authURL = getAuthURL();
        String authURL2 = tailscaleEndpointStatus.getAuthURL();
        if (authURL == null) {
            if (authURL2 != null) {
                return false;
            }
        } else if (!authURL.equals(authURL2)) {
            return false;
        }
        String networkName = getNetworkName();
        String networkName2 = tailscaleEndpointStatus.getNetworkName();
        if (networkName == null) {
            if (networkName2 != null) {
                return false;
            }
        } else if (!networkName.equals(networkName2)) {
            return false;
        }
        String magicDNSSuffix = getMagicDNSSuffix();
        String magicDNSSuffix2 = tailscaleEndpointStatus.getMagicDNSSuffix();
        if (magicDNSSuffix == null) {
            if (magicDNSSuffix2 != null) {
                return false;
            }
        } else if (!magicDNSSuffix.equals(magicDNSSuffix2)) {
            return false;
        }
        TailscalePeer self = getSelf();
        TailscalePeer self2 = tailscaleEndpointStatus.getSelf();
        if (self == null) {
            if (self2 != null) {
                return false;
            }
        } else if (!self.equals(self2)) {
            return false;
        }
        TailscalePeer exitNode = getExitNode();
        TailscalePeer exitNode2 = tailscaleEndpointStatus.getExitNode();
        if (exitNode == null) {
            if (exitNode2 != null) {
                return false;
            }
        } else if (!exitNode.equals(exitNode2)) {
            return false;
        }
        return getKeyAuth() == tailscaleEndpointStatus.getKeyAuth() && getCanShareFiles() == tailscaleEndpointStatus.getCanShareFiles() && getWaitingFileCount() == tailscaleEndpointStatus.getWaitingFileCount() && getReceivingFileCount() == tailscaleEndpointStatus.getReceivingFileCount() && getUnreadFileCount() == tailscaleEndpointStatus.getUnreadFileCount();
    }

    public final native String getAuthURL();

    public final native String getBackendState();

    public final native boolean getCanShareFiles();

    public final native String getEndpointTag();

    public final native TailscalePeer getExitNode();

    public final native boolean getKeyAuth();

    public final native String getMagicDNSSuffix();

    public final native String getNetworkName();

    public final native int getReceivingFileCount();

    public final native TailscalePeer getSelf();

    public final native String getStateText();

    public final native int getUnreadFileCount();

    public final native int getWaitingFileCount();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getEndpointTag(), getBackendState(), getStateText(), getAuthURL(), getNetworkName(), getMagicDNSSuffix(), getSelf(), getExitNode(), Boolean.valueOf(getKeyAuth()), Boolean.valueOf(getCanShareFiles()), Integer.valueOf(getWaitingFileCount()), Integer.valueOf(getReceivingFileCount()), Integer.valueOf(getUnreadFileCount())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAuthURL(String str);

    public final native void setBackendState(String str);

    public final native void setCanShareFiles(boolean z);

    public final native void setEndpointTag(String str);

    public final native void setExitNode(TailscalePeer tailscalePeer);

    public final native void setKeyAuth(boolean z);

    public final native void setMagicDNSSuffix(String str);

    public final native void setNetworkName(String str);

    public final native void setReceivingFileCount(int i);

    public final native void setSelf(TailscalePeer tailscalePeer);

    public final native void setStateText(String str);

    public final native void setUnreadFileCount(int i);

    public final native void setWaitingFileCount(int i);

    public String toString() {
        return "TailscaleEndpointStatus{EndpointTag:" + getEndpointTag() + ",BackendState:" + getBackendState() + ",StateText:" + getStateText() + ",AuthURL:" + getAuthURL() + ",NetworkName:" + getNetworkName() + ",MagicDNSSuffix:" + getMagicDNSSuffix() + ",Self:" + getSelf() + ",ExitNode:" + getExitNode() + ",KeyAuth:" + getKeyAuth() + ",CanShareFiles:" + getCanShareFiles() + ",WaitingFileCount:" + getWaitingFileCount() + ",ReceivingFileCount:" + getReceivingFileCount() + ",UnreadFileCount:" + getUnreadFileCount() + ",}";
    }

    public native TailscaleUserGroupIterator userGroups();

    public TailscaleEndpointStatus(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
