package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscalePeer implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscalePeer() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscalePeer)) {
            return false;
        }
        TailscalePeer tailscalePeer = (TailscalePeer) obj;
        String stableID = getStableID();
        String stableID2 = tailscalePeer.getStableID();
        if (stableID == null) {
            if (stableID2 != null) {
                return false;
            }
        } else if (!stableID.equals(stableID2)) {
            return false;
        }
        String hostName = getHostName();
        String hostName2 = tailscalePeer.getHostName();
        if (hostName == null) {
            if (hostName2 != null) {
                return false;
            }
        } else if (!hostName.equals(hostName2)) {
            return false;
        }
        String dNSName = getDNSName();
        String dNSName2 = tailscalePeer.getDNSName();
        if (dNSName == null) {
            if (dNSName2 != null) {
                return false;
            }
        } else if (!dNSName.equals(dNSName2)) {
            return false;
        }
        String os = getOS();
        String os2 = tailscalePeer.getOS();
        if (os == null) {
            if (os2 != null) {
                return false;
            }
        } else if (!os.equals(os2)) {
            return false;
        }
        return getOnline() == tailscalePeer.getOnline() && getExitNode() == tailscalePeer.getExitNode() && getExitNodeOption() == tailscalePeer.getExitNodeOption() && getShareeNode() == tailscalePeer.getShareeNode() && getExpired() == tailscalePeer.getExpired() && getActive() == tailscalePeer.getActive() && getCanReceiveFiles() == tailscalePeer.getCanReceiveFiles() && getRxBytes() == tailscalePeer.getRxBytes() && getTxBytes() == tailscalePeer.getTxBytes() && getKeyExpiry() == tailscalePeer.getKeyExpiry() && getLastSeen() == tailscalePeer.getLastSeen();
    }

    public final native boolean getActive();

    public final native boolean getCanReceiveFiles();

    public final native String getDNSName();

    public final native boolean getExitNode();

    public final native boolean getExitNodeOption();

    public final native boolean getExpired();

    public final native String getHostName();

    public final native long getKeyExpiry();

    public final native long getLastSeen();

    public final native String getOS();

    public final native boolean getOnline();

    public final native long getRxBytes();

    public final native boolean getShareeNode();

    public final native String getStableID();

    public final native long getTxBytes();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getStableID(), getHostName(), getDNSName(), getOS(), Boolean.valueOf(getOnline()), Boolean.valueOf(getExitNode()), Boolean.valueOf(getExitNodeOption()), Boolean.valueOf(getShareeNode()), Boolean.valueOf(getExpired()), Boolean.valueOf(getActive()), Boolean.valueOf(getCanReceiveFiles()), Long.valueOf(getRxBytes()), Long.valueOf(getTxBytes()), Long.valueOf(getKeyExpiry()), Long.valueOf(getLastSeen())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setActive(boolean z);

    public final native void setCanReceiveFiles(boolean z);

    public final native void setDNSName(String str);

    public final native void setExitNode(boolean z);

    public final native void setExitNodeOption(boolean z);

    public final native void setExpired(boolean z);

    public final native void setHostName(String str);

    public final native void setKeyExpiry(long j);

    public final native void setLastSeen(long j);

    public final native void setOS(String str);

    public final native void setOnline(boolean z);

    public final native void setRxBytes(long j);

    public final native void setShareeNode(boolean z);

    public final native void setStableID(String str);

    public final native void setTxBytes(long j);

    public native StringIterator sshHostKeys();

    public native StringIterator tailscaleIPs();

    public String toString() {
        return "TailscalePeer{StableID:" + getStableID() + ",HostName:" + getHostName() + ",DNSName:" + getDNSName() + ",OS:" + getOS() + ",Online:" + getOnline() + ",ExitNode:" + getExitNode() + ",ExitNodeOption:" + getExitNodeOption() + ",ShareeNode:" + getShareeNode() + ",Expired:" + getExpired() + ",Active:" + getActive() + ",CanReceiveFiles:" + getCanReceiveFiles() + ",RxBytes:" + getRxBytes() + ",TxBytes:" + getTxBytes() + ",KeyExpiry:" + getKeyExpiry() + ",LastSeen:" + getLastSeen() + ",}";
    }

    public TailscalePeer(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
