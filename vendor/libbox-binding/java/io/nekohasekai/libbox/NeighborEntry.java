package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class NeighborEntry implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public NeighborEntry() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NeighborEntry)) {
            return false;
        }
        NeighborEntry neighborEntry = (NeighborEntry) obj;
        String address = getAddress();
        String address2 = neighborEntry.getAddress();
        if (address == null) {
            if (address2 != null) {
                return false;
            }
        } else if (!address.equals(address2)) {
            return false;
        }
        String macAddress = getMacAddress();
        String macAddress2 = neighborEntry.getMacAddress();
        if (macAddress == null) {
            if (macAddress2 != null) {
                return false;
            }
        } else if (!macAddress.equals(macAddress2)) {
            return false;
        }
        String hostname = getHostname();
        String hostname2 = neighborEntry.getHostname();
        if (hostname == null) {
            return hostname2 == null;
        }
        return hostname.equals(hostname2);
    }

    public final native String getAddress();

    public final native String getHostname();

    public final native String getMacAddress();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getAddress(), getMacAddress(), getHostname()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAddress(String str);

    public final native void setHostname(String str);

    public final native void setMacAddress(String str);

    public String toString() {
        return "NeighborEntry{Address:" + getAddress() + ",MacAddress:" + getMacAddress() + ",Hostname:" + getHostname() + ",}";
    }

    public NeighborEntry(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
