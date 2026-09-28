package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBLocalProvidedDevice implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBLocalProvidedDevice() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBLocalProvidedDevice)) {
            return false;
        }
        USBLocalProvidedDevice uSBLocalProvidedDevice = (USBLocalProvidedDevice) obj;
        String serverTag = getServerTag();
        String serverTag2 = uSBLocalProvidedDevice.getServerTag();
        if (serverTag == null) {
            if (serverTag2 != null) {
                return false;
            }
        } else if (!serverTag.equals(serverTag2)) {
            return false;
        }
        String deviceID = getDeviceID();
        String deviceID2 = uSBLocalProvidedDevice.getDeviceID();
        if (deviceID == null) {
            if (deviceID2 != null) {
                return false;
            }
        } else if (!deviceID.equals(deviceID2)) {
            return false;
        }
        String localDeviceID = getLocalDeviceID();
        String localDeviceID2 = uSBLocalProvidedDevice.getLocalDeviceID();
        if (localDeviceID == null) {
            if (localDeviceID2 != null) {
                return false;
            }
        } else if (!localDeviceID.equals(localDeviceID2)) {
            return false;
        }
        String label = getLabel();
        String label2 = uSBLocalProvidedDevice.getLabel();
        if (label == null) {
            if (label2 != null) {
                return false;
            }
        } else if (!label.equals(label2)) {
            return false;
        }
        return getVendorID() == uSBLocalProvidedDevice.getVendorID() && getProductID() == uSBLocalProvidedDevice.getProductID();
    }

    public final native String getDeviceID();

    public final native String getLabel();

    public final native String getLocalDeviceID();

    public final native int getProductID();

    public final native String getServerTag();

    public final native int getVendorID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getServerTag(), getDeviceID(), getLocalDeviceID(), getLabel(), Integer.valueOf(getVendorID()), Integer.valueOf(getProductID())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setDeviceID(String str);

    public final native void setLabel(String str);

    public final native void setLocalDeviceID(String str);

    public final native void setProductID(int i);

    public final native void setServerTag(String str);

    public final native void setVendorID(int i);

    public String toString() {
        return "USBLocalProvidedDevice{ServerTag:" + getServerTag() + ",DeviceID:" + getDeviceID() + ",LocalDeviceID:" + getLocalDeviceID() + ",Label:" + getLabel() + ",VendorID:" + getVendorID() + ",ProductID:" + getProductID() + ",}";
    }

    public USBLocalProvidedDevice(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
