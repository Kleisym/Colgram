package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBSharedDevice implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBSharedDevice() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBSharedDevice)) {
            return false;
        }
        USBSharedDevice uSBSharedDevice = (USBSharedDevice) obj;
        String busID = getBusID();
        String busID2 = uSBSharedDevice.getBusID();
        if (busID == null) {
            if (busID2 != null) {
                return false;
            }
        } else if (!busID.equals(busID2)) {
            return false;
        }
        String stableID = getStableID();
        String stableID2 = uSBSharedDevice.getStableID();
        if (stableID == null) {
            if (stableID2 != null) {
                return false;
            }
        } else if (!stableID.equals(stableID2)) {
            return false;
        }
        if (getBackend() != uSBSharedDevice.getBackend() || getState() != uSBSharedDevice.getState()) {
            return false;
        }
        String deviceID = getDeviceID();
        String deviceID2 = uSBSharedDevice.getDeviceID();
        if (deviceID == null) {
            if (deviceID2 != null) {
                return false;
            }
        } else if (!deviceID.equals(deviceID2)) {
            return false;
        }
        if (getBusNum() != uSBSharedDevice.getBusNum() || getDevNum() != uSBSharedDevice.getDevNum() || getSpeed() != uSBSharedDevice.getSpeed() || getVendorID() != uSBSharedDevice.getVendorID() || getProductID() != uSBSharedDevice.getProductID() || getBCDDevice() != uSBSharedDevice.getBCDDevice() || getDeviceClass() != uSBSharedDevice.getDeviceClass() || getDeviceSubClass() != uSBSharedDevice.getDeviceSubClass() || getDeviceProtocol() != uSBSharedDevice.getDeviceProtocol() || getConfigurationValue() != uSBSharedDevice.getConfigurationValue() || getNumConfigurations() != uSBSharedDevice.getNumConfigurations()) {
            return false;
        }
        String serial = getSerial();
        String serial2 = uSBSharedDevice.getSerial();
        if (serial == null) {
            if (serial2 != null) {
                return false;
            }
        } else if (!serial.equals(serial2)) {
            return false;
        }
        String product = getProduct();
        String product2 = uSBSharedDevice.getProduct();
        if (product == null) {
            return product2 == null;
        }
        return product.equals(product2);
    }

    public final native int getBCDDevice();

    public final native int getBackend();

    public final native String getBusID();

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

    public final native int getSpeed();

    public final native String getStableID();

    public final native int getState();

    public final native int getVendorID();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getBusID(), getStableID(), Integer.valueOf(getBackend()), Integer.valueOf(getState()), getDeviceID(), Integer.valueOf(getBusNum()), Integer.valueOf(getDevNum()), Integer.valueOf(getSpeed()), Integer.valueOf(getVendorID()), Integer.valueOf(getProductID()), Integer.valueOf(getBCDDevice()), Integer.valueOf(getDeviceClass()), Integer.valueOf(getDeviceSubClass()), Integer.valueOf(getDeviceProtocol()), Integer.valueOf(getConfigurationValue()), Integer.valueOf(getNumConfigurations()), getSerial(), getProduct()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native USBSharedDeviceInterfaceIterator interfaces();

    public final native void setBCDDevice(int i);

    public final native void setBackend(int i);

    public final native void setBusID(String str);

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

    public final native void setSpeed(int i);

    public final native void setStableID(String str);

    public final native void setState(int i);

    public final native void setVendorID(int i);

    public String toString() {
        return "USBSharedDevice{BusID:" + getBusID() + ",StableID:" + getStableID() + ",Backend:" + getBackend() + ",State:" + getState() + ",DeviceID:" + getDeviceID() + ",BusNum:" + getBusNum() + ",DevNum:" + getDevNum() + ",Speed:" + getSpeed() + ",VendorID:" + getVendorID() + ",ProductID:" + getProductID() + ",BCDDevice:" + getBCDDevice() + ",DeviceClass:" + getDeviceClass() + ",DeviceSubClass:" + getDeviceSubClass() + ",DeviceProtocol:" + getDeviceProtocol() + ",ConfigurationValue:" + getConfigurationValue() + ",NumConfigurations:" + getNumConfigurations() + ",Serial:" + getSerial() + ",Product:" + getProduct() + ",}";
    }

    public USBSharedDevice(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
