package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class TailscalePingResult implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public TailscalePingResult() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof TailscalePingResult)) {
            return false;
        }
        TailscalePingResult tailscalePingResult = (TailscalePingResult) obj;
        if (getLatencyMs() != tailscalePingResult.getLatencyMs() || getIsDirect() != tailscalePingResult.getIsDirect()) {
            return false;
        }
        String endpoint = getEndpoint();
        String endpoint2 = tailscalePingResult.getEndpoint();
        if (endpoint == null) {
            if (endpoint2 != null) {
                return false;
            }
        } else if (!endpoint.equals(endpoint2)) {
            return false;
        }
        String peerRelay = getPeerRelay();
        String peerRelay2 = tailscalePingResult.getPeerRelay();
        if (peerRelay == null) {
            if (peerRelay2 != null) {
                return false;
            }
        } else if (!peerRelay.equals(peerRelay2)) {
            return false;
        }
        if (getDERPRegionID() != tailscalePingResult.getDERPRegionID()) {
            return false;
        }
        String dERPRegionCode = getDERPRegionCode();
        String dERPRegionCode2 = tailscalePingResult.getDERPRegionCode();
        if (dERPRegionCode == null) {
            if (dERPRegionCode2 != null) {
                return false;
            }
        } else if (!dERPRegionCode.equals(dERPRegionCode2)) {
            return false;
        }
        String error = getError();
        String error2 = tailscalePingResult.getError();
        if (error == null) {
            return error2 == null;
        }
        return error.equals(error2);
    }

    public final native String getDERPRegionCode();

    public final native int getDERPRegionID();

    public final native String getEndpoint();

    public final native String getError();

    public final native boolean getIsDirect();

    public final native double getLatencyMs();

    public final native String getPeerRelay();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Double.valueOf(getLatencyMs()), Boolean.valueOf(getIsDirect()), getEndpoint(), getPeerRelay(), Integer.valueOf(getDERPRegionID()), getDERPRegionCode(), getError()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setDERPRegionCode(String str);

    public final native void setDERPRegionID(int i);

    public final native void setEndpoint(String str);

    public final native void setError(String str);

    public final native void setIsDirect(boolean z);

    public final native void setLatencyMs(double d);

    public final native void setPeerRelay(String str);

    public String toString() {
        return "TailscalePingResult{LatencyMs:" + getLatencyMs() + ",IsDirect:" + getIsDirect() + ",Endpoint:" + getEndpoint() + ",PeerRelay:" + getPeerRelay() + ",DERPRegionID:" + getDERPRegionID() + ",DERPRegionCode:" + getDERPRegionCode() + ",Error:" + getError() + ",}";
    }

    public TailscalePingResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
