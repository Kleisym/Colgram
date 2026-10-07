package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class BridgeOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public BridgeOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof BridgeOptions)) {
            return false;
        }
        BridgeOptions bridgeOptions = (BridgeOptions) obj;
        String bridgeName = getBridgeName();
        String bridgeName2 = bridgeOptions.getBridgeName();
        if (bridgeName == null) {
            if (bridgeName2 != null) {
                return false;
            }
        } else if (!bridgeName.equals(bridgeName2)) {
            return false;
        }
        if (getMTU() != bridgeOptions.getMTU()) {
            return false;
        }
        String inet4Port = getInet4Port();
        String inet4Port2 = bridgeOptions.getInet4Port();
        if (inet4Port == null) {
            if (inet4Port2 != null) {
                return false;
            }
        } else if (!inet4Port.equals(inet4Port2)) {
            return false;
        }
        String inet6Port = getInet6Port();
        String inet6Port2 = bridgeOptions.getInet6Port();
        if (inet6Port == null) {
            if (inet6Port2 != null) {
                return false;
            }
        } else if (!inet6Port.equals(inet6Port2)) {
            return false;
        }
        String str = getInterface();
        String str2 = bridgeOptions.getInterface();
        if (str == null) {
            if (str2 != null) {
                return false;
            }
        } else if (!str.equals(str2)) {
            return false;
        }
        return getRuleIndex() == bridgeOptions.getRuleIndex() && getRouteTable() == bridgeOptions.getRouteTable();
    }

    public final native String getBridgeName();

    public final native String getInet4Port();

    public final native String getInet6Port();

    public final native String getInterface();

    public final native int getMTU();

    public final native int getRouteTable();

    public final native int getRuleIndex();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getBridgeName(), Integer.valueOf(getMTU()), getInet4Port(), getInet6Port(), getInterface(), Integer.valueOf(getRuleIndex()), Integer.valueOf(getRouteTable())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setBridgeName(String str);

    public final native void setInet4Port(String str);

    public final native void setInet6Port(String str);

    public final native void setInterface(String str);

    public final native void setMTU(int i);

    public final native void setRouteTable(int i);

    public final native void setRuleIndex(int i);

    public String toString() {
        return "BridgeOptions{BridgeName:" + getBridgeName() + ",MTU:" + getMTU() + ",Inet4Port:" + getInet4Port() + ",Inet6Port:" + getInet6Port() + ",Interface:" + getInterface() + ",RuleIndex:" + getRuleIndex() + ",RouteTable:" + getRouteTable() + ",}";
    }

    public BridgeOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
