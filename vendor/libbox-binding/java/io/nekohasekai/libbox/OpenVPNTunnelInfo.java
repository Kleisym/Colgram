package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenVPNTunnelInfo implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenVPNTunnelInfo() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public native StringIterator dns();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenVPNTunnelInfo)) {
            return false;
        }
        OpenVPNTunnelInfo openVPNTunnelInfo = (OpenVPNTunnelInfo) obj;
        String server = getServer();
        String server2 = openVPNTunnelInfo.getServer();
        if (server == null) {
            if (server2 != null) {
                return false;
            }
        } else if (!server.equals(server2)) {
            return false;
        }
        String network = getNetwork();
        String network2 = openVPNTunnelInfo.getNetwork();
        if (network == null) {
            if (network2 != null) {
                return false;
            }
        } else if (!network.equals(network2)) {
            return false;
        }
        String cipher = getCipher();
        String cipher2 = openVPNTunnelInfo.getCipher();
        if (cipher == null) {
            if (cipher2 != null) {
                return false;
            }
        } else if (!cipher.equals(cipher2)) {
            return false;
        }
        return getMTU() == openVPNTunnelInfo.getMTU() && getConnectedSince() == openVPNTunnelInfo.getConnectedSince();
    }

    public final native String getCipher();

    public final native long getConnectedSince();

    public final native int getMTU();

    public final native String getNetwork();

    public final native String getServer();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getServer(), getNetwork(), getCipher(), Integer.valueOf(getMTU()), Long.valueOf(getConnectedSince())});
    }

    public native StringIterator iPv4();

    public native StringIterator iPv6();

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setCipher(String str);

    public final native void setConnectedSince(long j);

    public final native void setMTU(int i);

    public final native void setNetwork(String str);

    public final native void setServer(String str);

    public String toString() {
        return "OpenVPNTunnelInfo{Server:" + getServer() + ",Network:" + getNetwork() + ",Cipher:" + getCipher() + ",MTU:" + getMTU() + ",ConnectedSince:" + getConnectedSince() + ",}";
    }

    public OpenVPNTunnelInfo(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
