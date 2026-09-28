package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBDeviceDescriptor implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBDeviceDescriptor(String str, String str2) {
        int i__NewUSBDeviceDescriptor = __NewUSBDeviceDescriptor(str, str2);
        this.refnum = i__NewUSBDeviceDescriptor;
        Seq.trackGoRef(i__NewUSBDeviceDescriptor, this);
    }

    private static native int __NewUSBDeviceDescriptor(String str, String str2);

    public native void addInterface(int i, int i2, int i3);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBDeviceDescriptor)) {
            return false;
        }
        USBDeviceDescriptor uSBDeviceDescriptor = (USBDeviceDescriptor) obj;
        String serverTag = getServerTag();
        String serverTag2 = uSBDeviceDescriptor.getServerTag();
        if (serverTag == null) {
            if (serverTag2 != null) {
                return false;
            }
        } else if (!serverTag.equals(serverTag2)) {
            return false;
        }
        String deviceID = getDeviceID();
        String deviceID2 = uSBDeviceDescriptor.getDeviceID();
        if (deviceID == null) {
            if (deviceID2 != null) {
                return false;
            }
        } else if (!deviceID.equals(deviceID2)) {
            return false;
        }
        if (getBusNum() != uSBDeviceDescriptor.getBusNum() || getDevNum() != uSBDeviceDescriptor.getDevNum() || getSpeed() != uSBDeviceDescriptor.getSpeed() || getVendorID() != uSBDeviceDescriptor.getVendorID() || getProductID() != uSBDeviceDescriptor.getProductID() || getBCDDevice() != uSBDeviceDescriptor.getBCDDevice() || getDeviceClass() != uSBDeviceDescriptor.getDeviceClass() || getDeviceSubClass() != uSBDeviceDescriptor.getDeviceSubClass() || getDeviceProtocol() != uSBDeviceDescriptor.getDeviceProtocol() || getConfigurationValue() != uSBDeviceDescriptor.getConfigurationValue() || getNumConfigurations() != uSBDeviceDescriptor.getNumConfigurations()) {
            return false;
        }
        String serial = getSerial();
        String serial2 = uSBDeviceDescriptor.getSerial();
        if (serial == null) {
            if (serial2 != null) {
                return false;
            }
        } else if (!serial.equals(serial2)) {
            return false;
        }
        String product = getProduct();
        String product2 = uSBDeviceDescriptor.getProduct();
        if (product == null) {
            return product2 == null;
        }
        return product.equals(product2);
    }

    public final native int getBCDDevice();

    public final native int getBusNum();

    public final native int getConfigurationValue();

    public final native int getDevNum();

    public final native int getDeviceClass();

    public final native String getDeviceID();

    public final native int getDeviceProtocol();

    public final native int getDeviceSubClass();

    public final native int getNumConfigurations();

    public final native String getProduct();

    public final native int getProductID();

    public final native String getSerial();

    public final native String getServerTag();

    public final native int getSpeed();

    public final native int getVendorID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getServerTag(), getDeviceID(), Integer.valueOf(getBusNum()), Integer.valueOf(getDevNum()), Integer.valueOf(getSpeed()), Integer.valueOf(getVendorID()), Integer.valueOf(getProductID()), Integer.valueOf(getBCDDevice()), Integer.valueOf(getDeviceClass()), Integer.valueOf(getDeviceSubClass()), Integer.valueOf(getDeviceProtocol()), Integer.valueOf(getConfigurationValue()), Integer.valueOf(getNumConfigurations()), getSerial(), getProduct()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setBCDDevice(int i);

    public final native void setBusNum(int i);

    public final native void setConfigurationValue(int i);

    public final native void setDevNum(int i);

    public final native void setDeviceClass(int i);

    public final native void setDeviceID(String str);

    public final native void setDeviceProtocol(int i);

    public final native void setDeviceSubClass(int i);

    public final native void setNumConfigurations(int i);

    public final native void setProduct(String str);

    public final native void setProductID(int i);

    public final native void setSerial(String str);

    public final native void setServerTag(String str);

    public final native void setSpeed(int i);

    public final native void setVendorID(int i);

    public String toString() {
        return "USBDeviceDescriptor{ServerTag:" + getServerTag() + ",DeviceID:" + getDeviceID() + ",BusNum:" + getBusNum() + ",DevNum:" + getDevNum() + ",Speed:" + getSpeed() + ",VendorID:" + getVendorID() + ",ProductID:" + getProductID() + ",BCDDevice:" + getBCDDevice() + ",DeviceClass:" + getDeviceClass() + ",DeviceSubClass:" + getDeviceSubClass() + ",DeviceProtocol:" + getDeviceProtocol() + ",ConfigurationValue:" + getConfigurationValue() + ",NumConfigurations:" + getNumConfigurations() + ",Serial:" + getSerial() + ",Product:" + getProduct() + ",}";
    }

    public USBDeviceDescriptor(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
