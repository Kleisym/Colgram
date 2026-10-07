package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBSharedDeviceInterface implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBSharedDeviceInterface() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBSharedDeviceInterface)) {
            return false;
        }
        USBSharedDeviceInterface uSBSharedDeviceInterface = (USBSharedDeviceInterface) obj;
        return getInterfaceClass() == uSBSharedDeviceInterface.getInterfaceClass() && getInterfaceSubClass() == uSBSharedDeviceInterface.getInterfaceSubClass() && getInterfaceProtocol() == uSBSharedDeviceInterface.getInterfaceProtocol();
    }

    public final native int getInterfaceClass();

    public final native int getInterfaceProtocol();

    public final native int getInterfaceSubClass();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getInterfaceClass()), Integer.valueOf(getInterfaceSubClass()), Integer.valueOf(getInterfaceProtocol())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setInterfaceClass(int i);

    public final native void setInterfaceProtocol(int i);

    public final native void setInterfaceSubClass(int i);

    public String toString() {
        return "USBSharedDeviceInterface{InterfaceClass:" + getInterfaceClass() + ",InterfaceSubClass:" + getInterfaceSubClass() + ",InterfaceProtocol:" + getInterfaceProtocol() + ",}";
    }

    public USBSharedDeviceInterface(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
