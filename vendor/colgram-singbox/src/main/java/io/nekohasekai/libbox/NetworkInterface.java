package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class NetworkInterface implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public NetworkInterface() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NetworkInterface)) {
            return false;
        }
        NetworkInterface networkInterface = (NetworkInterface) obj;
        if (getIndex() != networkInterface.getIndex() || getMTU() != networkInterface.getMTU()) {
            return false;
        }
        String name = getName();
        String name2 = networkInterface.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        StringIterator addresses = getAddresses();
        StringIterator addresses2 = networkInterface.getAddresses();
        if (addresses == null) {
            if (addresses2 != null) {
                return false;
            }
        } else if (!addresses.equals(addresses2)) {
            return false;
        }
        if (getFlags() != networkInterface.getFlags() || getType() != networkInterface.getType()) {
            return false;
        }
        StringIterator dNSServer = getDNSServer();
        StringIterator dNSServer2 = networkInterface.getDNSServer();
        if (dNSServer == null) {
            if (dNSServer2 != null) {
                return false;
            }
        } else if (!dNSServer.equals(dNSServer2)) {
            return false;
        }
        StringIterator gateway = getGateway();
        StringIterator gateway2 = networkInterface.getGateway();
        if (gateway == null) {
            if (gateway2 != null) {
                return false;
            }
        } else if (!gateway.equals(gateway2)) {
            return false;
        }
        return getMetered() == networkInterface.getMetered();
    }

    public final native StringIterator getAddresses();

    public final native StringIterator getDNSServer();

    public final native int getFlags();

    public final native StringIterator getGateway();

    public final native int getIndex();

    public final native int getMTU();

    public final native boolean getMetered();

    public final native String getName();

    public final native int getType();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getIndex()), Integer.valueOf(getMTU()), getName(), getAddresses(), Integer.valueOf(getFlags()), Integer.valueOf(getType()), getDNSServer(), getGateway(), Boolean.valueOf(getMetered())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAddresses(StringIterator stringIterator);

    public final native void setDNSServer(StringIterator stringIterator);

    public final native void setFlags(int i);

    public final native void setGateway(StringIterator stringIterator);

    public final native void setIndex(int i);

    public final native void setMTU(int i);

    public final native void setMetered(boolean z);

    public final native void setName(String str);

    public final native void setType(int i);

    public String toString() {
        return "NetworkInterface{Index:" + getIndex() + ",MTU:" + getMTU() + ",Name:" + getName() + ",Addresses:" + getAddresses() + ",Flags:" + getFlags() + ",Type:" + getType() + ",DNSServer:" + getDNSServer() + ",Gateway:" + getGateway() + ",Metered:" + getMetered() + ",}";
    }

    public NetworkInterface(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
