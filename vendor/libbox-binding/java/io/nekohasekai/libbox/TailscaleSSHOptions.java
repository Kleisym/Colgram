package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscaleSSHOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscaleSSHOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscaleSSHOptions)) {
            return false;
        }
        TailscaleSSHOptions tailscaleSSHOptions = (TailscaleSSHOptions) obj;
        String endpointTag = getEndpointTag();
        String endpointTag2 = tailscaleSSHOptions.getEndpointTag();
        if (endpointTag == null) {
            if (endpointTag2 != null) {
                return false;
            }
        } else if (!endpointTag.equals(endpointTag2)) {
            return false;
        }
        String peerAddress = getPeerAddress();
        String peerAddress2 = tailscaleSSHOptions.getPeerAddress();
        if (peerAddress == null) {
            if (peerAddress2 != null) {
                return false;
            }
        } else if (!peerAddress.equals(peerAddress2)) {
            return false;
        }
        String username = getUsername();
        String username2 = tailscaleSSHOptions.getUsername();
        if (username == null) {
            if (username2 != null) {
                return false;
            }
        } else if (!username.equals(username2)) {
            return false;
        }
        String terminalType = getTerminalType();
        String terminalType2 = tailscaleSSHOptions.getTerminalType();
        if (terminalType == null) {
            if (terminalType2 != null) {
                return false;
            }
        } else if (!terminalType.equals(terminalType2)) {
            return false;
        }
        if (getColumns() != tailscaleSSHOptions.getColumns() || getRows() != tailscaleSSHOptions.getRows() || getWidthPixels() != tailscaleSSHOptions.getWidthPixels() || getHeightPixels() != tailscaleSSHOptions.getHeightPixels()) {
            return false;
        }
        StringIterator hostKeys = getHostKeys();
        StringIterator hostKeys2 = tailscaleSSHOptions.getHostKeys();
        if (hostKeys == null) {
            if (hostKeys2 != null) {
                return false;
            }
        } else if (!hostKeys.equals(hostKeys2)) {
            return false;
        }
        return getForwardAgent() == tailscaleSSHOptions.getForwardAgent();
    }

    public final native int getColumns();

    public final native String getEndpointTag();

    public final native boolean getForwardAgent();

    public final native int getHeightPixels();

    public final native StringIterator getHostKeys();

    public final native String getPeerAddress();

    public final native int getRows();

    public final native String getTerminalType();

    public final native String getUsername();

    public final native int getWidthPixels();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getEndpointTag(), getPeerAddress(), getUsername(), getTerminalType(), Integer.valueOf(getColumns()), Integer.valueOf(getRows()), Integer.valueOf(getWidthPixels()), Integer.valueOf(getHeightPixels()), getHostKeys(), Boolean.valueOf(getForwardAgent())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setColumns(int i);

    public final native void setEndpointTag(String str);

    public final native void setForwardAgent(boolean z);

    public final native void setHeightPixels(int i);

    public final native void setHostKeys(StringIterator stringIterator);

    public final native void setPeerAddress(String str);

    public final native void setRows(int i);

    public final native void setTerminalType(String str);

    public final native void setUsername(String str);

    public final native void setWidthPixels(int i);

    public String toString() {
        return "TailscaleSSHOptions{EndpointTag:" + getEndpointTag() + ",PeerAddress:" + getPeerAddress() + ",Username:" + getUsername() + ",TerminalType:" + getTerminalType() + ",Columns:" + getColumns() + ",Rows:" + getRows() + ",WidthPixels:" + getWidthPixels() + ",HeightPixels:" + getHeightPixels() + ",HostKeys:" + getHostKeys() + ",ForwardAgent:" + getForwardAgent() + ",}";
    }

    public TailscaleSSHOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
